$ErrorActionPreference = 'Stop'

# Elevate once so the inbound rule can be added safely.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $argumentLine = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    try {
        $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $argumentLine -Verb RunAs -Wait -PassThru
        exit $process.ExitCode
    } catch {
        Write-Error 'Administrator approval was cancelled or could not be started.'
        exit 1
    }
}

$port = 8080
if ($env:PORT -match '^\d+$') { $port = [int]$env:PORT }
$configPath = Join-Path $PSScriptRoot 'config.txt'
if (-not ($env:PORT -match '^\d+$') -and (Test-Path -LiteralPath $configPath)) {
    $portLine = Get-Content -LiteralPath $configPath | Where-Object { $_ -match '^\s*PORT\s*=\s*\d+\s*$' } | Select-Object -First 1
    if ($portLine -match '^\s*PORT\s*=\s*(\d+)\s*$') { $port = [int]$Matches[1] }
}
if ($port -lt 1 -or $port -gt 65535) { throw "Invalid app port in config.txt: $port" }

$ruleName = "Uncle Dogs Parking LAN TCP $port"
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleName `
    -Description 'Allows Uncle Dogs Parking only from devices on the connected local subnet.' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port `
    -RemoteAddress LocalSubnet -Profile Any | Out-Null

Write-Host "Firewall rule created for TCP port $port." -ForegroundColor Green
Write-Host 'The rule is restricted to your local subnet; it does not configure router port forwarding.'
Write-Host 'Keep the phone and host PC on the same non-guest Wi-Fi network.'
