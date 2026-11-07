using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text;

namespace AirDesk.Core;

public sealed class PeerIdentity
{
    internal PeerIdentity(string fingerprint) => Fingerprint = fingerprint;
    public string Fingerprint { get; }
}

public sealed record DeviceRecord(string Fingerprint, string Certificate, string Label, bool Paired, bool Revoked, string? PairingId, string? ConnectionId);

public sealed class DeviceRegistry
{
    private readonly object _sync = new();
    private readonly Dictionary<string, DeviceRecord> _devices = new(StringComparer.Ordinal);
    private readonly string? _path;
    public DeviceRegistry(string? publicMetadataPath = null)
    {
        _path = publicMetadataPath;
        if (_path is not null && File.Exists(_path))
        {
            try
            {
                foreach (var item in JsonSerializer.Deserialize<DeviceRecord[]>(File.ReadAllText(_path)) ?? [])
                {
                    using var cert = X509CertificateLoader.LoadCertificate(Convert.FromBase64String(item.Certificate));
                    if (Fingerprint(cert) == item.Fingerprint) _devices[item.Fingerprint] = item;
                }
            }
            catch (Exception ex) when (ex is IOException or JsonException or CryptographicException or FormatException)
            { throw new InvalidOperationException("Device metadata is invalid; refusing to trust devices."); }
        }
    }
    public static string Fingerprint(X509Certificate2 certificate) => Convert.ToHexString(SHA256.HashData(certificate.RawData));
    public static string NormalizeFingerprint(string value) => string.Concat(value.Where(c => !char.IsWhiteSpace(c) && c != ':')).ToUpperInvariant();
    public DeviceRecord[] Devices { get { lock (_sync) return _devices.Values.ToArray(); } }
    public DeviceRecord? Find(string fingerprint) { lock (_sync) return _devices.GetValueOrDefault(fingerprint); }

    public DeviceRecord Enroll(string publicDeviceJson, string confirmedFingerprint)
    {
        if (Encoding.UTF8.GetByteCount(publicDeviceJson) > 32768) throw new ProtocolFailure("INVALID_PAYLOAD");
        using var document = JsonDocument.Parse(publicDeviceJson, new JsonDocumentOptions { MaxDepth = 16 });
        var json = document.RootElement;
        var names = json.EnumerateObject().Select(p => p.Name).ToArray();
        if (names.Length != 4 || !names.ToHashSet().SetEquals(["protocol", "deviceLabel", "clientCertificate", "clientFingerprint"]) || json.EnumerateObject().Any(p => p.Value.ValueKind != JsonValueKind.String) || json.GetProperty("protocol").GetString() != "airdesk-device-v1")
            throw new ProtocolFailure("INVALID_PAYLOAD");
        var label = json.GetProperty("deviceLabel").GetString()!;
        if (label.Length is < 1 or > 80 || label.Any(char.IsControl)) throw new ProtocolFailure("INVALID_PAYLOAD");
        var der = Convert.FromBase64String(json.GetProperty("clientCertificate").GetString()!);
        using var certificate = X509CertificateLoader.LoadCertificate(der);
        if (!certificate.RawData.AsSpan().SequenceEqual(der)) throw new ProtocolFailure("INVALID_PAYLOAD");
        var fingerprint = Fingerprint(certificate);
        if (NormalizeFingerprint(confirmedFingerprint) != fingerprint || NormalizeFingerprint(json.GetProperty("clientFingerprint").GetString()!) != fingerprint || !ValidCertificate(certificate, DateTimeOffset.UtcNow))
            throw new ProtocolFailure("UNAUTHENTICATED");
        lock (_sync)
        {
            if (_devices.TryGetValue(fingerprint, out var previous) && previous.Revoked) throw new ProtocolFailure("FORBIDDEN");
            var record = previous is not null ? previous with { Label = label } : new DeviceRecord(fingerprint, Convert.ToBase64String(certificate.RawData), label, false, false, null, null);
            _devices[fingerprint] = record;
            Save();
            return record;
        }
    }
    public PeerIdentity? Authenticate(X509Certificate2? certificate, DateTimeOffset now)
    {
        if (certificate is null) return null;
        var fingerprint = Fingerprint(certificate);
        lock (_sync)
        {
            if (!_devices.TryGetValue(fingerprint, out var record) || record.Revoked) return null;
            if (!ValidCertificate(certificate, now)) return null;
            return new PeerIdentity(fingerprint);
        }
    }
    public bool IsEnrolled(PeerIdentity peer) { lock (_sync) return _devices.TryGetValue(peer.Fingerprint, out var item) && !item.Revoked; }
    public DeviceRecord MarkPaired(PeerIdentity peer, string pairingId, string connectionId)
    {
        lock (_sync)
        {
            if (!_devices.TryGetValue(peer.Fingerprint, out var record) || record.Revoked) throw new ProtocolFailure("UNAUTHENTICATED");
            _devices[peer.Fingerprint] = record = record with { Paired = true, PairingId = pairingId, ConnectionId = connectionId };
            Save(); return record;
        }
    }
    public void Revoke(string fingerprint)
    {
        lock (_sync)
        {
            if (_devices.TryGetValue(fingerprint, out var item)) { _devices[fingerprint] = item with { Revoked = true, Paired = false }; Save(); }
        }
    }
    private void Save()
    {
        if (_path is null) return;
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
        var temporary = _path + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(_devices.Values.ToArray()));
        File.Move(temporary, _path, true);
    }
    public static bool ValidCertificate(X509Certificate2 certificate, DateTimeOffset now)
    {
        if (certificate.HasPrivateKey || certificate.NotBefore.ToUniversalTime() > now.UtcDateTime || certificate.NotAfter.ToUniversalTime() <= now.UtcDateTime) return false;
        using var chain = new X509Chain();
        chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
        chain.ChainPolicy.CustomTrustStore.Add(certificate);
        chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
        chain.ChainPolicy.DisableCertificateDownloads = true;
        chain.ChainPolicy.VerificationTime = now.UtcDateTime;
        chain.ChainPolicy.ApplicationPolicy.Add(new Oid("1.3.6.1.5.5.7.3.2"));
        return chain.Build(certificate);
    }
}
