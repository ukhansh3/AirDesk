$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:AIRDESK_DEV_TOKEN -or $env:AIRDESK_DEV_TOKEN.Length -lt 24) {
    throw "Set a fresh AIRDESK_DEV_TOKEN of at least 24 chars before local diagnostics."
}
Push-Location (Join-Path $root "gesture-engine")
try {
    $env:PYTHONPATH = Join-Path (Get-Location) "src"
    python -m airdesk_gesture.ws_server --source synthetic
} finally { Pop-Location }
