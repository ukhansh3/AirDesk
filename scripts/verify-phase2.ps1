[CmdletBinding()]
param(
    [string]$PythonExe = "",
    [string]$DotNetExe = "dotnet",
    [string]$GradleExe = "gradle",
    [string]$JavaHome = "",
    [string]$AndroidSdk = "",
    [string]$GradleUserHome = "",
    [string]$ReportPath = "",
    [switch]$SkipWindows,
    [switch]$SkipAndroid
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$artifactRoot = Join-Path $repoRoot "out/phase2-verification"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
if (-not $ReportPath) { $ReportPath = Join-Path $artifactRoot "verification.json" }
if (-not $PythonExe) {
    $venvPython = Join-Path $repoRoot "gesture-engine/.venv/Scripts/python.exe"
    $PythonExe = if (Test-Path -LiteralPath $venvPython) { $venvPython } else { "python" }
}

$stages = [System.Collections.Generic.List[object]]::new()
$savedEnvironment = @{}
$environmentNames = @("PYTHONPATH", "PYTHONDONTWRITEBYTECODE", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_USER_HOME", "GRADLE_USER_HOME", "DOTNET_CLI_HOME", "NUGET_PACKAGES", "DOTNET_GENERATE_ASPNET_CERTIFICATE", "DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "DOTNET_NOLOGO", "DOTNET_CLI_TELEMETRY_OPTOUT", "DOTNET_ADD_GLOBAL_TOOLS_TO_PATH")
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Invoke-VerificationStage {
    param([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory, [switch]$RequireHostSummary)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    if (-not (Get-Command $Executable -ErrorAction SilentlyContinue)) {
        $stages.Add([pscustomobject]@{ name=$Name; status="NOT_RUN"; exitCode=$null; durationSeconds=0; reason="Required executable not found" })
        Write-Warning "$Name : NOT_RUN (required executable not found)"
        return
    }
    Push-Location $WorkingDirectory
    try {
        Write-Host "Running $Name" -ForegroundColor Cyan
        $output = @(& $Executable @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output | ForEach-Object { Write-Host $_ }
        $entry = [ordered]@{ name=$Name; status=$(if ($exitCode -eq 0) { "PASSED" } else { "FAILED" }); exitCode=$exitCode; durationSeconds=[Math]::Round($timer.Elapsed.TotalSeconds, 3) }
        if ($RequireHostSummary -and $exitCode -eq 0) {
            $summaryLine = $output | ForEach-Object { "$_" } | Where-Object { $_ -match '^AIRDESK_HOST_TESTS\s+\{' } | Select-Object -Last 1
            if (-not $summaryLine) {
                $entry.status = "FAILED"
                $entry.reason = "Host tests did not provide the required safe-backend summary"
            } else {
                $summary = ($summaryLine -replace '^AIRDESK_HOST_TESTS\s+', '') | ConvertFrom-Json
                $entry.testSummary = $summary
                if ($summary.failed -ne 0 -or $summary.nativeCalls -ne 0 -or $summary.passed -lt 1) {
                    $entry.status = "FAILED"
                    $entry.reason = "Host summary reports failures, native calls, or no tests"
                }
            }
        }
        $stages.Add([pscustomobject]$entry)
    } catch {
        # Do not persist tool output, exception text, input text or credentials.
        $stages.Add([pscustomobject]@{ name=$Name; status="FAILED"; exitCode=$null; durationSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3); reason="Verification command could not complete; inspect the console" })
        Write-Warning "$Name : FAILED"
    } finally {
        Pop-Location
    }
}

try {
    $env:PYTHONPATH = Join-Path $repoRoot "gesture-engine/src"
    $env:PYTHONDONTWRITEBYTECODE = "1"
    $env:DOTNET_CLI_HOME = Join-Path $artifactRoot "dotnet-home"
    $env:DOTNET_GENERATE_ASPNET_CERTIFICATE = "false"
    $env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = "1"
    $env:DOTNET_NOLOGO = "1"
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"
    $env:DOTNET_ADD_GLOBAL_TOOLS_TO_PATH = "0"
    if (-not $env:NUGET_PACKAGES) { $env:NUGET_PACKAGES = Join-Path $artifactRoot "nuget" }
    $env:ANDROID_USER_HOME = Join-Path $artifactRoot "android-user"
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    if ($AndroidSdk) { $env:ANDROID_HOME = $AndroidSdk; $env:ANDROID_SDK_ROOT = $AndroidSdk }
    if ($GradleUserHome) { $env:GRADLE_USER_HOME = $GradleUserHome }
    elseif (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $artifactRoot "gradle-home" }

    Invoke-VerificationStage -Name "Phase1 Python regression" -Executable $PythonExe -Arguments @("-m", "unittest", "discover", "-s", "tests", "-v") -WorkingDirectory (Join-Path $repoRoot "gesture-engine")
    Invoke-VerificationStage -Name "Shared Phase2 wire fixtures" -Executable $PythonExe -Arguments @("-m", "unittest", "discover", "-s", "integration-tests", "-p", "test_phase2_contract_harness.py", "-v") -WorkingDirectory $repoRoot
    if ($SkipWindows) {
        $stages.Add([pscustomobject]@{name="Windows tests and WPF build";status="NOT_RUN";reason="Explicitly skipped"})
    } else {
        Invoke-VerificationStage -Name "Windows mock/security harness" -Executable $DotNetExe -Arguments @("run", "--project", "windows-host/AirDesk.Host.Tests/AirDesk.Host.Tests.csproj", "--configuration", "Release") -WorkingDirectory $repoRoot -RequireHostSummary
        Invoke-VerificationStage -Name "Windows WPF build" -Executable $DotNetExe -Arguments @("build", "windows-host/AirDesk.Host/AirDesk.Host.csproj", "--configuration", "Release", "--nologo") -WorkingDirectory $repoRoot
    }
    if ($SkipAndroid) {
        $stages.Add([pscustomobject]@{name="Android JVM tests and debug build";status="NOT_RUN";reason="Explicitly skipped"})
    } else {
        Invoke-VerificationStage -Name "Android JVM tests and assembleDebug" -Executable $GradleExe -Arguments @("--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", ":app:testDebugUnitTest", ":app:assembleDebug") -WorkingDirectory (Join-Path $repoRoot "android-client")
    }
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}

$reportDirectory = Split-Path ([IO.Path]::GetFullPath($ReportPath)) -Parent
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report = [ordered]@{
    generatedAtUtc=[DateTime]::UtcNow.ToString("o")
    scope="Automated tests/builds only; no GUI, camera, emulator or native OS input execution"
    stages=@($stages.ToArray())
    manualGates=@("PLEOS SDK/permissions and safety evidence NOT_VERIFIED", "Actual native Windows input NOT_RUN", "User-run pairing/device tests recorded separately")
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding utf8
$stages | Format-Table name,status,exitCode,durationSeconds -AutoSize
Write-Host "Sanitized report: $ReportPath"
if (@($stages | Where-Object status -eq "FAILED").Count) { exit 1 }
if (@($stages | Where-Object status -eq "NOT_RUN").Count) { exit 2 }
exit 0
