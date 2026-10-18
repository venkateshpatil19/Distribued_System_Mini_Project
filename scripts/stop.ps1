# Stops DeployGuard and frees its ports.
# 1. Restart loops (logs\run-*.cmd) go first, so nothing relaunches the processes we stop next.
# 2. Java and Node processes started from this folder.
# 3. Anything else still listening on one of DeployGuard's ports.
# Exit code 0 when every port is free afterwards, 1 otherwise.
$root = Split-Path -Parent $PSScriptRoot
$ports = @(3000, 8081, 8082, 8083, 8101, 8102, 8103, 9091, 9092, 9093)

function Stop-Matching($filter, $pattern) {
    Get-CimInstance Win32_Process -Filter $filter -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like $pattern } |
        ForEach-Object {
            Write-Host "  stopping $($_.Name) (PID $($_.ProcessId))"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
}

function Get-ListenerPids([int]$port) {
    $pids = @()
    try {
        $pids = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique)
    } catch {
        # Thrown when nothing listens, or when the cmdlet is missing: netstat covers both.
        $pids = @(netstat -ano | Select-String 'LISTENING' | ForEach-Object {
                $cols = $_.ToString().Trim() -split '\s+'
                if ($cols[1] -match ":$port$") { [int]$cols[-1] }
            } | Select-Object -Unique)
    }
    # PIDs 0 and 4 belong to Windows itself and cannot be stopped.
    return @($pids | Where-Object { $_ -gt 4 })
}

Stop-Matching "Name='cmd.exe'" "*\logs\run-*.cmd*"
Stop-Matching "Name='java.exe'" "*deployguard.jar*"
Stop-Matching "Name='node.exe'" "*standalone\server.js*"

foreach ($port in $ports) {
    foreach ($procId in Get-ListenerPids $port) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        # Never stop Docker itself; a Docker run is stopped with docker-stop.bat.
        if ($p -and $p.ProcessName -match '^(com\.docker|docker|wslrelay|vpnkit)') {
            Write-Host "  port $port is used by Docker ($($p.ProcessName)); run docker-stop.bat first"
            continue
        }
        if ($p) {
            Write-Host "  port $port is used by $($p.ProcessName) (PID $procId), stopping it"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
    }
}

# Windows needs a moment to release the sockets.
for ($i = 0; $i -lt 20; $i++) {
    $busy = @($ports | Where-Object { (Get-ListenerPids $_).Count -gt 0 })
    if ($busy.Count -eq 0) { exit 0 }
    Start-Sleep -Milliseconds 500
}
Write-Host "  [x] still in use: $($busy -join ', '). Close the program using them, or run as administrator."
exit 1
