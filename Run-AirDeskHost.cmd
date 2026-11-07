@echo off
setlocal
cd /d "%~dp0"
where dotnet >nul 2>&1
if errorlevel 1 (
  echo AirDesk Host requires the .NET 10 SDK with Windows desktop and ASP.NET Core support.
  echo Install the SDK, then run this file again.
  pause
  exit /b 1
)
set DOTNET_GENERATE_ASPNET_CERTIFICATE=false
set DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
set DOTNET_CLI_TELEMETRY_OPTOUT=1
set DOTNET_ADD_GLOBAL_TOOLS_TO_PATH=0
dotnet run --project "%~dp0windows-host\AirDesk.Host\AirDesk.Host.csproj" --configuration Release
if errorlevel 1 (
  echo AirDesk Host could not start. Review the build or runtime error above.
  pause
  exit /b 1
)
endlocal
