using System.ComponentModel;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using AirDesk.Core;
using Microsoft.Win32;

namespace AirDesk.Host;

public partial class MainWindow : Window
{
    private readonly DeviceRegistry _registry;
    private readonly HostCoordinator _coordinator;
    private readonly DispatcherTimer _uiTimer;
    private SecureHostService? _service;
    private X509Certificate2? _identity;
    private bool _closing, _stopping, _shutdownPending;
    private Task? _starting;
    public MainWindow()
    {
        InitializeComponent();
        var metadata = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AirDesk", "Phase2", "devices.json");
        _registry = new DeviceRegistry(metadata);
        _coordinator = new(_registry, new SchemaCatalog());
        _uiTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        _uiTimer.Tick += (_, _) => Refresh(); _uiTimer.Start();
        ReloadDisplays();
        SystemEvents.DisplaySettingsChanged += OnTopologyChanged;
        Refresh();
    }
    private async void OnStart(object sender, RoutedEventArgs e)
    {
        if (_service is not null || _stopping || _starting is not null || _shutdownPending) return;
        _starting = StartHost();
        try { await _starting; }
        finally { _starting = null; }
    }
    private async Task StartHost()
    {
        StartButton.IsEnabled = false;
        try
        {
            _identity = WindowsIdentity.OpenOrCreate();
            HostFingerprint.Text = DeviceRegistry.Fingerprint(_identity);
            var service = new SecureHostService(_registry, _coordinator, _identity);
            await service.StartAsync(); _service = service; Refresh();
        }
        catch
        {
            _identity?.Dispose(); _identity = null; HostFingerprint.Clear();
            ShowError("보안 Host를 시작하지 못했습니다. 포트 8766 사용 여부와 Windows 인증서 저장소 접근을 확인하세요.");
        }
        finally { StartButton.IsEnabled = _service is null; }
    }
    private async void OnStop(object sender, RoutedEventArgs e)
    {
        if (_starting is { } starting) await starting;
        await StopHost();
    }
    private async Task StopHost()
    {
        if (_stopping) return;
        _stopping = true;
        try
        {
            _coordinator.CancelPairing(); _coordinator.EndSession();
            if (_service is { } service) { _service = null; await service.DisposeAsync(); }
        }
        catch { ShowError("Host 종료 중 연결 정리를 완료하지 못했습니다. 세션은 회수되었으며 새 승인이 필요합니다."); }
        finally { _identity?.Dispose(); _identity = null; _stopping = false; StartButton.IsEnabled = true; Refresh(); }
    }
    private void OnEnroll(object sender, RoutedEventArgs e) => TryAction(() =>
    {
        _registry.Enroll(DeviceImport.Text, ConfirmedFingerprint.Text);
        DeviceImport.Clear(); ConfirmedFingerprint.Clear(); Refresh();
    });
    private void OnBeginPairing(object sender, RoutedEventArgs e) => TryAction(() =>
    {
        if (_service is null) { ShowError("먼저 보안 Host를 시작하세요."); return; }
        _coordinator.BeginPairing(); Refresh();
    });
    private void OnCancelPairing(object sender, RoutedEventArgs e) => _coordinator.CancelPairing();
    private void OnAllowPairing(object sender, RoutedEventArgs e) => TryAction(() => _coordinator.ApprovePairing(true));
    private void OnDenyPairing(object sender, RoutedEventArgs e) => TryAction(() => _coordinator.ApprovePairing(false));
    private void OnApproveSession(object sender, RoutedEventArgs e) => TryAction(() => _coordinator.ApproveSession(true));
    private void OnDenySession(object sender, RoutedEventArgs e) => TryAction(() => _coordinator.ApproveSession(false));
    private void OnEndSession(object sender, RoutedEventArgs e) => _coordinator.EndSession();
    private void OnRevokeDevice(object sender, RoutedEventArgs e)
    {
        if (DeviceList.SelectedItem is DeviceRecord selected) TryAction(() => _coordinator.RevokeDevice(selected.Fingerprint));
    }
    private void OnCopyBootstrap(object sender, RoutedEventArgs e) => TryAction(() =>
    {
        var offer = _coordinator.CurrentOffer;
        if (_identity is null || _service is null || offer is null) { ShowError("Host를 시작하고 새 페어링 코드를 만든 뒤 복사하세요."); return; }
        var publicInfo = JsonSerializer.Serialize(new
        {
            protocol = "airdesk-bootstrap-v1", host = "10.0.2.2", port = 8766,
            serverCertificate = Convert.ToBase64String(_identity.RawData), serverFingerprint = DeviceRegistry.Fingerprint(_identity),
            pairingId = offer.PairingId, expiresAtMs = offer.ExpiresAtMs
        });
        Clipboard.SetText(publicInfo);
    });
    private void OnDisplayChanged(object sender, SelectionChangedEventArgs e)
    {
        if (DisplayList.SelectedItem is DisplayChoice choice && _coordinator is not null) _coordinator.SelectDisplay(choice.Bounds);
    }
    private void ReloadDisplays()
    {
        DisplayList.ItemsSource = DisplayCatalog.Read();
        if (DisplayList.Items.Count > 0) DisplayList.SelectedIndex = 0;
    }
    private void OnTopologyChanged(object? sender, EventArgs e)
    {
        _coordinator.EndSession("SAFETY_LOCK");
        Dispatcher.InvokeAsync(ReloadDisplays);
    }
    private void Refresh()
    {
        if (_closing) return;
        var state = _coordinator.Snapshot;
        StatusText.Text = $"{state.State} · {(_service is null ? "Host 중지됨" : "mTLS loopback 8766")} · 안전 UNKNOWN";
        PairingCode.Text = _coordinator.LocalPairingCode ?? "코드 없음 / 이미 사용됨";
        PairingStatus.Text = state.PendingDevice is null ? "PC 화면 전용 · 코드 120초 / 승인 대기 60초" : $"확인할 요청 장치: {state.PendingDevice}\nSHA-256: {state.PendingFingerprint}";
        var sessionDevice = _registry.Devices.FirstOrDefault(d => d.Fingerprint == state.SessionFingerprint);
        SessionStatus.Text = state.PendingSession is not null
            ? $"세션 요청 장치: {sessionDevice?.Label}\nSHA-256: {state.SessionFingerprint}\n요청 권한: {string.Join(", ", state.RequestedCapabilities)} · 실제 입력은 잠금 유지"
            : state.ConnectionId is null ? "활성 세션 없음" : "세션 연결됨 · 모의 입력만 가능 · 최대 10분";
        InputStatus.Text = $"모의 이벤트 {state.MockCalls} · 눌린 버튼 {state.HeldButtons} · 눌린 키 {state.HeldKeys} · Native 호출 {state.NativeCalls}";
        var selected = (DeviceList.SelectedItem as DeviceRecord)?.Fingerprint;
        DeviceList.ItemsSource = _registry.Devices;
        if (selected is not null) DeviceList.SelectedItem = _registry.Devices.FirstOrDefault(d => d.Fingerprint == selected);
    }
    private void TryAction(Action action)
    {
        try { action(); }
        catch (ProtocolFailure failure) { ShowError("처리되지 않았습니다: " + failure.Code); }
        catch { ShowError("공개 정보 또는 장치 설정을 확인하세요. 비밀키/코드/입력 내용을 표시하거나 기록하지 않았습니다."); }
    }
    private static void ShowError(string message) => MessageBox.Show(message, "AirDesk Host", MessageBoxButton.OK, MessageBoxImage.Warning);
    private async void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_closing) return;
        e.Cancel = true;
        if (_shutdownPending) return;
        _shutdownPending = true;
        _uiTimer.Stop(); SystemEvents.DisplaySettingsChanged -= OnTopologyChanged;
        if (_starting is { } starting) await starting;
        await StopHost(); _closing = true; Close();
    }
}
