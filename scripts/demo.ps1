# DeployGuard real-world demo: "Express checkout launch at an online store".
# Drives the running cluster through the API and narrates each step.
# Needs the app running (start.bat). Works on Windows PowerShell 5.1 and PowerShell 7.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\demo.ps1 [-Api http://localhost:8081] [-NoPause]
param(
  [string]$Api = "http://localhost:8081",
  [switch]$NoPause
)
$ErrorActionPreference = "Stop"

function Step($n, $title) {
  Write-Host ""
  Write-Host ("=" * 72) -ForegroundColor DarkGreen
  Write-Host ("STEP {0}: {1}" -f $n, $title) -ForegroundColor Green
  Write-Host ("=" * 72) -ForegroundColor DarkGreen
}
function Say($text) { Write-Host "  $text" }
function Pause-Demo { if (-not $NoPause) { Read-Host "  Press Enter to continue" | Out-Null } }

function Get-Api($path) { Invoke-RestMethod -Uri "$Api$path" -Method Get }
function Send-Api($method, $path, $body) {
  $json = if ($null -eq $body) { "{}" } else { $body | ConvertTo-Json -Depth 10 -Compress }
  Invoke-RestMethod -Uri "$Api$path" -Method $method -Body $json -ContentType "application/json" -Headers @{ "X-Actor" = "demo-script" }
}

# Polls until $test returns true or the timeout passes; returns elapsed seconds or -1.
function Wait-Until([scriptblock]$test, [int]$timeoutSec, [string]$what) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    if (& $test) { return [math]::Round($sw.Elapsed.TotalSeconds, 1) }
    Start-Sleep -Milliseconds 500
  }
  Say "timed out waiting for: $what"
  return -1
}

# Stops any rollout still running on a flag so every run starts clean.
function Reset-Flag($flag, $envKey) {
  foreach ($r in (Get-Api "/api/rollouts")) {
    if ($r.flagKey -eq $flag -and $r.envKey -eq $envKey -and ($r.state -eq "RUNNING" -or $r.state -eq "PAUSED")) {
      Send-Api POST "/api/rollouts/$($r.id)/rollback" @{ reason = "demo reset" } | Out-Null
    }
  }
}

try { $ov = Get-Api "/api/overview" } catch { Write-Host "Cannot reach $Api. Start the app first: start.bat" -ForegroundColor Red; exit 1 }

Step 1 "The cluster: independent controllers and agents"
Say "Leader: $($ov.leader) (epoch $($ov.epoch)), answered by $($ov.servedBy)"
foreach ($c in $ov.controllers) { Say ("  {0,-14} leader={1,-5} alive={2}" -f $c.id, $c.isLeader, $c.alive) }
Say "Agents: $($ov.agents.live) live, $($ov.agents.converged) on the latest revision r$($ov.latestRevision)"
Say "Simulated shoppers in the last 60 s: $($ov.traffic.requests) requests, $($ov.traffic.errors) errors"
Pause-Demo

Step 2 "Dark launch: only enterprise customers see the new checkout in staging"
Reset-Flag "new-checkout" "staging"
$rule = @{ attribute = "plan"; operator = "equals"; values = @("enterprise"); serve = "on" }
$res = Send-Api PUT "/api/flags/new-checkout/envs/staging" @{ enabled = $true; rolloutPercent = 0; rules = @($rule); reason = "dark launch to enterprise plan" }
Say "Saved as global revision r$($res.revision); every agent applies it in order."
foreach ($u in @(@{ userId = "u1"; plan = "enterprise" }, @{ userId = "u2"; plan = "free" })) {
  $e = Send-Api POST "/api/flags/new-checkout/evaluate" @{ env = "staging"; context = $u }
  Say ("user {0} on plan {1,-10} -> {2,-3} because {3}" -f $u.userId, $u.plan, $e.variant, $e.reason)
}
$t = Wait-Until { (Get-Api "/api/overview").agents.converged -eq (Get-Api "/api/overview").agents.live } 15 "convergence"
Say "All live agents applied r$($res.revision) within $t s."
Pause-Demo

Step 3 "A healthy staged rollout: search ranker 10% -> 50% -> 100%"
Reset-Flag "search-ranker" "production"
$r = Send-Api POST "/api/rollouts" @{ requestId = [guid]::NewGuid().ToString(); flagKey = "search-ranker"; env = "production"; stages = @(10, 50, 100); observeSeconds = 8; minRequests = 30; maxErrorRate = 0.05; maxP95Ms = 250 }
Say "Rollout #$($r.id) started at 10% (revision r$($r.stageRevision)). The leader checks guardrails every second."
$t = Wait-Until { $s = (Get-Api "/api/rollouts/$($r.id)"); Write-Host ("    state={0} stage={1}% verdict={2}" -f $s.state, $s.stages[$s.currentStage], $s.lastVerdict); $s.state -ne "RUNNING" } 90 "rollout to finish"
$final = Get-Api "/api/rollouts/$($r.id)"
Say "Result: $($final.state) after $t s."
Pause-Demo

Step 4 "A broken release: the new checkout fails 30% of payments"
Reset-Flag "new-checkout" "production"
Send-Api POST "/api/faults" @{ type = "unhealthy"; flag = "new-checkout"; errorRate = 0.3; extraLatencyMs = 150; seconds = 120 } | Out-Null
Say "Fault injected (simulated): users on the NEW checkout get 30% errors and +150 ms."
$r = Send-Api POST "/api/rollouts" @{ requestId = [guid]::NewGuid().ToString(); flagKey = "new-checkout"; env = "production"; stages = @(5, 25, 50, 100); observeSeconds = 15; minRequests = 30; maxErrorRate = 0.05; maxP95Ms = 400; onBreach = "ROLLBACK" }
Say "Rollout #$($r.id) started at 5%. Nobody presses a button from here on."
$t = Wait-Until { $s = (Get-Api "/api/rollouts/$($r.id)"); $s.state -ne "RUNNING" } 60 "automatic rollback"
$final = Get-Api "/api/rollouts/$($r.id)"
Say "Result: $($final.state) after $t s"
Say "Reason: $($final.rollbackReason)"
$rev = $final.rollbackRevision
if ($rev) {
  $t2 = Wait-Until { $ok = $true; foreach ($a in (Get-Api "/api/agents")) { if ($a.envKey -eq "production" -and $a.live -and $a.appliedRevision -lt $rev) { $ok = $false } }; $ok } 20 "rollback convergence"
  Say "Every production agent is back on the old checkout (revision r$rev) within $t2 s of the rollback."
}
Send-Api POST "/api/faults" @{ type = "clear" } | Out-Null
Pause-Demo

Step 5 "The leader controller loses its network (simulated partition)"
$leader = (Get-Api "/api/overview").leader
Send-Api POST "/api/controllers/$leader/faults" @{ action = "isolate"; seconds = 12 } | Out-Null
Say "$leader is cut off from its peers for 12 s. The others stop hearing its heartbeats."
$t = Wait-Until { $l = (Get-Api "/api/overview").leader; $l -and $l -ne $leader } 20 "new leader"
Say "New leader: $((Get-Api '/api/overview').leader) after $t s (Bully election)."
$t = Wait-Until { (Get-Api "/api/overview").leader -eq $leader } 30 "old leader to return"
Say "Partition healed: $leader has the highest id, so it takes leadership back after $t s more."
Say "While cut off it could not corrupt rollouts: every write needs a lease and a fresh fencing token."
Pause-Demo

Step 6 "An agent drops off the network, then comes back"
$agent = ((Get-Api "/api/agents") | Where-Object { $_.live } | Select-Object -First 1).id
Send-Api POST "/api/agents/$agent/faults" @{ type = "partition"; seconds = 8 } | Out-Null
Say "$agent stops sending heartbeats for 8 s."
$t = Wait-Until { ((Get-Api "/api/agents") | Where-Object { $_.id -eq $agent }).health -eq "SUSPECTED" } 15 "suspicion"
Say "Failure detector marks it SUSPECTED after $t s (suspicion, not proof of a crash)."
$t = Wait-Until { $h = ((Get-Api "/api/agents") | Where-Object { $_.id -eq $agent }).health; $h -eq "HEALTHY" -or $h -eq "RECOVERING" } 30 "recovery"
Say "It is back ($(((Get-Api '/api/agents') | Where-Object { $_.id -eq $agent }).health)) after $t s and caught up on any missed revision."
Pause-Demo

Step 7 "Audit trail with logical clocks"
foreach ($e in (Get-Api "/api/events?limit=12")) {
  Say ("L={0,-6} {1,-16} {2,-24} {3}" -f $e.lamport, $e.nodeId, $e.type, $e.outcome)
}
Say "Open http://localhost:3000/dashboard to see the same story live in the UI."
