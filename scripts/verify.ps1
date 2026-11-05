$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $root "gesture-engine")
try {
    $env:PYTHONPATH = Join-Path (Get-Location) "src"
    python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw "Python tests failed" }
} finally { Pop-Location }
Write-Host "Python contract + deterministic gesture tests completed." -ForegroundColor Green
Write-Host "Windows, Android, PLEOS, camera and screen streaming have separate verification gates."
