using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace AirDesk.Host;

/// <summary>Called only by the user's Start Host action. No plaintext private-key export.</summary>
internal static class WindowsIdentity
{
    private const string FriendlyName = "AirDesk Phase2 Host v1";
    public static X509Certificate2 OpenOrCreate()
    {
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite);
        foreach (var certificate in store.Certificates)
            if (certificate.FriendlyName == FriendlyName && certificate.HasPrivateKey &&
                certificate.NotAfter.ToUniversalTime() > DateTime.UtcNow.AddDays(1) && certificate.NotBefore.ToUniversalTime() <= DateTime.UtcNow)
            {
                using var privateKey = certificate.GetRSAPrivateKey();
                if (privateKey is RSACng rsa && rsa.Key.ExportPolicy == CngExportPolicies.None) return certificate;
            }
        var creation = new CngKeyCreationParameters
        {
            Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
            KeyUsage = CngKeyUsages.Signing,
            ExportPolicy = CngExportPolicies.None
        };
        creation.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(3072), CngPropertyOptions.None));
        using var key = CngKey.Create(CngAlgorithm.Rsa, "AirDesk.Phase2.Host." + Guid.NewGuid().ToString("N"), creation);
        using var rsaKey = new RSACng(key);
        var request = new CertificateRequest("CN=AirDesk Host", rsaKey, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, true));
        var san = new SubjectAlternativeNameBuilder();
        san.AddIpAddress(IPAddress.Loopback); san.AddIpAddress(IPAddress.Parse("10.0.2.2")); san.AddDnsName("localhost");
        request.CertificateExtensions.Add(san.Build());
        var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(1));
        created.FriendlyName = FriendlyName;
        store.Add(created);
        return created;
    }
}
