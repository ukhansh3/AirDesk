using System.Net;
using System.Net.Security;
using System.Net.WebSockets;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;
using AirDesk.Core;

if (args.Contains("--tls-diagnostic"))
{
    try
    {
        using var http = new HttpClient(new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(5) }) { Timeout = TimeSpan.FromSeconds(10) };
        using var response = await http.SendAsync(new HttpRequestMessage(HttpMethod.Head, "https://api.github.com"));
        Console.WriteLine("TLS_DIAGNOSTIC external-no-client-certificate HTTP " + (int)response.StatusCode);
        return 0;
    }
    catch (Exception error) { Console.WriteLine("TLS_DIAGNOSTIC " + error.GetBaseException().GetType().Name + " " + error.GetBaseException().Message); return 1; }
}
// Schannel on Windows cannot use ephemeral private-key handles. Import synthetic
// test certificates into disposable OS-protected key containers, never a trust store.
Certificates.UseTemporaryWindowsKeys = OperatingSystem.IsWindows() && !args.Contains("--ephemeral-test-keys");
if (args.Contains("--tls-python-probe"))
{
    var option = Array.IndexOf(args, "--tls-python-probe");
    var executable = option + 1 < args.Length ? args[option + 1] : "python";
    using var serverCertificate = Certificates.Server();
    var publicPath = Path.Combine(AppContext.BaseDirectory, "synthetic-public-" + Guid.NewGuid().ToString("N") + ".pem");
    File.WriteAllText(publicPath, serverCertificate.ExportCertificatePem());
    var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
    try
    {
        var serverTask = Task.Run(async () =>
        {
            try
            {
                using var tcp = await listener.AcceptTcpClientAsync(timeout.Token); using var tls = new SslStream(tcp.GetStream());
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = serverCertificate, ClientCertificateRequired = false,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateChainPolicy = new() { DisableCertificateDownloads = true, RevocationMode = X509RevocationMode.NoCheck }
                }, timeout.Token);
                await tls.WriteAsync(new byte[] { 65 }, timeout.Token);
                Console.WriteLine("TLS_PYTHON_PROBE server PASS");
            }
            catch (Exception ex) { Console.WriteLine("TLS_PYTHON_PROBE server " + ex.GetBaseException().GetType().Name + " " + ex.GetBaseException().Message); }
        });
        var start = new System.Diagnostics.ProcessStartInfo(executable) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        start.ArgumentList.Add("-c");
        start.ArgumentList.Add("import socket,ssl,sys; c=ssl.create_default_context(cafile=sys.argv[1]); c.minimum_version=ssl.TLSVersion.TLSv1_2; s=c.wrap_socket(socket.create_connection(('127.0.0.1',int(sys.argv[2])),timeout=8),server_hostname='127.0.0.1'); assert s.recv(1)==b'A'; print('TLS_PYTHON_PROBE client PASS'); s.close()");
        start.ArgumentList.Add(publicPath); start.ArgumentList.Add(((IPEndPoint)listener.LocalEndpoint).Port.ToString());
        using var process = System.Diagnostics.Process.Start(start)!;
        var output = process.StandardOutput.ReadToEndAsync(); var error = process.StandardError.ReadToEndAsync();
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException) { process.Kill(true); throw; }
        Console.Write((await output));
        if (process.ExitCode != 0) Console.WriteLine("TLS_PYTHON_PROBE client FAIL " + (await error).Split('\n', StringSplitOptions.RemoveEmptyEntries).LastOrDefault());
        await serverTask;
        return process.ExitCode;
    }
    finally { listener.Stop(); File.Delete(publicPath); }
}
if (args.Contains("--tls-key-diagnostic"))
{
    using var serverCertificate = Certificates.Server(); using var clientCertificate = Certificates.Client(forTls: true);
    foreach (var (label, certificate) in new[] { ("server", serverCertificate), ("client", clientCertificate) })
    {
        using var rsa = certificate.GetRSAPrivateKey(); using var ec = certificate.GetECDsaPrivateKey();
        var ephemeral = rsa is RSACng r ? r.Key.IsEphemeral : ec is ECDsaCng e ? e.Key.IsEphemeral : (bool?)null;
        Console.WriteLine($"TLS_KEY_DIAGNOSTIC {label} hasPrivateKey={certificate.HasPrivateKey} provider={rsa?.GetType().Name ?? ec?.GetType().Name} isEphemeral={ephemeral}");
    }
    foreach (var requireClient in new[] { false, true })
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
        try
        {
            var serverTask = Task.Run(async () =>
            {
                try
                {
                    using var tcp = await listener.AcceptTcpClientAsync(timeout.Token); using var tls = new SslStream(tcp.GetStream());
                    await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = serverCertificate, ClientCertificateRequired = requireClient,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                        CertificateChainPolicy = new() { DisableCertificateDownloads = true, RevocationMode = X509RevocationMode.NoCheck },
                        RemoteCertificateValidationCallback = (_, cert, _, _) => !requireClient || cert is not null && cert.GetRawCertData().AsSpan().SequenceEqual(clientCertificate.RawData)
                    }, timeout.Token);
                    Console.WriteLine($"TLS_KEY_DIAGNOSTIC requireClient={requireClient} server PASS");
                }
                catch (Exception ex) { Console.WriteLine($"TLS_KEY_DIAGNOSTIC requireClient={requireClient} server {ex.GetBaseException().GetType().Name} {ex.GetBaseException().Message}"); }
            });
            try
            {
                using var tcp = new TcpClient(); await tcp.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port, timeout.Token);
                using var tls = new SslStream(tcp.GetStream());
                await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
                {
                    TargetHost = "127.0.0.1", EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateChainPolicy = new() { DisableCertificateDownloads = true, RevocationMode = X509RevocationMode.NoCheck },
                    LocalCertificateSelectionCallback = requireClient ? (_, _, _, _, _) => clientCertificate : null,
                    RemoteCertificateValidationCallback = (_, cert, _, errors) => cert is not null && (errors & SslPolicyErrors.RemoteCertificateNameMismatch) == 0 && cert.GetRawCertData().AsSpan().SequenceEqual(serverCertificate.RawData)
                }, timeout.Token);
                Console.WriteLine($"TLS_KEY_DIAGNOSTIC requireClient={requireClient} client PASS");
            }
            catch (Exception ex) { Console.WriteLine($"TLS_KEY_DIAGNOSTIC requireClient={requireClient} client {ex.GetBaseException().GetType().Name} {ex.GetBaseException().Message}"); }
            await serverTask;
        }
        finally { listener.Stop(); }
    }
    return 0;
}
var passed = 0; var failed = 0;
var tlsPositive = false;
void Check(bool value, string description = "assertion failed") { if (!value) throw new Exception(description); }
void Case(string name, Action body)
{
    try { body(); passed++; Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failed++; Console.WriteLine("FAIL " + name + " [" + ex.GetType().Name + "] " + ex.GetBaseException().Message); }
}
async Task AsyncCase(string name, Func<Task> body)
{
    try { await body(); passed++; Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failed++; Console.WriteLine("FAIL " + name + " [" + ex.GetType().Name + "] " + ex.GetBaseException().Message); }
}
var schemas = new SchemaCatalog();
Case("canonical schema and duplicate/null/depth rejection", () =>
{
    var valid = WireMessage.Create("pointer.move", "test", 1, 1, new { grantId = new string('g', 32), displayId = "primary", x = .4, y = .7 }).ToJson();
    Check(WireMessage.Parse(valid, schemas).Type == "pointer.move");
    var invalid = new[] { "null", "[]", "{\"type\":null}", valid.Replace("\"type\":\"pointer.move\"", "\"type\":null"), valid.Replace("\"payload\":{", "\"payload\":null,\"discard\":{"), valid.Replace("\"x\":0.4", "\"x\":0.4,\"\\u0078\":0.5"), valid.Replace("\"sessionId\":\"test\"", "\"sessionId\":null"), valid.Replace("\"protocolVersion\":\"0.1\"", "\"protocolVersion\":null"), new string('[', 18) + "0" + new string(']', 18) };
    foreach (var value in invalid) { try { WireMessage.Parse(value, schemas); throw new Exception("invalid accepted"); } catch (ProtocolFailure) { } }
});
Case("shared synthetic wire fixture catalog", () =>
{
    using var catalog = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "phase2-wire-cases.json")));
    var cases = catalog.RootElement.ValueKind == JsonValueKind.Array ? catalog.RootElement : catalog.RootElement.GetProperty("cases");
    foreach (var item in cases.EnumerateArray())
    {
        var expected = item.GetProperty("schemaValid").GetBoolean();
        var actual = true;
        try { WireMessage.Parse(item.GetProperty("wire").GetString()!, schemas); } catch (ProtocolFailure) { actual = false; }
        Check(actual == expected);
    }
});
Case("Android-shaped EC non-CA no-EKU cert enrolls; unknown/expired/revoked reject", () =>
{
    using var cert = Certificates.Client(); using var pub = Certificates.Public(cert);
    var registry = new DeviceRegistry();
    Check(registry.Authenticate(pub, DateTimeOffset.UtcNow) is null);
    registry.Enroll(Certificates.DeviceJson(cert), DeviceRegistry.Fingerprint(cert));
    Check(registry.Authenticate(pub, DateTimeOffset.UtcNow) is not null);
    Check(registry.Authenticate(pub, DateTimeOffset.UtcNow.AddYears(2)) is null);
    registry.Revoke(DeviceRegistry.Fingerprint(cert));
    Check(registry.Authenticate(pub, DateTimeOffset.UtcNow) is null);
});
Case("public enrollment wrong pin/trailing DER/extra secret reject", () =>
{
    using var cert = Certificates.Client(); var registry = new DeviceRegistry();
    foreach (var raw in new[] { Certificates.DeviceJson(cert).Replace("airdesk-device-v1", "unknown"), Certificates.DeviceJson(cert).Replace("\"deviceLabel\"", "\"privateKey\":\"test-only\",\"deviceLabel\""), Certificates.DeviceJson(cert, cert.RawData.Concat(new byte[] { 0 }).ToArray()) })
    { try { registry.Enroll(raw, DeviceRegistry.Fingerprint(cert)); throw new Exception("bad enrollment accepted"); } catch (Exception ex) when (ex is ProtocolFailure or CryptographicException) { } }
    try { registry.Enroll(Certificates.DeviceJson(cert), new string('0', 64)); throw new Exception(); } catch (ProtocolFailure) { }
});
Case("8-digit code local-only, both approvals, consume and pairing recovery", () =>
{
    using var f = new Fixture(schemas); f.Pair();
    Check(f.Host.LocalPairingCode is null);
    f.Send("pairing.begin", "reconnect", 0, new { deviceLabel = "Synthetic client" });
    Check(f.Host.Drain(f.Peer).Single().Type == "pairing.completed");
    Check(f.Host.Snapshot.State == "DISCONNECTED");
});
Case("forged HOST approval cannot pair", () =>
{
    using var f = new Fixture(schemas); var offer = f.Host.BeginPairing(); var code = f.Host.LocalPairingCode!;
    Check(code.Length == 8 && code.All(char.IsAsciiDigit));
    f.Send("pairing.claim", "pair", 0, new { pairingId = offer.PairingId, code, deviceLabel = "Synthetic" });
    f.Send("pairing.approval", "pair", 1, new { pairingId = offer.PairingId, side = "HOST", approved = true });
    Check(f.Error() == "FORBIDDEN"); Check(!f.Registry.Find(f.Peer.Fingerprint)!.Paired);
});
Case("five wrong codes lock offer; cooldown survives replacement", () =>
{
    using var f = new Fixture(schemas); var offer = f.Host.BeginPairing(); var wrong = f.Host.LocalPairingCode == "00000000" ? "11111111" : "00000000";
    for (var i = 0; i < 5; i++) f.Send("pairing.claim", "pair", i, new { pairingId = offer.PairingId, code = wrong, deviceLabel = "Synthetic" });
    Check(f.Error() == "RATE_LIMITED");
    try { f.Host.BeginPairing(); throw new Exception(); } catch (ProtocolFailure ex) { Check(ex.Code == "RATE_LIMITED"); }
    f.Clock.Now += 300_000; f.Host.BeginPairing();
});
Case("expired code and expired approval cannot pair", () =>
{
    using var f = new Fixture(schemas); var offer = f.Host.BeginPairing(); var code = f.Host.LocalPairingCode!;
    f.Clock.Now += 120_000; f.Send("pairing.claim", "pair", 0, new { pairingId = offer.PairingId, code, deviceLabel = "Synthetic" }); Check(f.Error() == "EXPIRED");
    offer = f.Host.BeginPairing(); code = f.Host.LocalPairingCode!;
    f.Send("pairing.claim", "second", 0, new { pairingId = offer.PairingId, code, deviceLabel = "Synthetic" });
    f.Clock.Now += 60_000; f.Host.Tick(); Check(!f.Registry.Find(f.Peer.Fingerprint)!.Paired);
});
Case("late valid claim receives independent sixty-second approval window", () =>
{
    foreach (var delay in new[] { 59_000L, 60_000L })
    {
        using var f = new Fixture(schemas); var offer = f.Host.BeginPairing(); var code = f.Host.LocalPairingCode!;
        f.Clock.Now += 119_000;
        f.Send("pairing.claim", "late", 0, new { pairingId = offer.PairingId, code, deviceLabel = "Synthetic" });
        f.Clock.Now += delay;
        if (delay < 60_000)
        {
            f.Host.ApprovePairing(true);
            f.Send("pairing.approval", "late", 1, new { pairingId = offer.PairingId, side = "VEHICLE", approved = true });
            Check(f.Registry.Find(f.Peer.Fingerprint)!.Paired);
        }
        else
        {
            try { f.Host.ApprovePairing(true); throw new Exception("expired approval accepted"); } catch (ProtocolFailure) { }
            Check(!f.Registry.Find(f.Peer.Fingerprint)!.Paired);
        }
    }
});
Case("unpaired session, reserved video and absent host consent blocked", () =>
{
    using var f = new Fixture(schemas);
    f.Send("session.request", "unpaired", 0, new { connectionId = "unknown", capabilities = new[] { "POINTER" } }); Check(f.Error() == "NOT_PAIRED");
    f.Pair(); f.Send("session.request", "video", 0, new { connectionId = f.Connection, capabilities = new[] { "VIEW_VIDEO" } }); Check(f.Error() == "STREAM_UNAVAILABLE");
    f.Send("session.request", "session", 0, new { connectionId = f.Connection, capabilities = new[] { "POINTER" } });
    f.Input(1, "pointer.down", new { grantId = new string('g', 32), button = "left" }); Check(f.Error() == "FORBIDDEN"); Check(f.Host.Snapshot.MockCalls == 0);
});
Case("mock pointer/key/text/scroll flow and explicit release", () =>
{
    using var f = new Fixture(schemas); f.Session();
    f.Input(2, "pointer.move", new { grantId = f.Grant, displayId = "primary", x = .2, y = .4 });
    f.Input(3, "pointer.down", new { grantId = f.Grant, button = "left" });
    f.Input(4, "keyboard.down", new { grantId = f.Grant, code = "ControlLeft", modifiers = new[] { "Ctrl" } });
    f.Input(5, "keyboard.down", new { grantId = f.Grant, code = "KeyA", modifiers = new[] { "Ctrl" } });
    f.Input(6, "keyboard.text", new { grantId = f.Grant, text = "합성 테스트 only" });
    f.Input(7, "pointer.scroll", new { grantId = f.Grant, deltaX = 1, deltaY = -2, unit = "line" });
    Check(f.Host.Snapshot.HeldButtons == 1 && f.Host.Snapshot.HeldKeys == 2);
    f.Input(8, "input.release_all", new { reason = "USER_REQUEST" });
    Check(f.Host.Snapshot.HeldButtons == 0 && f.Host.Snapshot.HeldKeys == 0 && f.Host.Snapshot.MockCalls == 9);
});
Case("native UNKNOWN and unissued live permit reject without API calls", () =>
{
    using var f = new Fixture(schemas); f.Session(); f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" }, "/input");
    Check(f.Error() == "UNSAFE_VEHICLE"); Check(f.Host.Snapshot.MockCalls == 0 && NativeInputBackend.AttemptedCalls == 0);
    Check(NativeInputBackend.TryCreate(null) is null);
    var state = new InputState(new MockInputBackend());
    try { state.ApplyLive(InputCommand.Parse(f.Message("pointer.down", "session", 2, new { grantId = f.Grant, button = "left" })), null, f.Peer.Fingerprint, "session", new("primary", 0, 0, 100, 100)); throw new Exception(); } catch (ProtocolFailure) { }
    Check(typeof(LiveInputPermit).GetConstructors().Length == 0);
});
Case("wrong grant high sequence does not poison later valid sequence", () =>
{
    using var f = new Fixture(schemas); f.Session();
    f.Input(999, "pointer.down", new { grantId = new string('x', 32), button = "left" }); Check(f.Error() == "FORBIDDEN");
    f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" }); Check(f.Host.Snapshot.HeldButtons == 1);
});
Case("future and duplicate packets cannot leave held state", () =>
{
    using var f = new Fixture(schemas); f.Session();
    f.Host.Receive(f.Peer, "/mock-input", WireMessage.Create("pointer.down", "session", 999, f.Clock.Now + 10000, new { grantId = f.Grant, button = "left" }).ToJson()); Check(f.Error() == "STALE");
    f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" });
    f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" }); Check(f.Error() == "REPLAY"); Check(f.Host.Snapshot.HeldButtons == 0);
});
Case("out-of-order cross-channel release revokes and releases", () =>
{
    using var f = new Fixture(schemas); f.Session();
    f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" });
    f.Send("session.heartbeat", "session", 4, new { ackSequence = 2 }); f.Host.Drain(f.Peer);
    f.Input(3, "pointer.up", new { grantId = f.Grant, button = "left" });
    Check(f.Error() == "REPLAY"); Check(f.Host.Snapshot.HeldButtons == 0 && f.Host.Snapshot.State == "LOCKED");
});
Case("heartbeat future ack cannot renew; watchdog releases", () =>
{
    using var f = new Fixture(schemas); f.Session(); f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" });
    f.Clock.Now += 2000; f.Send("session.heartbeat", "session", 3, new { ackSequence = 999 }); Check(f.Error() == "INVALID_PAYLOAD");
    f.Clock.Now += 1000; f.Host.Tick(); Check(f.Host.Snapshot.HeldButtons == 0 && f.Host.Snapshot.State == "LOCKED");
});
Case("constant heartbeat acknowledgements cannot keep dead receive path alive", () =>
{
    using var f = new Fixture(schemas); f.Session(); f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" });
    f.Send("session.heartbeat", "session", 3, new { ackSequence = 2 }); f.Host.Drain(f.Peer);
    for (var i = 0; i < 2; i++) { f.Clock.Now += 1000; f.Send("session.heartbeat", "session", 4 + i, new { ackSequence = 2 }); f.Host.Drain(f.Peer); }
    f.Clock.Now += 1000; f.Host.Tick(); Check(f.Host.Snapshot.HeldButtons == 0 && f.Host.Snapshot.State == "LOCKED");
});
Case("session maximum TTL, revoke, disconnect and topology all clean held input", () =>
{
    foreach (var action in new Action<Fixture>[] { f => { f.Clock.Now += 600_000; f.Host.Tick(); }, f => f.Host.EndSession("SAFETY_LOCK"), f => f.Host.Disconnect(f.Peer), f => f.Host.SelectDisplay(new("primary", -1920, 0, 1920, 1080)) })
    {
        using var f = new Fixture(schemas); f.Session(); f.Input(2, "keyboard.down", new { grantId = f.Grant, code = "AltLeft" }); action(f);
        Check(f.Host.Snapshot.HeldKeys == 0 && f.Host.Snapshot.ConnectionId is null);
        f.Input(3, "keyboard.down", new { grantId = f.Grant, code = "AltLeft" }); Check(f.Error() == "SESSION_NOT_FOUND");
    }
});
Case("client forget persists device revocation", () =>
{
    using var f = new Fixture(schemas); f.Pair(); var pairing = f.Registry.Find(f.Peer.Fingerprint)!.PairingId;
    f.Send("pairing.revoked", "forget", 0, new { pairingId = pairing, reason = "USER_REQUEST" });
    Check(f.Registry.Find(f.Peer.Fingerprint)!.Revoked && !f.Registry.IsEnrolled(f.Peer));
});
Case("revocation persistence failure still releases and revokes in memory", () =>
{
    var directory = Path.Combine(AppContext.BaseDirectory, "synthetic-revocation-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(directory);
    try
    {
        var path = Path.Combine(directory, "public-devices.json");
        using var f = new Fixture(schemas, metadataPath: path); f.Session();
        f.Input(2, "pointer.down", new { grantId = f.Grant, button = "left" });
        Directory.CreateDirectory(path + ".tmp"); // Synthetic write failure; no user files involved.
        try { f.Host.RevokeDevice(f.Peer.Fingerprint); throw new Exception("expected persistence failure"); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        Check(f.Host.Snapshot.HeldButtons == 0 && f.Host.Snapshot.ConnectionId is null);
        Check(!f.Registry.IsEnrolled(f.Peer));
    }
    finally { Directory.Delete(directory, true); }
});
Case("faulting backend release retains ownership and attempts other releases", () =>
{
    var backend = new FaultBackend(); var state = new InputState(backend);
    var permit = (MockInputPermit)Activator.CreateInstance(typeof(MockInputPermit), true)!;
    InputCommand Command(string type, object payload) => InputCommand.Parse(WireMessage.Create(type, "mock", 0, 0, payload));
    state.ApplyMock(Command("pointer.down", new { grantId = new string('g', 32), button = "left" }), permit, new("primary", 0, 0, 10, 10));
    state.ApplyMock(Command("keyboard.down", new { grantId = new string('g', 32), code = "Enter" }), permit, new("primary", 0, 0, 10, 10));
    Check(!state.ReleaseAll()); Check(state.HeldButtons == 1 && state.HeldKeys == 0);
    backend.FailUp = false; Check(state.ReleaseAll()); Check(state.HeldButtons == 0);
});
Case("DPI physical bounds and negative virtual origin map correctly", () =>
{
    var display = new DisplayBounds("selected", -1920, -100, 1920, 1080);
    Check(CoordinateMapper.Physical(display, 0, 0) == (-1920, -100));
    Check(CoordinateMapper.Physical(display, 1, 1) == (-1, 979));
    var whole = new DisplayBounds("all", -1920, -100, 3840, 1180);
    Check(CoordinateMapper.Absolute(display, whole, 0, 0) == (0, 0));
    try { CoordinateMapper.Physical(display, double.NaN, .5); throw new Exception(); } catch (ProtocolFailure) { }
});

await AsyncCase("real loopback mTLS positive and duplicate control role rejected", async () =>
{
    using var f = new Fixture(schemas, forTls: true); using var server = Certificates.Server(); await using var service = new SecureHostService(f.Registry, f.Host, server);
    await service.StartAsync(0);
    using var client = Certificates.Socket(f.Client, server); await client.ConnectAsync(new Uri($"wss://127.0.0.1:{service.Port}/control"), CancellationToken.None);
    tlsPositive = true;
    var offer = f.Host.BeginPairing();
    await Certificates.Send(client, f.Message("pairing.claim", "pair", 0, new { pairingId = offer.PairingId, code = f.Host.LocalPairingCode!, deviceLabel = "Synthetic" }));
    for (var i = 0; i < 100 && f.Host.Snapshot.PendingDevice is null; i++) await Task.Delay(10);
    f.Host.ApprovePairing(true);
    Check((await Certificates.Receive(client, schemas)).Type == "pairing.approval");
    await Certificates.Send(client, f.Message("pairing.approval", "pair", 1, new { pairingId = offer.PairingId, side = "VEHICLE", approved = true }));
    var completed = await Certificates.Receive(client, schemas); Check(completed.Type == "pairing.completed");
    var connection = completed.Payload.GetProperty("connectionId").GetString()!;
    await Certificates.Send(client, f.Message("session.request", "remote", 0, new { connectionId = connection, capabilities = new[] { "POINTER", "KEYBOARD" } }));
    for (var i = 0; i < 100 && f.Host.Snapshot.PendingSession is null; i++) await Task.Delay(10);
    f.Host.ApproveSession(true);
    Check((await Certificates.Receive(client, schemas)).Type == "session.approve");
    var granted = await Certificates.Receive(client, schemas); var grant = granted.Payload.GetProperty("grantId").GetString()!;
    await Certificates.Send(client, f.Message("session.heartbeat", "remote", 1, new { ackSequence = granted.Sequence }));
    Check((await Certificates.Receive(client, schemas)).Payload.GetProperty("ackSequence").GetInt32() == 1);
    using var mockSocket = Certificates.Socket(f.Client, server);
    await mockSocket.ConnectAsync(new Uri($"wss://127.0.0.1:{service.Port}/mock-input"), CancellationToken.None);
    await Certificates.Send(mockSocket, f.Message("pointer.down", "remote", 2, new { grantId = grant, button = "left" }));
    Check((await Certificates.Receive(client, schemas)).Payload.GetProperty("ackSequence").GetInt32() == 2);
    Check(f.Host.Snapshot.HeldButtons == 1);
    await Certificates.Send(mockSocket, f.Message("input.release_all", "remote", 3, new { reason = "USER_REQUEST" }));
    Check((await Certificates.Receive(client, schemas)).Payload.GetProperty("ackSequence").GetInt32() == 3);
    Check(f.Host.Snapshot.HeldButtons == 0);
    using var duplicate = Certificates.Socket(f.Client, server);
    try { await duplicate.ConnectAsync(new Uri($"wss://127.0.0.1:{service.Port}/control"), CancellationToken.None); throw new Exception("duplicate role accepted"); } catch (WebSocketException) { }
    f.Host.RevokeDevice(f.Peer.Fingerprint);
    Check((await Certificates.Receive(client, schemas)).Type == "pairing.revoked");
    Check((await Certificates.Receive(client, schemas)).Type == "session.ended");
    using var closingTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
    var close = await client.ReceiveAsync(new byte[8192].AsMemory(), closingTimeout.Token);
    Check(close.MessageType == WebSocketMessageType.Close);
    mockSocket.Abort();
    client.Abort();
});
await AsyncCase("real mTLS absent unknown revoked client and wrong server pin reject", async () =>
{
    if (!tlsPositive) throw new InvalidOperationException("NOT_RUN: positive TLS precondition failed; negative authentication is not proven.");
    using var f = new Fixture(schemas, forTls: true); using var server = Certificates.Server(); using var unknown = Certificates.Client(forTls: true); await using var service = new SecureHostService(f.Registry, f.Host, server); await service.StartAsync(0);
    foreach (var mode in new[] { "absent", "unknown", "wrong-pin", "revoked" })
    {
        if (mode == "revoked") f.Registry.Revoke(f.Peer.Fingerprint);
        using var client = Certificates.Socket(mode == "absent" ? null : mode == "unknown" ? unknown : f.Client, server, mode == "wrong-pin");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try { await client.ConnectAsync(new Uri($"wss://127.0.0.1:{service.Port}/control"), timeout.Token); throw new Exception("untrusted TLS connection accepted"); } catch (Exception ex) when (ex is WebSocketException or AuthenticationException or HttpRequestException) { }
    }
});
Check(NativeInputBackend.AttemptedCalls == 0, "Native calls must remain zero.");
Console.WriteLine("AIRDESK_HOST_TESTS " + JsonSerializer.Serialize(new { passed, failed, nativeCalls = NativeInputBackend.AttemptedCalls }));
return failed == 0 ? 0 : 1;

sealed class FakeClock : IHostClock { public long Now = 1000; public long MonotonicMs => Now; public long UtcMs => 1800000000000 + Now; }
sealed class Fixture : IDisposable
{
    public readonly FakeClock Clock = new(); public readonly DeviceRegistry Registry; public readonly X509Certificate2 Client;
    public readonly PeerIdentity Peer; public readonly HostCoordinator Host;
    public string Connection = "", Grant = "";
    public Fixture(SchemaCatalog schemas, bool forTls = false, string? metadataPath = null)
    {
        Registry = new(metadataPath);
        Client = Certificates.Client(forTls);
        Registry.Enroll(Certificates.DeviceJson(Client), DeviceRegistry.Fingerprint(Client)); using var pub = Certificates.Public(Client);
        Peer = Registry.Authenticate(pub, DateTimeOffset.UtcNow)!; if (Peer is null) throw new Exception("synthetic cert authentication failed");
        Host = new(Registry, schemas, Clock);
    }
    public WireMessage Message(string type, string sid, long sequence, object payload) => WireMessage.Create(type, sid, sequence, Clock.Now, payload);
    public void Send(string type, string sid, long sequence, object payload) => Host.Receive(Peer, "/control", Message(type, sid, sequence, payload).ToJson());
    public void Input(long sequence, string type, object payload, string endpoint = "/mock-input") => Host.Receive(Peer, endpoint, Message(type, "session", sequence, payload).ToJson());
    public string Error() => Host.Drain(Peer).Last(m => m.Type == "protocol.error").Payload.GetProperty("code").GetString()!;
    public void Pair()
    {
        var offer = Host.BeginPairing(); var code = Host.LocalPairingCode!;
        Send("pairing.claim", "pair", 0, new { pairingId = offer.PairingId, code, deviceLabel = "Synthetic" });
        Host.ApprovePairing(true); Send("pairing.approval", "pair", 1, new { pairingId = offer.PairingId, side = "VEHICLE", approved = true });
        Host.Drain(Peer); Connection = Registry.Find(Peer.Fingerprint)!.ConnectionId!;
        if (Connection is null) throw new Exception("pair failed");
    }
    public void Session()
    {
        Pair(); Send("session.request", "session", 0, new { connectionId = Connection, capabilities = new[] { "POINTER", "KEYBOARD" } }); Host.ApproveSession(true);
        Grant = Host.Drain(Peer).Single(m => m.Type == "session.granted").Payload.GetProperty("grantId").GetString()!;
        Send("session.heartbeat", "session", 1, new { ackSequence = 1 }); Host.Drain(Peer);
    }
    public void Dispose() { Host.EndSession(); Client.Dispose(); }
}
static class Certificates
{
    public static bool UseTemporaryWindowsKeys;
    private static X509Certificate2 Prepare(X509Certificate2 certificate)
    {
        if (!OperatingSystem.IsWindows() || !UseTemporaryWindowsKeys) return certificate;
        var password = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var encrypted = certificate.Export(X509ContentType.Pkcs12, password);
        try { return X509CertificateLoader.LoadPkcs12(encrypted, password, X509KeyStorageFlags.UserKeySet); }
        finally { CryptographicOperations.ZeroMemory(encrypted); certificate.Dispose(); }
    }
    public static X509Certificate2 Client(bool forTls = false)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=Synthetic Android EC client", key, HashAlgorithmName.SHA256);
        var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1));
        return forTls ? Prepare(certificate) : certificate;
    }
    public static X509Certificate2 Server()
    {
        using var key = RSA.Create(2048); var request = new CertificateRequest("CN=Synthetic loopback host", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        var san = new SubjectAlternativeNameBuilder(); san.AddIpAddress(IPAddress.Loopback); san.AddIpAddress(IPAddress.Parse("10.0.2.2")); san.AddDnsName("localhost"); request.CertificateExtensions.Add(san.Build());
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));
        return Prepare(request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1)));
    }
    public static X509Certificate2 Public(X509Certificate2 cert) => X509CertificateLoader.LoadCertificate(cert.RawData);
    public static string DeviceJson(X509Certificate2 certificate, byte[]? der = null) => JsonSerializer.Serialize(new { protocol = "airdesk-device-v1", deviceLabel = "Synthetic Android device", clientCertificate = Convert.ToBase64String(der ?? certificate.RawData), clientFingerprint = DeviceRegistry.Fingerprint(certificate) });
    public static ClientWebSocket Socket(X509Certificate2? client, X509Certificate2 server, bool wrongPin = false)
    {
        var socket = new ClientWebSocket(); if (client is not null) socket.Options.ClientCertificates.Add(client);
        socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, errors) =>
        {
            if (wrongPin || certificate is null || (errors & SslPolicyErrors.RemoteCertificateNameMismatch) != 0) return false;
            using var presented = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
            if (DeviceRegistry.Fingerprint(presented) != DeviceRegistry.Fingerprint(server)) return false;
            using var chain = new X509Chain(); chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust; chain.ChainPolicy.CustomTrustStore.Add(presented); chain.ChainPolicy.DisableCertificateDownloads = true; chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
            return chain.Build(presented);
        };
        return socket;
    }
    public static async Task Send(ClientWebSocket socket, WireMessage message) => await socket.SendAsync(Encoding.UTF8.GetBytes(message.ToJson()).AsMemory(), WebSocketMessageType.Text, true, CancellationToken.None);
    public static async Task<WireMessage> Receive(ClientWebSocket socket, SchemaCatalog schemas)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5)); using var buffer = new MemoryStream(); var bytes = new byte[8192]; ValueWebSocketReceiveResult result;
        do { result = await socket.ReceiveAsync(bytes.AsMemory(), timeout.Token); if (result.MessageType != WebSocketMessageType.Text) throw new Exception("no text response"); buffer.Write(bytes, 0, result.Count); } while (!result.EndOfMessage);
        return WireMessage.Parse(Encoding.UTF8.GetString(buffer.ToArray()), schemas);
    }
}
sealed class FaultBackend : IInputBackend
{
    public bool FailUp = true;
    public void Move(int x, int y) { }
    public void Button(string button, bool down) { if (!down && FailUp) throw new IOException("synthetic failure"); }
    public void Key(string code, bool down) { }
    public void Text(string text) { }
    public void Scroll(int horizontal, int vertical) { }
}
