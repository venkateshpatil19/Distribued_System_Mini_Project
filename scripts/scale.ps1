# Scales the agent service with Docker Compose and measures how the cluster copes.
# Usage: scale.bat 10   (or: powershell -File scripts\scale.ps1 -Agents 10)
# Prints, and appends to logs\scale-results.csv: join time, config propagation time, total requests/s.
param([int]$Agents = 10, [string]$Api = "http://127.0.0.1:3000")
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
function Get-Api($path) { Invoke-RestMethod -Uri "$Api$path" -TimeoutSec 10 }

# 1. Join: time until every replica is registered, live and on the latest revision.
Write-Host "Scaling the agent service to $Agents replicas..."
$sw = [Diagnostics.Stopwatch]::StartNew()
docker compose up -d --no-recreate --no-build --scale agent=$Agents
if ($LASTEXITCODE -ne 0) { throw "docker compose failed; start the stack with docker-start.bat first" }
do {
    Start-Sleep -Milliseconds 500
    $ov = Get-Api "/api/overview"
} until (($ov.agents.live -eq $Agents -and $ov.agents.converged -eq $Agents) -or $sw.Elapsed.TotalSeconds -gt 180)
$joinS = [math]::Round($sw.Elapsed.TotalSeconds, 1)
Write-Host "  $($ov.agents.live) live agents, $($ov.agents.converged) converged after $joinS s"

# 2. Propagation: one config change, time until every live agent has applied it.
$body = @{ rolloutPercent = (Get-Random -Minimum 1 -Maximum 99); reason = "scale test with $Agents agents" } | ConvertTo-Json
$rev = (Invoke-RestMethod -Method Put -Uri "$Api/api/flags/recs-limit/envs/staging" -Body $body -ContentType "application/json").revision
$sw.Restart()
do {
    Start-Sleep -Milliseconds 100
    $behind = @((Get-Api "/api/agents") | Where-Object { $_.live -and $_.appliedRevision -lt $rev })
} until ($behind.Count -eq 0 -or $sw.Elapsed.TotalSeconds -gt 60)
$propMs = $sw.ElapsedMilliseconds
Write-Host "  revision r$rev reached all agents in $propMs ms"

# 3. Throughput: simulated requests per second across all agents (two complete 10 s windows).
Write-Host "  measuring traffic for 30 s..."
Start-Sleep -Seconds 30
$pts = @((Get-Api "/api/metrics/series?minutes=2") | Where-Object { $_.variant -eq "all" })
$rps = [math]::Round((($pts[-3], $pts[-2] | Measure-Object -Property requests -Sum).Sum) / 20)
Write-Host "  $rps requests/s handled by $Agents agents"

if (-not (Test-Path logs)) { New-Item -ItemType Directory logs | Out-Null }
$csv = "logs\scale-results.csv"
if (-not (Test-Path $csv)) { "agents,join_s,propagation_ms,requests_per_s" | Out-File $csv -Encoding ascii }
"$Agents,$joinS,$propMs,$rps" | Out-File $csv -Append -Encoding ascii
Write-Host "Saved to $csv"
