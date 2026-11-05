$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location (Join-Path $root "gesture-engine")
py -3.11 -m venv .venv
& ".venv/Scripts/python.exe" -m pip install --upgrade pip
& ".venv/Scripts/python.exe" -m pip install -e ".[dev,ws]"
Write-Host "Optional real webcam: .venv/Scripts/python.exe -m pip install -e '.[camera]'"
Write-Host "Obtain an official compatible hand_landmarker.task externally; models are ignored."
