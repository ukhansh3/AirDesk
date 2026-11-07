using System.Runtime.InteropServices;
using System.Text.Json;

namespace AirDesk.Core;

public readonly record struct DisplayBounds(string Id, int Left, int Top, int Width, int Height);
public static class CoordinateMapper
{
    public static (int X, int Y) Physical(DisplayBounds display, double x, double y)
    {
        if (!double.IsFinite(x) || !double.IsFinite(y) || x < 0 || x > 1 || y < 0 || y > 1 || display.Width < 1 || display.Height < 1)
            throw new ProtocolFailure("INVALID_PAYLOAD");
        return (display.Left + (int)Math.Round(x * (display.Width - 1)), display.Top + (int)Math.Round(y * (display.Height - 1)));
    }
    public static (int X, int Y) Absolute(DisplayBounds display, DisplayBounds virtualDesktop, double x, double y)
    {
        var point = Physical(display, x, y);
        if (virtualDesktop.Width < 2 || virtualDesktop.Height < 2) throw new ProtocolFailure("INVALID_PAYLOAD");
        return ((int)Math.Round((point.X - virtualDesktop.Left) * 65535d / (virtualDesktop.Width - 1)),
            (int)Math.Round((point.Y - virtualDesktop.Top) * 65535d / (virtualDesktop.Height - 1)));
    }
}

public sealed class InputCommand
{
    public string Type { get; }
    public string? GrantId { get; }
    public string? DisplayId { get; }
    public string? Button { get; }
    public string? Code { get; }
    public string? Text { get; }
    public double X { get; }
    public double Y { get; }
    public int DeltaX { get; }
    public int DeltaY { get; }
    public HashSet<string>? Modifiers { get; }
    private InputCommand(WireMessage message)
    {
        Type = message.Type;
        var p = message.Payload;
        string? String(string name) => p.TryGetProperty(name, out var v) ? v.GetString() : null;
        GrantId = String("grantId"); DisplayId = String("displayId"); Button = String("button"); Code = String("code"); Text = String("text");
        X = p.TryGetProperty("x", out var x) ? x.GetDouble() : 0;
        Y = p.TryGetProperty("y", out var y) ? y.GetDouble() : 0;
        DeltaX = p.TryGetProperty("deltaX", out var dx) ? JsonNumbers.Int32(dx) : 0;
        DeltaY = p.TryGetProperty("deltaY", out var dy) ? JsonNumbers.Int32(dy) : 0;
        Modifiers = p.TryGetProperty("modifiers", out var mods) ? mods.EnumerateArray().Select(e => e.GetString()!).ToHashSet() : null;
    }
    public static InputCommand Parse(WireMessage message)
    {
        var result = new InputCommand(message);
        if (result.Code is not null && !KeyCodes.Supported(result.Code)) throw new ProtocolFailure("INVALID_PAYLOAD");
        if (result.Text is not null && (!SchemaCatalog.ValidUnicode(result.Text) || result.Text.EnumerateRunes().Count() is < 1 or > 1024)) throw new ProtocolFailure("INVALID_PAYLOAD");
        return result;
    }
    public override string ToString() => $"{Type} (input redacted)";
}

public static class KeyCodes
{
    private static readonly Dictionary<string, ushort> Keys = new()
    {
        ["Enter"] = 0x0D, ["Backspace"] = 0x08, ["Delete"] = 0x2E,
        ["ArrowLeft"] = 0x25, ["ArrowUp"] = 0x26, ["ArrowRight"] = 0x27, ["ArrowDown"] = 0x28,
        ["ShiftLeft"] = 0xA0, ["ShiftRight"] = 0xA1, ["ControlLeft"] = 0xA2, ["ControlRight"] = 0xA3,
        ["AltLeft"] = 0xA4, ["AltRight"] = 0xA5, ["Space"] = 0x20, ["Tab"] = 0x09, ["Escape"] = 0x1B,
        ["Home"] = 0x24, ["End"] = 0x23, ["PageUp"] = 0x21, ["PageDown"] = 0x22
    };
    public static bool Supported(string code) => Keys.ContainsKey(code) || code.Length == 4 && code.StartsWith("Key") && code[3] is >= 'A' and <= 'Z' || code.Length == 6 && code.StartsWith("Digit") && code[5] is >= '0' and <= '9';
    public static ushort VirtualKey(string code) => Keys.TryGetValue(code, out var key) ? key : code.StartsWith("Key") && Supported(code) ? code[3] : code.StartsWith("Digit") && Supported(code) ? code[5] : throw new ProtocolFailure("INVALID_PAYLOAD");
    public static string? Modifier(string code) => code.StartsWith("Shift") ? "Shift" : code.StartsWith("Control") ? "Ctrl" : code.StartsWith("Alt") ? "Alt" : null;
}

public interface IInputBackend
{
    void Move(int x, int y);
    void Button(string button, bool down);
    void Key(string code, bool down);
    void Text(string text);
    void Scroll(int horizontal, int vertical);
}

/// <summary>No input contents are retained, only counts for diagnostic UI.</summary>
public sealed class MockInputBackend : IInputBackend
{
    public int Calls { get; private set; }
    public void Move(int x, int y) => Calls++;
    public void Button(string button, bool down) => Calls++;
    public void Key(string code, bool down) => Calls++;
    public void Text(string text) => Calls++;
    public void Scroll(int horizontal, int vertical) => Calls++;
}

public sealed class MockInputPermit
{
    internal MockInputPermit() { }
}

/// <summary>No issuer exists until an independently validated vehicle verifier is implemented.</summary>
public sealed class LiveInputPermit
{
    private readonly string _peer, _session, _grant;
    private readonly long _deadline;
    private readonly HashSet<string> _capabilities;
    private LiveInputPermit(string peer, string session, string grant, long deadline, HashSet<string> capabilities)
    { _peer = peer; _session = session; _grant = grant; _deadline = deadline; _capabilities = capabilities; }
    internal bool Current => Environment.TickCount64 < _deadline;
    internal bool Allows(string peer, string session, InputCommand command) => Current && _peer == peer && _session == session &&
        _grant == command.GrantId && _capabilities.Contains(command.Type.StartsWith("keyboard.") ? "KEYBOARD" : "POINTER");
}

public sealed class InputState(IInputBackend backend)
{
    private readonly HashSet<string> _buttons = [];
    private readonly HashSet<string> _keys = [];
    public int HeldButtons => _buttons.Count;
    public int HeldKeys => _keys.Count;
    public void ApplyMock(InputCommand command, MockInputPermit permit, DisplayBounds display)
    {
        ArgumentNullException.ThrowIfNull(permit);
        if (backend is NativeInputBackend) throw new InvalidOperationException("Mock authority cannot reach native input.");
        Apply(command, display);
    }
    public void ApplyLive(InputCommand command, LiveInputPermit? permit, string peer, string session, DisplayBounds display)
    {
        if (permit is null || backend is not NativeInputBackend native || !native.OwnsPermit(permit) || !permit.Allows(peer, session, command))
            throw new ProtocolFailure("FORBIDDEN");
        Apply(command, display);
    }
    private void Apply(InputCommand command, DisplayBounds display)
    {
        if (command.Modifiers is not null)
        {
            var intendedKeys = _keys.ToHashSet();
            if (command.Type == "keyboard.down") intendedKeys.Add(command.Code!);
            if (command.Type == "keyboard.up") intendedKeys.Remove(command.Code!);
            var intendedModifiers = intendedKeys.Select(KeyCodes.Modifier).Where(m => m is not null).Select(m => m!).ToHashSet();
            if (!intendedModifiers.SetEquals(command.Modifiers)) throw new ProtocolFailure("INVALID_PAYLOAD");
        }
        switch (command.Type)
        {
            case "pointer.move":
                if (command.DisplayId != display.Id) throw new ProtocolFailure("FORBIDDEN");
                var point = CoordinateMapper.Physical(display, command.X, command.Y); backend.Move(point.X, point.Y); break;
            case "pointer.down": if (_buttons.Add(command.Button!)) backend.Button(command.Button!, true); break;
            case "pointer.up": if (_buttons.Contains(command.Button!)) { backend.Button(command.Button!, false); _buttons.Remove(command.Button!); } break;
            case "keyboard.down": if (_keys.Add(command.Code!)) backend.Key(command.Code!, true); break;
            case "keyboard.up": if (_keys.Contains(command.Code!)) { backend.Key(command.Code!, false); _keys.Remove(command.Code!); } break;
            case "keyboard.text": backend.Text(command.Text!); break;
            case "pointer.scroll": backend.Scroll(command.DeltaX, command.DeltaY); break;
            case "input.release_all": ReleaseAll(); break;
            default: throw new ProtocolFailure("INVALID_PAYLOAD");
        }
    }
    public bool ReleaseAll()
    {
        var released = true;
        foreach (var button in _buttons.ToArray())
            try { backend.Button(button, false); _buttons.Remove(button); } catch { released = false; }
        foreach (var key in _keys.ToArray())
            try { backend.Key(key, false); _keys.Remove(key); } catch { released = false; }
        return released;
    }
}

/// <summary>Compiled native implementation, deliberately not constructible without an unissued live permit.</summary>
public sealed class NativeInputBackend : IInputBackend
{
    private static long _attemptedCalls;
    public static long AttemptedCalls => Interlocked.Read(ref _attemptedCalls);
    private readonly LiveInputPermit _permit;
    private readonly DisplayBounds _desktop;
    private NativeInputBackend(LiveInputPermit permit, DisplayBounds desktop)
    { _permit = permit; _desktop = desktop; if (!permit.Current || desktop.Width < 2 || desktop.Height < 2) throw new ProtocolFailure("FORBIDDEN"); }
    public static NativeInputBackend? TryCreate(LiveInputPermit? permit, DisplayBounds virtualDesktop = default) => permit is null ? null : new(permit, virtualDesktop);
    internal bool OwnsPermit(LiveInputPermit permit) => ReferenceEquals(_permit, permit);
    private void RequireCurrent() { if (!_permit.Current) throw new ProtocolFailure("EXPIRED"); }
    public void Move(int x, int y)
    {
        RequireCurrent();
        if (x < _desktop.Left || y < _desktop.Top || x >= (long)_desktop.Left + _desktop.Width || y >= (long)_desktop.Top + _desktop.Height) throw new ProtocolFailure("INVALID_PAYLOAD");
        var ax = (int)Math.Round((x - _desktop.Left) * 65535d / (_desktop.Width - 1));
        var ay = (int)Math.Round((y - _desktop.Top) * 65535d / (_desktop.Height - 1));
        Send(new INPUT { Type = 0, Data = new UNION { Mouse = new MOUSEINPUT { X = ax, Y = ay, Flags = 0x8000 | 0x4000 | 0x0001 } } });
    }
    public void MoveAbsolute(LiveInputPermit permit, DisplayBounds display, DisplayBounds desktop, double x, double y)
    {
        if (!OwnsPermit(permit)) throw new ProtocolFailure("FORBIDDEN");
        RequireCurrent();
        var point = CoordinateMapper.Absolute(display, desktop, x, y);
        Send(new INPUT { Type = 0, Data = new UNION { Mouse = new MOUSEINPUT { X = point.X, Y = point.Y, Flags = 0x8000 | 0x4000 | 0x0001 } } });
    }
    public void Button(string button, bool down)
    {
        if (down) RequireCurrent();
        uint flags = (button, down) switch { ("left", true) => 2, ("left", false) => 4, ("right", true) => 8, ("right", false) => 16, ("middle", true) => 32, ("middle", false) => 64, _ => throw new ProtocolFailure("INVALID_PAYLOAD") };
        Send(new INPUT { Type = 0, Data = new UNION { Mouse = new MOUSEINPUT { Flags = flags } } });
    }
    public void Key(string code, bool down)
    {
        if (down) RequireCurrent();
        var extended = code is "Delete" or "Home" or "End" or "PageUp" or "PageDown" or "ControlRight" or "AltRight" || code.StartsWith("Arrow");
        Send(new INPUT { Type = 1, Data = new UNION { Keyboard = new KEYBDINPUT { VirtualKey = KeyCodes.VirtualKey(code), Flags = (down ? 0u : 2u) | (extended ? 1u : 0u) } } });
    }
    public void Text(string text)
    {
        RequireCurrent();
        if (!SchemaCatalog.ValidUnicode(text) || text.EnumerateRunes().Count() is < 1 or > 1024) throw new ProtocolFailure("INVALID_PAYLOAD");
        foreach (var unit in text)
        {
            Send(new INPUT { Type = 1, Data = new UNION { Keyboard = new KEYBDINPUT { Scan = unit, Flags = 4 } } });
            Send(new INPUT { Type = 1, Data = new UNION { Keyboard = new KEYBDINPUT { Scan = unit, Flags = 4 | 2 } } });
        }
    }
    public void Scroll(int horizontal, int vertical)
    {
        RequireCurrent();
        if (horizontal is < -120 or > 120 || vertical is < -120 or > 120) throw new ProtocolFailure("INVALID_PAYLOAD");
        if (vertical != 0) Send(new INPUT { Type = 0, Data = new UNION { Mouse = new MOUSEINPUT { Data = unchecked((uint)(vertical * 120)), Flags = 0x0800 } } });
        if (horizontal != 0) Send(new INPUT { Type = 0, Data = new UNION { Mouse = new MOUSEINPUT { Data = unchecked((uint)(horizontal * 120)), Flags = 0x1000 } } });
    }
    private static void Send(INPUT input)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException();
        Interlocked.Increment(ref _attemptedCalls);
        if (SendInput(1, [input], Marshal.SizeOf<INPUT>()) != 1) throw new InvalidOperationException("OS input rejected; no elevation or UIPI bypass.");
    }
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint Type; public UNION Data; }
    [StructLayout(LayoutKind.Explicit)] private struct UNION { [FieldOffset(0)] public MOUSEINPUT Mouse; [FieldOffset(0)] public KEYBDINPUT Keyboard; }
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT { public int X, Y; public uint Data, Flags, Time; public nuint Extra; }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT { public ushort VirtualKey, Scan; public uint Flags, Time; public nuint Extra; }
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, INPUT[] inputs, int size);
}
