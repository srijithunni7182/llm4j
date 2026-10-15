# Says whether the Fabric server of a data directory is running, and which processes may report to it (names only, never keys).
#   fabric-status.ps1 [-Data DIR] [-Port N]
param([string]$Data = '.\fabric-data', [int]$Port = 9090)
. "$PSScriptRoot\lib.ps1"
if (-not (Test-Path $Data)) { Say "No Fabric data directory at ${Data}: it has not been set up here. Run fabric-up.ps1."; exit 1 }
$Data = (Resolve-Path $Data).Path; $pidFile = Join-Path $Data 'fabric.pid'
if ((Test-Path $pidFile) -and (Pid-Alive (Get-Content $pidFile))) {
  if ((Healthy "http://127.0.0.1:$Port" 3) -or (Healthy "https://127.0.0.1:$Port" 1)) { Say "running: pid $(Get-Content $pidFile), port $Port, data $Data" }
  else { Say "the process is alive (pid $(Get-Content $pidFile)) but does not answer on port ${Port}: look at $Data\fabric.log" }
} else { Say "not running (data $Data exists). Start it with fabric-up.ps1." }
if (Test-Path (Join-Path $Data 'vault.json')) { Need-Java; Say 'clients that may report:'; & java -jar (Fabric-Jar) clients list --data $Data 2>$null | ForEach-Object { "  $_" } }
