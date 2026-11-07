using System.Runtime.InteropServices;
using AirDesk.Core;

namespace AirDesk.Host;

internal sealed record DisplayChoice(string Label, DisplayBounds Bounds);
internal static class DisplayCatalog
{
    public static DisplayChoice[] Read()
    {
        var displays = new List<DisplayChoice>();
        EnumDisplayMonitors(0, 0, (nint monitor, nint _, ref RECT rectangle, nint data) =>
        {
            var info = new MONITORINFOEX { Size = Marshal.SizeOf<MONITORINFOEX>(), Device = "" };
            if (GetMonitorInfo(monitor, ref info))
                displays.Add(new($"{info.Device} · {info.Monitor.Right - info.Monitor.Left}×{info.Monitor.Bottom - info.Monitor.Top}",
                    new("primary", info.Monitor.Left, info.Monitor.Top, info.Monitor.Right - info.Monitor.Left, info.Monitor.Bottom - info.Monitor.Top)));
            return true;
        }, 0);
        return displays.ToArray();
    }
    private delegate bool MonitorCallback(nint monitor, nint hdc, ref RECT rectangle, nint data);
    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct MONITORINFOEX
    { public int Size; public RECT Monitor, Work; public uint Flags; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Device; }
    [DllImport("user32.dll")] private static extern bool EnumDisplayMonitors(nint hdc, nint clip, MonitorCallback callback, nint data);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool GetMonitorInfo(nint monitor, ref MONITORINFOEX info);
}
