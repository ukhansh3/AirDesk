using System.Security.Cryptography;
using System.Text;

namespace AirDesk.Core;

public interface IHostClock { long MonotonicMs { get; } long UtcMs { get; } }
public sealed class SystemHostClock : IHostClock
{
    public long MonotonicMs => Environment.TickCount64;
    public long UtcMs => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
public sealed record LocalOffer(string PairingId, long ExpiresAtMs);
public sealed record HostSnapshot(string State, string Safety, string? PendingDevice, string? PendingSession, string? ConnectionId, int HeldButtons, int HeldKeys, int MockCalls, int NativeCalls, string DisplayId, string[] RequestedCapabilities, string? PendingFingerprint, string? SessionFingerprint);

public sealed class HostCoordinator
{
    private sealed class Offer(string id, string code, byte[] salt, byte[] hash, long deadline, long utcExpiry)
    {
        public readonly string Id = id; public string? Code = code; public readonly byte[] Salt = salt, Hash = hash;
        public readonly long Deadline = deadline, UtcExpiry = utcExpiry;
        public int Failures; public PeerIdentity? Claimant; public string? ClaimSession; public bool HostApproved, VehicleApproved;
        public long ApprovalDeadline;
    }
    private sealed class Session(PeerIdentity peer, string id, string requestId, HashSet<string> capabilities, long deadline, long utcExpiry, long now)
    {
        public readonly PeerIdentity Peer = peer; public readonly string Id = id, RequestId = requestId;
        public readonly HashSet<string> Capabilities = capabilities;
        public readonly long Deadline = deadline, UtcExpiry = utcExpiry;
        public long LastHeartbeat = now, LastAck = -1, LastAckProgress = now, GrantSequence = -1; public bool Approved; public string? GrantId;
    }
    private sealed record ReceiveWindow(long Sequence, long ProducerTime, long Offset);
    private readonly object _sync = new();
    private readonly DeviceRegistry _registry;
    private readonly IHostClock _clock;
    private readonly SchemaCatalog _schemas;
    private readonly Dictionary<(string Peer, string Session), ReceiveWindow> _received = [];
    private readonly Dictionary<(string Peer, string Session), long> _sent = [];
    private readonly Dictionary<(string Peer, string Session), long> _delivered = [];
    private readonly Dictionary<string, string> _pairingSessions = [];
    private readonly HashSet<string> _usedSessions = [];
    private readonly Queue<(string Peer, WireMessage Message)> _outbox = [];
    private readonly MockInputBackend _mockBackend = new();
    private readonly InputState _mockInput;
    private readonly MockInputPermit _mockPermit = new();
    private Offer? _offer;
    private Session? _session;
    private long _cooldownUntil;
    private string _state = "DISCONNECTED";
    private DisplayBounds _display = new("primary", 0, 0, 1920, 1080);
    public event Action? Changed;
    public HostCoordinator(DeviceRegistry registry, SchemaCatalog schemas, IHostClock? clock = null)
    { _registry = registry; _schemas = schemas; _clock = clock ?? new SystemHostClock(); _mockInput = new(_mockBackend); }
    public HostSnapshot Snapshot { get { lock (_sync) return new(_state, "UNKNOWN", _offer?.Claimant is { } peer ? _registry.Find(peer.Fingerprint)?.Label : null, _session is { Approved: false } s ? s.RequestId : null, _session is { } active ? _registry.Find(active.Peer.Fingerprint)?.ConnectionId : null, _mockInput.HeldButtons, _mockInput.HeldKeys, _mockBackend.Calls, (int)NativeInputBackend.AttemptedCalls, _display.Id, _session?.Capabilities.Order().ToArray() ?? [], _offer?.Claimant?.Fingerprint, _session?.Peer.Fingerprint); } }
    public string? LocalPairingCode { get { lock (_sync) { TickLocked(); return _offer?.Code; } } }
    public LocalOffer? CurrentOffer { get { lock (_sync) { TickLocked(); return _offer is null ? null : new(_offer.Id, _offer.UtcExpiry); } } }
    public LocalOffer BeginPairing()
    {
        lock (_sync)
        {
            TickLocked();
            if (_clock.MonotonicMs < _cooldownUntil) throw new ProtocolFailure("RATE_LIMITED");
            CancelPairingLocked("REPLACED");
            var code = RandomNumberGenerator.GetInt32(100_000_000).ToString("D8");
            var salt = RandomNumberGenerator.GetBytes(32);
            _offer = new(Guid.NewGuid().ToString("N"), code, salt, HMACSHA256.HashData(salt, Encoding.ASCII.GetBytes(code)), _clock.MonotonicMs + 120_000, _clock.UtcMs + 120_000);
            _state = "PAIRING";
            Changed?.Invoke(); return new(_offer.Id, _offer.UtcExpiry);
        }
    }
    public void CancelPairing() { lock (_sync) { CancelPairingLocked("USER_REQUEST"); Changed?.Invoke(); } }
    private void CancelPairingLocked(string reason)
    {
        if (_offer?.Claimant is { } peer && _offer.ClaimSession is { } sid)
            Emit(peer, sid, "pairing.revoked", new { pairingId = _offer.Id, reason });
        if (_offer is not null) _offer.Code = null;
        _offer = null;
        if (_session is null) _state = "DISCONNECTED";
    }
    public void ApprovePairing(bool approved)
    {
        lock (_sync)
        {
            TickLocked();
            if (_offer?.Claimant is not { } peer || _offer.ClaimSession is not { } sid) throw new ProtocolFailure("NOT_PAIRED");
            Emit(peer, sid, "pairing.approval", new { pairingId = _offer.Id, side = "HOST", approved });
            if (!approved) CancelPairingLocked("USER_REQUEST");
            else { _offer.HostApproved = true; CompletePairingLocked(); }
            Changed?.Invoke();
        }
    }
    private void CompletePairingLocked()
    {
        if (_offer is not { HostApproved: true, VehicleApproved: true, Claimant: not null, ClaimSession: not null }) return;
        var connection = Guid.NewGuid().ToString("N");
        _registry.MarkPaired(_offer.Claimant, _offer.Id, connection);
        Emit(_offer.Claimant, _offer.ClaimSession, "pairing.completed", new { pairingId = _offer.Id, connectionId = connection });
        _offer.Code = null; _offer = null; _state = "DISCONNECTED";
    }
    public void ApproveSession(bool approved)
    {
        lock (_sync)
        {
            TickLocked();
            if (_session is not { Approved: false } session) throw new ProtocolFailure("SESSION_NOT_FOUND");
            Emit(session.Peer, session.Id, "session.approve", new { requestId = session.RequestId, approved, expiresAtMs = session.UtcExpiry });
            if (!approved) EndSessionLocked("USER_REQUEST");
            else
            {
                session.Approved = true; session.GrantId = Guid.NewGuid().ToString("N"); session.LastHeartbeat = _clock.MonotonicMs;
                _state = "CONNECTED";
                Emit(session.Peer, session.Id, "session.granted", new { grantId = session.GrantId, capabilities = session.Capabilities.Order().ToArray(), expiresAtMs = session.UtcExpiry });
                session.GrantSequence = _sent[(session.Peer.Fingerprint, session.Id)] - 1;
                session.LastAckProgress = _clock.MonotonicMs;
            }
            Changed?.Invoke();
        }
    }
    public void EndSession(string reason = "USER_REQUEST") { lock (_sync) { EndSessionLocked(reason); Changed?.Invoke(); } }
    private void EndSessionLocked(string reason)
    {
        var session = _session;
        _session = null;
        var released = _mockInput.ReleaseAll();
        _state = !released || reason is "TIMEOUT" or "SAFETY_LOCK" or "AUTH_REVOKED" ? "LOCKED" : "DISCONNECTED";
        if (session is not null) Emit(session.Peer, session.Id, "session.ended", new { reason });
    }
    public void RevokeDevice(string fingerprint)
    {
        lock (_sync)
        {
            var device = _registry.Find(fingerprint);
            if (device?.PairingId is not null && _pairingSessions.TryGetValue(fingerprint, out var sid))
                Emit(new PeerIdentity(fingerprint), sid, "pairing.revoked", new { pairingId = device.PairingId, reason = "USER_REQUEST" });
            try { _registry.Revoke(fingerprint); }
            finally
            {
                // A persistence failure cannot defer release of already-owned input.
                if (_offer?.Claimant?.Fingerprint == fingerprint) CancelPairingLocked("AUTH_FAILURE");
                if (_session?.Peer.Fingerprint == fingerprint) EndSessionLocked("AUTH_REVOKED");
                Changed?.Invoke();
            }
        }
    }
    public void SelectDisplay(DisplayBounds display)
    {
        if (display.Width < 1 || display.Height < 1 || string.IsNullOrWhiteSpace(display.Id)) throw new ArgumentException("Invalid display.");
        lock (_sync) { if (_display != display) { EndSessionLocked("SAFETY_LOCK"); _display = display; Changed?.Invoke(); } }
    }
    public void Disconnect(PeerIdentity peer)
    {
        lock (_sync)
        {
            if (_session?.Peer.Fingerprint == peer.Fingerprint) EndSessionLocked("REMOTE_DISCONNECT");
            if (_offer?.Claimant?.Fingerprint == peer.Fingerprint) CancelPairingLocked("AUTH_FAILURE");
            Changed?.Invoke();
        }
    }
    public void Tick() { lock (_sync) { TickLocked(); } }
    private void TickLocked()
    {
        var now = _clock.MonotonicMs;
        if (_session is null && (_mockInput.HeldButtons > 0 || _mockInput.HeldKeys > 0)) _mockInput.ReleaseAll();
        if (_offer is { } offer && (offer.Claimant is null ? now >= offer.Deadline : now >= offer.ApprovalDeadline)) { CancelPairingLocked("EXPIRED"); Changed?.Invoke(); }
        if (_session is { } session && (now >= session.Deadline || session.Approved &&
            (now - session.LastHeartbeat >= 3000 || now - session.LastAckProgress >= 3000 && _sent.GetValueOrDefault((session.Peer.Fingerprint, session.Id)) - 1 > session.LastAck)))
        { EndSessionLocked("TIMEOUT"); Changed?.Invoke(); }
    }
    public WireMessage[] Drain(PeerIdentity peer)
    {
        lock (_sync)
        {
            var mine = new List<WireMessage>(); var count = _outbox.Count;
            for (var i = 0; i < count; i++) { var item = _outbox.Dequeue(); if (item.Peer == peer.Fingerprint) { mine.Add(item.Message); _delivered[(item.Peer, item.Message.SessionId)] = item.Message.Sequence; } else _outbox.Enqueue(item); }
            return mine.ToArray();
        }
    }
    private void Emit(PeerIdentity peer, string sid, string type, object payload)
    {
        var key = (peer.Fingerprint, sid); var next = _sent.GetValueOrDefault(key);
        _sent[key] = next + 1;
        var message = WireMessage.Create(type, sid, next, _clock.MonotonicMs, payload);
        WireMessage.Parse(message.ToJson(), _schemas);
        if (_outbox.Count >= 256) _outbox.Dequeue();
        _outbox.Enqueue((peer.Fingerprint, message));
    }
    private void Reject(PeerIdentity peer, string sid, string code) => Emit(peer, sid, "protocol.error", new { code, message = "요청이 허용되지 않았습니다.", retryable = false, correlationId = Guid.NewGuid().ToString("N") });
    public void Receive(PeerIdentity peer, string endpoint, string raw)
    {
        lock (_sync)
        {
            WireMessage? message = null;
            ReceiveWindow? candidate = null;
            try
            {
                TickLocked();
                if (!_registry.IsEnrolled(peer)) throw new ProtocolFailure("UNAUTHENTICATED");
                message = WireMessage.Parse(raw, _schemas);
                var input = endpoint is "/input" or "/mock-input";
                if (input && !(message.Type.StartsWith("pointer.") || message.Type.StartsWith("keyboard.") || message.Type == "input.release_all")) throw new ProtocolFailure("FORBIDDEN");
                if (!input && message.Type is not ("pairing.begin" or "pairing.claim" or "pairing.approval" or "pairing.revoked" or "session.request" or "session.heartbeat" or "session.revoke")) throw new ProtocolFailure("FORBIDDEN");
                if (input && (_session is null || _session.Id != message.SessionId || _session.Peer.Fingerprint != peer.Fingerprint)) throw new ProtocolFailure("SESSION_NOT_FOUND");
                candidate = ValidateSequence(peer, message);
                if (input) HandleInput(peer, endpoint, message); else HandleControl(peer, message);
                _received[(peer.Fingerprint, message.SessionId)] = candidate;
                if (endpoint == "/mock-input") Emit(peer, message.SessionId, "session.heartbeat", new { ackSequence = (int)Math.Min(message.Sequence, int.MaxValue) });
            }
            catch (ProtocolFailure failure)
            {
                if (failure.SequenceAccepted && candidate is not null && message is not null) _received[(peer.Fingerprint, message.SessionId)] = candidate;
                if (endpoint is "/input" or "/mock-input" && _session?.Peer.Fingerprint == peer.Fingerprint &&
                    (_mockInput.HeldButtons > 0 || _mockInput.HeldKeys > 0)) EndSessionLocked("SAFETY_LOCK");
                Reject(peer, message?.SessionId ?? "protocol-error", failure.Code);
            }
            Changed?.Invoke();
        }
    }
    private ReceiveWindow ValidateSequence(PeerIdentity peer, WireMessage message)
    {
        var key = (peer.Fingerprint, message.SessionId);
        var now = _clock.MonotonicMs;
        if (_received.TryGetValue(key, out var previous))
        {
            if (message.Sequence <= previous.Sequence) throw new ProtocolFailure("REPLAY");
            var age = (double)now - previous.Offset - message.TimestampMs;
            if (message.TimestampMs < previous.ProducerTime || age > 750 || age < -250) throw new ProtocolFailure("STALE");
            return new(message.Sequence, message.TimestampMs, previous.Offset);
        }
        else
        {
            if (_received.Count >= 256) throw new ProtocolFailure("RATE_LIMITED");
            return new(message.Sequence, message.TimestampMs, now - message.TimestampMs);
        }
    }
    private void HandleControl(PeerIdentity peer, WireMessage message)
    {
        var p = message.Payload;
        switch (message.Type)
        {
            case "pairing.begin":
                _pairingSessions[peer.Fingerprint] = message.SessionId;
                var existing = _registry.Find(peer.Fingerprint)!;
                if (existing.Paired && existing.PairingId is not null && existing.ConnectionId is not null)
                    Emit(peer, message.SessionId, "pairing.completed", new { pairingId = existing.PairingId, connectionId = existing.ConnectionId });
                break;
            case "pairing.claim":
                if (_clock.MonotonicMs < _cooldownUntil) throw new ProtocolFailure("RATE_LIMITED");
                if (_offer is null || p.GetProperty("pairingId").GetString() != _offer.Id) throw new ProtocolFailure("EXPIRED");
                if (_offer.Claimant is not null || _offer.Code is null) throw new ProtocolFailure("FORBIDDEN");
                var submitted = p.GetProperty("code").GetString()!;
                var hash = HMACSHA256.HashData(_offer.Salt, Encoding.ASCII.GetBytes(submitted));
                if (!CryptographicOperations.FixedTimeEquals(hash, _offer.Hash))
                {
                    if (++_offer.Failures >= 5) { _cooldownUntil = _clock.MonotonicMs + 300_000; CancelPairingLocked("AUTH_FAILURE"); throw new ProtocolFailure("RATE_LIMITED", true); }
                    throw new ProtocolFailure("UNAUTHENTICATED", true);
                }
                _offer.Code = null; _offer.Claimant = peer; _offer.ClaimSession = message.SessionId; _offer.ApprovalDeadline = _clock.MonotonicMs + 60_000;
                _pairingSessions[peer.Fingerprint] = message.SessionId;
                _state = "WAITING_APPROVAL";
                break;
            case "pairing.approval":
                if (p.GetProperty("side").GetString() != "VEHICLE") throw new ProtocolFailure("FORBIDDEN");
                if (_offer?.Claimant?.Fingerprint != peer.Fingerprint || _offer.ClaimSession != message.SessionId || p.GetProperty("pairingId").GetString() != _offer.Id) throw new ProtocolFailure("NOT_PAIRED");
                if (!p.GetProperty("approved").GetBoolean()) CancelPairingLocked("USER_REQUEST");
                else { _offer.VehicleApproved = true; CompletePairingLocked(); }
                break;
            case "session.request":
                var device = _registry.Find(peer.Fingerprint)!;
                if (!device.Paired || device.ConnectionId != p.GetProperty("connectionId").GetString()) throw new ProtocolFailure("NOT_PAIRED");
                var capabilities = p.GetProperty("capabilities").EnumerateArray().Select(e => e.GetString()!).ToHashSet();
                if (capabilities.Contains("VIEW_VIDEO")) throw new ProtocolFailure("STREAM_UNAVAILABLE");
                if (_session is not null || _usedSessions.Contains(message.SessionId)) throw new ProtocolFailure("FORBIDDEN");
                if (_usedSessions.Count >= 128) throw new ProtocolFailure("RATE_LIMITED");
                _usedSessions.Add(message.SessionId);
                _session = new(peer, message.SessionId, Guid.NewGuid().ToString("N"), capabilities, _clock.MonotonicMs + 600_000, _clock.UtcMs + 600_000, _clock.MonotonicMs);
                _state = "WAITING_APPROVAL";
                break;
            case "pairing.revoked":
                var revokedDevice = _registry.Find(peer.Fingerprint)!;
                var pairingId = p.GetProperty("pairingId").GetString();
                if (revokedDevice.PairingId != pairingId && !(_offer?.Claimant?.Fingerprint == peer.Fingerprint && _offer.Id == pairingId)) throw new ProtocolFailure("NOT_PAIRED");
                RevokeDevice(peer.Fingerprint);
                break;
            case "session.heartbeat":
                if (_session is null || _session.Peer.Fingerprint != peer.Fingerprint || _session.Id != message.SessionId) throw new ProtocolFailure("SESSION_NOT_FOUND");
                var ack = JsonNumbers.Int64(p.GetProperty("ackSequence"));
                if (ack > _delivered.GetValueOrDefault((peer.Fingerprint, message.SessionId), 0)) throw new ProtocolFailure("INVALID_PAYLOAD");
                if (ack < _session.LastAck) throw new ProtocolFailure("REPLAY");
                if (_session.Approved && ack < _session.GrantSequence) throw new ProtocolFailure("INVALID_PAYLOAD");
                if (ack > _session.LastAck) _session.LastAckProgress = _clock.MonotonicMs;
                _session.LastAck = ack;
                _session.LastHeartbeat = _clock.MonotonicMs;
                Emit(peer, message.SessionId, "session.heartbeat", new { ackSequence = (int)Math.Min(message.Sequence, int.MaxValue) });
                break;
            case "session.revoke":
                if (_session?.Peer.Fingerprint != peer.Fingerprint || _session.Id != message.SessionId) throw new ProtocolFailure("SESSION_NOT_FOUND");
                EndSessionLocked(p.GetProperty("reason").GetString()!);
                break;
        }
    }
    private void HandleInput(PeerIdentity peer, string endpoint, WireMessage message)
    {
        var session = _session!;
        if (!session.Approved || session.GrantId is null || session.LastAck < session.GrantSequence || _clock.MonotonicMs >= session.Deadline || _clock.MonotonicMs - session.LastHeartbeat >= 3000) throw new ProtocolFailure("FORBIDDEN");
        if (message.Type == "input.release_all") { _mockInput.ReleaseAll(); return; }
        var command = InputCommand.Parse(message);
        if (command.GrantId != session.GrantId) throw new ProtocolFailure("FORBIDDEN");
        var capability = message.Type.StartsWith("keyboard.") ? "KEYBOARD" : "POINTER";
        if (!session.Capabilities.Contains(capability)) throw new ProtocolFailure("FORBIDDEN");
        if (endpoint == "/input") throw new ProtocolFailure("UNSAFE_VEHICLE");
        _mockInput.ApplyMock(command, _mockPermit, _display);
    }
    public void SimulateMockRelease() { lock (_sync) { _mockInput.ReleaseAll(); Changed?.Invoke(); } }
}
