# AirDesk Windows Host — WPF baseline

This module currently compiles a diagnostic **locked** window (requires .NET 10 Windows desktop tooling). It contains NO screen capture, SendInput, pairing or mouse control yet.

~~~powershell
dotnet build windows-host/AirDesk.Host/AirDesk.Host.csproj
dotnet run --project windows-host/AirDesk.Host/AirDesk.Host.csproj
~~~

The current display shows safety and auth prerequisites. Production pairing and session authorization must be designed, tested and approved before activating input.
See docs/features/INPUT_SPEC.md and PAIRING_SPEC.md.
