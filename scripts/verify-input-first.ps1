[CmdletBinding()]
param(
    [string]$PythonExe = "",
    [string]$GradleExe = "gradle",
    [string]$JavaHome = "",
    [string]$AndroidSdk = "",
    [string]$GradleUserHome = "",
    [string]$ReportPath = "",
    [switch]$SkipAndroid
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$artifactRoot = Join-Path $repoRoot "out/input-first-verification"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
if (-not $ReportPath) { $ReportPath = Join-Path $artifactRoot "verification.json" }
if (-not $PythonExe) {
    $localPython = Join-Path $repoRoot "gesture-engine/.venv/Scripts/python.exe"
    $PythonExe = if (Test-Path -LiteralPath $localPython) { $localPython } else { "python" }
}

$stages = [System.Collections.Generic.List[object]]::new()
$savedEnvironment = @{}
$environmentNames = @("PYTHONPATH", "PYTHONDONTWRITEBYTECODE", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_USER_HOME", "GRADLE_USER_HOME")
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Get-JunitSummary {
    $directory = Join-Path $repoRoot "android-client/app/build/test-results/testDebugUnitTest"
    $files = @(Get-ChildItem -LiteralPath $directory -Filter "TEST-*.xml" -File -ErrorAction SilentlyContinue)
    if (-not $files.Count) { throw "JUnit XML missing" }
    $summary = [ordered]@{ suites=$files.Count; tests=0; failures=0; errors=0; skipped=0 }
    foreach ($file in $files) {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw
        if (-not $document.testsuite) { throw "JUnit suite missing" }
        foreach ($field in @("tests", "failures", "errors", "skipped")) {
            $summary[$field] += [int]$document.testsuite.GetAttribute($field)
        }
    }
    if ($summary.tests -lt 1) { throw "No tests recorded" }
    return [pscustomobject]$summary
}

function Invoke-VerificationStage {
    param([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory, [switch]$Android)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    if (-not (Get-Command $Executable -ErrorAction SilentlyContinue)) {
        $stages.Add([pscustomobject]@{ name=$Name; status="NOT_RUN"; exitCode=$null; durationSeconds=0; reason="Required executable not found" })
        Write-Warning "$Name : NOT_RUN (required executable not found)"
        return
    }
    Push-Location $WorkingDirectory
    try {
        Write-Host "Running $Name" -ForegroundColor Cyan
        # unittest writes progress to stderr even on success. Windows PowerShell 5
        # must not turn those native progress records into terminating errors.
        $stagePreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $output = @(& $Executable @Arguments 2>&1)
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $stagePreference
        }
        $output | ForEach-Object { Write-Host $_ }
        $entry = [ordered]@{ name=$Name; status=$(if ($exitCode -eq 0) { "PASSED" } else { "FAILED" }); exitCode=$exitCode; durationSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3) }
        if ($Android -and $exitCode -eq 0) {
            $entry.testSummary = Get-JunitSummary
            if ($entry.testSummary.failures -gt 0 -or $entry.testSummary.errors -gt 0) {
                $entry.status = "FAILED"
            } elseif ($entry.testSummary.skipped -gt 0) {
                $entry.status = "PARTIAL"
            }
        } elseif (-not $Android) {
            $totals = @($output | ForEach-Object { "$_" } | Select-String -Pattern '^Ran (\d+) tests? in ')
            if ($totals.Count) { $entry.testSummary = [pscustomobject]@{ tests=[int]$totals[-1].Matches[0].Groups[1].Value } }
            if ($exitCode -eq 0 -and @($output | ForEach-Object { "$_" } | Where-Object { $_ -match '^OK \(.*skipped=' }).Count) {
                $entry.status = "PARTIAL"
            }
        }
        $stages.Add([pscustomobject]$entry)
    } catch {
        # Do not persist raw tool output, text, tokens, key material or exception messages.
        $stages.Add([pscustomobject]@{ name=$Name; status="FAILED"; exitCode=$null; durationSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3); reason="Command or result validation failed; inspect the console" })
        Write-Warning "$Name : FAILED"
    } finally {
        Pop-Location
    }
}

try {
    $env:PYTHONPATH = Join-Path $repoRoot "gesture-engine/src"
    $env:PYTHONDONTWRITEBYTECODE = "1"
    $env:ANDROID_USER_HOME = Join-Path $artifactRoot "android-user"
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    if ($AndroidSdk) { $env:ANDROID_HOME = $AndroidSdk; $env:ANDROID_SDK_ROOT = $AndroidSdk }
    if ($GradleUserHome) { $env:GRADLE_USER_HOME = $GradleUserHome }
    elseif (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $artifactRoot "gradle-home" }

    Invoke-VerificationStage -Name "Python regression and AIR models" -Executable $PythonExe -Arguments @("-m", "unittest", "discover", "-s", "tests", "-v") -WorkingDirectory (Join-Path $repoRoot "gesture-engine")
    Invoke-VerificationStage -Name "Canonical wire fixtures" -Executable $PythonExe -Arguments @("-m", "unittest", "discover", "-s", "integration-tests", "-p", "test_phase2_contract_harness.py", "-v") -WorkingDirectory $repoRoot
    Invoke-VerificationStage -Name "Experimental air-typing wire fixtures" -Executable $PythonExe -Arguments @("-m", "unittest", "discover", "-s", "integration-tests", "-p", "test_air_typing_contracts.py", "-v") -WorkingDirectory $repoRoot
    if ($SkipAndroid) {
        $stages.Add([pscustomobject]@{name="Android JVM tests and assembleDebug"; status="NOT_RUN"; reason="Explicitly skipped"})
    } else {
        Invoke-VerificationStage -Name "Android JVM tests and assembleDebug" -Executable $GradleExe -Arguments @("--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", ":app:testDebugUnitTest", ":app:assembleDebug") -WorkingDirectory (Join-Path $repoRoot "android-client") -Android
    }
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}

$reportDirectory = Split-Path ([IO.Path]::GetFullPath($ReportPath)) -Parent
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
[ordered]@{
    generatedAtUtc=[DateTime]::UtcNow.ToString("o")
    scope="ADR-004 app-owned DEMO desktop input and experimental ten-finger AIR; automated tests/build only"
    stages=@($stages.ToArray())
    legacyWindows="Optional historical subsystem unchanged; not part of this revised harness, previous TLS failures are not relabeled"
    manualGates=@("Real PLEOS display/touch/gesture and Korean IME NOT_RUN", "Actual ten-finger AIR camera typing accuracy/latency NOT_RUN", "Official vehicle safety/camera NOT_VERIFIED", "Operational input remains BLOCKED when safety UNKNOWN")
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding utf8
$stages | Format-Table name,status,exitCode,durationSeconds -AutoSize
Write-Host "Sanitized report: $ReportPath"
if (@($stages | Where-Object status -eq "FAILED").Count) { exit 1 }
if (@($stages | Where-Object { $_.status -in @("NOT_RUN", "PARTIAL") }).Count) { exit 2 }
exit 0
