using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace AirDesk.Core;

/// <summary>Three isolated roles on a loopback mTLS listener. No diagnostic-token authentication.</summary>
public sealed class SecureHostService(DeviceRegistry registry, HostCoordinator coordinator, X509Certificate2 serverCertificate) : IAsyncDisposable
{
    private WebApplication? _application;
    private Timer? _watchdog;
    private readonly ConcurrentDictionary<string, int> _connections = new();
    private readonly ConcurrentDictionary<(string Peer, string Role), byte> _roles = new();
    public int Port { get; private set; }
    public async Task StartAsync(int port = 8766, CancellationToken cancellationToken = default)
    {
        if (_application is not null) throw new InvalidOperationException("Host already started.");
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = [] });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.Limits.MaxRequestBodySize = 8192;
            options.Listen(IPAddress.Loopback, port, listen => listen.UseHttps(new HttpsConnectionAdapterOptions
            {
                ServerCertificate = serverCertificate,
                ClientCertificateMode = ClientCertificateMode.RequireCertificate,
                SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                CheckCertificateRevocation = false,
                OnAuthenticate = (_, settings) => settings.CertificateChainPolicy = new X509ChainPolicy
                { DisableCertificateDownloads = true, RevocationMode = X509RevocationMode.NoCheck },
                ClientCertificateValidation = (certificate, _, _) => registry.Authenticate(certificate, DateTimeOffset.UtcNow) is not null
            }));
        });
        var app = builder.Build();
        app.UseWebSockets(new Microsoft.AspNetCore.Builder.WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(10) });
        foreach (var endpoint in new[] { "/control", "/input", "/mock-input" })
        {
            var role = endpoint;
            app.Map(role, async context =>
            {
                var certificate = await context.Connection.GetClientCertificateAsync(context.RequestAborted);
                var peer = registry.Authenticate(certificate, DateTimeOffset.UtcNow);
                // RequireCertificate can request rather than guarantee presence. Explicit check is mandatory.
                if (peer is null) { context.Response.StatusCode = 403; return; }
                if (!context.WebSockets.IsWebSocketRequest || context.Request.Headers.ContainsKey("Origin")) { context.Response.StatusCode = 400; return; }
                if (_connections.AddOrUpdate(peer.Fingerprint, 1, (_, count) => count + 1) > 3)
                { _connections.AddOrUpdate(peer.Fingerprint, 0, (_, count) => Math.Max(0, count - 1)); context.Response.StatusCode = 429; return; }
                if (!_roles.TryAdd((peer.Fingerprint, role), 0))
                { _connections.AddOrUpdate(peer.Fingerprint, 0, (_, count) => Math.Max(0, count - 1)); context.Response.StatusCode = 409; return; }
                try
                {
                    using var socket = await context.WebSockets.AcceptWebSocketAsync();
                    await RunSocket(socket, peer, role, context.RequestAborted);
                }
                finally
                {
                    _connections.AddOrUpdate(peer.Fingerprint, 0, (_, count) => Math.Max(0, count - 1));
                    _roles.TryRemove((peer.Fingerprint, role), out _);
                    coordinator.Disconnect(peer);
                }
            });
        }
        try
        {
            await app.StartAsync(cancellationToken);
            Port = new Uri(app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single()).Port;
            _application = app;
            // Independent from network receive. Policy and dispatch share coordinator's serialized lock.
            _watchdog = new Timer(_ => coordinator.Tick(), null, 100, 100);
        }
        catch { await app.DisposeAsync(); throw; }
    }
    private async Task RunSocket(WebSocket socket, PeerIdentity peer, string endpoint, CancellationToken aborted)
    {
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(aborted);
        var token = stop.Token;
        async Task Receive()
        {
            var bytes = new byte[2048];
            var strictUtf8 = new UTF8Encoding(false, true);
            var windowStart = Environment.TickCount64;
            var messages = 0;
            while (socket.State == WebSocketState.Open && !token.IsCancellationRequested)
            {
                using var buffer = new MemoryStream();
                ValueWebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(bytes.AsMemory(), token);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    if (result.MessageType != WebSocketMessageType.Text || buffer.Length + result.Count > 8192)
                    { await socket.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "invalid message", token); return; }
                    buffer.Write(bytes, 0, result.Count);
                } while (!result.EndOfMessage);
                if (Environment.TickCount64 - windowStart >= 1000) { windowStart = Environment.TickCount64; messages = 0; }
                if (++messages > 128) { await socket.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "rate limited", token); return; }
                var text = strictUtf8.GetString(buffer.ToArray());
                if (!registry.IsEnrolled(peer)) { await socket.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "device revoked", token); return; }
                coordinator.Receive(peer, endpoint, text);
            }
        }
        async Task Send()
        {
            // Only the control connection drains the shared sender/session output stream.
            while (!token.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                if (endpoint == "/control")
                    foreach (var message in coordinator.Drain(peer))
                        await socket.SendAsync(Encoding.UTF8.GetBytes(message.ToJson()).AsMemory(), WebSocketMessageType.Text, true, token);
                if (!registry.IsEnrolled(peer)) return;
                await Task.Delay(20, token);
            }
        }
        var receive = Receive(); var send = Send();
        try { await await Task.WhenAny(receive, send); }
        catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or DecoderFallbackException) { }
        finally
        {
            stop.Cancel();
            try { await Task.WhenAll(receive, send); } catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or DecoderFallbackException) { }
            if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                try { await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "session ended", CancellationToken.None); } catch (WebSocketException) { }
        }
    }
    public async ValueTask DisposeAsync()
    {
        _watchdog?.Dispose();
        coordinator.EndSession("REMOTE_DISCONNECT");
        if (_application is { } app) { _application = null; await app.StopAsync(); await app.DisposeAsync(); }
    }
}
