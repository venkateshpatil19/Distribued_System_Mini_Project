# Windows version of run-demo.sh. Usage: powershell -ExecutionPolicy Bypass -File run-demo.ps1 bully
param([string]$Mode = "bully")
$peers = "1:5001,2:5002,3:5003,4:5004,5:5005"
javac ElectionNode.java; if ($LASTEXITCODE -ne 0) { exit 1 }
$procs = @{}
foreach ($i in 1..5) {
  $procs[$i] = Start-Process java -ArgumentList "ElectionNode",$Mode,$i,$peers -PassThru -NoNewWindow -RedirectStandardOutput "node$i.log"
}
Write-Host "5 nodes started in $Mode mode; waiting for the first election..."
Start-Sleep 8
Write-Host ">>> killing node 5 (the leader)"; Stop-Process -Id $procs[5].Id
Start-Sleep 10
Write-Host ">>> restarting node 5"
$procs[5] = Start-Process java -ArgumentList "ElectionNode",$Mode,5,$peers -PassThru -NoNewWindow -RedirectStandardOutput "node5-restart.log"
Start-Sleep 8
$procs.Values | ForEach-Object { Stop-Process -Id $_.Id -ErrorAction SilentlyContinue }
Get-Content node*.log | Sort-Object
