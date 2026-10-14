# Stops the Fabric server of a data directory (what it holds stays on disk). Safe to run when it is not running.
#   fabric-down.ps1 [-Data DIR]
param([string]$Data = '.\fabric-data')
. "$PSScriptRoot\lib.ps1"
$pidFile = Join-Path $Data 'fabric.pid'
if (-not (Test-Path $pidFile)) { Say "Fabric is not running (no pid file in $Data). Nothing to do."; exit 0 }
$p = Get-Content $pidFile
if (Pid-Alive $p) { Stop-Process -Id ([int]$p) -ErrorAction SilentlyContinue; Say "Fabric (pid $p) stopped." } else { Say "Fabric was not running (a stale pid file was removed)." }
Remove-Item $pidFile -ErrorAction SilentlyContinue
