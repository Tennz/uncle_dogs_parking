$ErrorActionPreference = 'Continue'
$configPath = Join-Path $PSScriptRoot 'config.txt'
if (-not (Test-Path -LiteralPath $configPath)) {
    Write-Host 'config.txt was not found beside this script.' -ForegroundColor Red
    exit 1
}
$urlLine = Get-Content -LiteralPath $configPath | Where-Object { $_ -match '^\s*DATABASE_URL\s*=' } | Select-Object -First 1
$url = if ($urlLine) { ($urlLine -split '=', 2)[1].Trim() } else { '' }
if ([string]::IsNullOrWhiteSpace($url)) {
    Write-Host 'DATABASE_URL is blank. Supabase mode is not configured.' -ForegroundColor Yellow
    exit 1
}
if ($url -match '^jdbc:postgresql://') { $uriText = $url -replace '^jdbc:', '' } else { $uriText = $url }
try {
    $uri = [System.Uri]::new($uriText)
    $hostName = $uri.DnsSafeHost
    $configuredPort = $uri.Port
} catch {
    Write-Host 'DATABASE_URL could not be parsed. Copy the exact host and port from Supabase Connect.' -ForegroundColor Red
    exit 1
}
if ([string]::IsNullOrWhiteSpace($hostName) -or $hostName -match 'YOUR_|PASTE_|PROJECT_REF') {
    Write-Host 'The database host is a placeholder. Copy the exact hostname from Supabase Connect.' -ForegroundColor Red
    exit 1
}
Write-Host "Host: $hostName"
Write-Host "Configured port: $configuredPort"
Write-Host ''
try {
    $addresses = [System.Net.Dns]::GetHostAddresses($hostName)
    Write-Host ('DNS: resolved to ' + (($addresses | ForEach-Object { $_.IPAddressToString }) -join ', ')) -ForegroundColor Green
} catch {
    Write-Host 'DNS: FAILED. Check the hostname and internet connection.' -ForegroundColor Red
    exit 2
}
$ports = @($configuredPort)
if ($hostName.ToLowerInvariant().EndsWith('.pooler.supabase.com')) {
    if ($configuredPort -eq 5432) { $ports += 6543 }
    elseif ($configuredPort -eq 6543) { $ports += 5432 }
}
$ports = $ports | Select-Object -Unique
foreach ($port in $ports) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($hostName, [int]$port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(4000, $false)
        if ($ok -and $client.Connected) {
            $client.EndConnect($async)
            Write-Host "TCP port ${port}: REACHABLE" -ForegroundColor Green
        } else {
            Write-Host "TCP port ${port}: NOT REACHABLE within 4 seconds" -ForegroundColor Red
        }
    } catch {
        Write-Host "TCP port ${port}: NOT REACHABLE ($($_.Exception.GetType().Name))" -ForegroundColor Red
    } finally {
        $client.Close()
    }
}
Write-Host ''
Write-Host 'NOTE: TCP reachability does not prove PostgreSQL SSL/authentication will succeed.'
Write-Host 'If TCP is reachable but Java times out during SSL, compare the copied endpoint with Supabase Connect and retry on a different network/hotspot.'
Write-Host 'If only port 6543 is reachable, v1.8.3 can automatically try the shared transaction pooler after a port-5432 connection failure.'
