@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Uncle Dogs Parking
where javac >nul 2>nul
if errorlevel 1 (
  echo Java Development Kit not found. Install Java 17 or newer and add javac to PATH.
  pause
  exit /b 1
)
if not exist out mkdir out
if not exist lib mkdir lib
set "NEED_PG=%DATABASE_URL%"
if not defined NEED_PG (
  for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"DATABASE_URL=" config.txt 2^>nul') do set "NEED_PG=%%B"
)
if defined NEED_PG (
  if not exist lib\postgresql-42.7.14.jar (
    echo Downloading PostgreSQL JDBC driver for Supabase mode...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri 'https://jdbc.postgresql.org/download/postgresql-42.7.14.jar' -OutFile 'lib/postgresql-42.7.14.jar' -UseBasicParsing; exit 0 } catch { Write-Error $_; exit 1 }"
    if errorlevel 1 (
      echo Could not download PostgreSQL JDBC driver. Check your internet connection and try again.
      pause
      exit /b 1
    )
  )
  set "CONFIG_DB_PASSWORD="
  for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"DATABASE_PASSWORD=" config.txt 2^>nul') do set "CONFIG_DB_PASSWORD=%%B"
  if not defined DATABASE_PASSWORD if not defined CONFIG_DB_PASSWORD (
    echo.
    echo Supabase is configured. Enter your Supabase database password.
    echo The password is hidden while you type and is not saved to config.txt.
    for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$s=Read-Host 'Supabase database password' -AsSecureString; $b=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s); try {[Runtime.InteropServices.Marshal]::PtrToStringBSTR($b)} finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b)}"`) do set "DATABASE_PASSWORD=%%P"
    if not defined DATABASE_PASSWORD (
      echo No password was entered. Cannot start in Supabase mode.
      pause
      exit /b 1
    )
  )
)
if exist sources.txt del sources.txt
echo Building Java 17-compatible source list...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$root=(Get-Location).Path; Get-ChildItem 'src' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\','/') } | Set-Content -Encoding ascii 'sources.txt'"
if errorlevel 1 (
  echo Could not create the Java source list.
  pause
  exit /b 1
)
javac --release 17 -encoding UTF-8 -d out @sources.txt
if errorlevel 1 (
  echo Compilation failed. Review the Java error above.
  pause
  exit /b 1
)
if exist lib\postgresql-42.7.14.jar (
  java -cp "out;lib/postgresql-42.7.14.jar" Main
) else (
  java -cp out Main
)
if errorlevel 1 pause
endlocal
