@echo off
setlocal
if not exist "%~dp0gesture-engine\.venv\Scripts\pythonw.exe" (
  echo AirDesk Python environment is missing.
  echo Please prepare gesture-engine\.venv first.
  pause
  exit /b 1
)
start "AirDesk" "%~dp0gesture-engine\.venv\Scripts\pythonw.exe" "%~dp0gesture-engine\airdesk_pc.pyw"
