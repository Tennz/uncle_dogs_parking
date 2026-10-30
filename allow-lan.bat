@echo off
setlocal
cd /d "%~dp0"
echo Uncle Dogs Parking - local Wi-Fi access setup
echo This adds a Windows Firewall rule scoped to the local subnet only.
echo Windows will request administrator approval.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0allow-lan.ps1"
if errorlevel 1 (
  echo.
  echo Could not add the firewall rule. Try right-clicking this file and choosing Run as administrator.
  pause
  exit /b 1
)
echo.
pause
endlocal
