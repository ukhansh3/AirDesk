using System.Windows;

namespace AirDesk.Host;

public partial class MainWindow : Window
{
    private readonly HostSecurityGate _security = new();
    public MainWindow() => InitializeComponent();

    private void OnRecheck(object sender, RoutedEventArgs e)
    {
        StatusText.Text = _security.CanInjectInput
            ? "Unexpected security state: inspect before proceeding"
            : "Status: LOCKED — missing parked/authenticated/PC approval gates";
    }
}
