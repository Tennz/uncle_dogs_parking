@echo off
setlocal
cd /d "%~dp0"
title Uncle Dogs Parking - Supabase Connection Check
echo.
echo Uncle Dogs Parking - Supabase network check
echo This test does NOT use or display your database password.
echo It checks DNS and TCP reachability only; it does not log into PostgreSQL.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-supabase.ps1"
echo.
pause
endlocal
