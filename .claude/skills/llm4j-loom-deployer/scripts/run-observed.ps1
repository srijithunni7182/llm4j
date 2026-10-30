# Runs a Loom workflow with observing on (the same as run-observed.sh): its own page, or reporting to a Fabric server. The Fabric client key goes into the
# process's secret store and is never printed or put on a command line.
#   run-observed.ps1 -Script FLOW.loom [-Embedded -Port 8787] [-Linger 5m]
#   run-observed.ps1 -Script FLOW.loom -Fabric URL -Name NAME [-Data FABRIC_DATA_DIR | -KeyStdin] [-Ca FILE] [-Detail metadata] [-Rules FILE] [-Secrets STORE] [-SecretsKeyFile FILE] [-More '--max-cost','0.50',...]
param([Parameter(Mandatory)][string]$Script, [string]$Fabric = '', [string]$Name = '', [string]$Data = '', [string]$Ca = '', [string]$Detail = '', [string]$Rules = '', [string]$Secrets = '', [string]$SecretsKeyFile = '',
      [switch]$KeyStdin, [switch]$Embedded, [int]$Port = 8787, [string]$Linger = '', [string[]]$More = @())
. "$PSScriptRoot\lib.ps1"
if (-not (Test-Path $Script)) { Die "the script '$Script' does not exist" 2 }
Need-Java; $weave = Weave-Jar; $inspect = Inspect-Jars
$run = @('-cp', "$weave;$inspect", 'io.github.llm4j.loom.cli.WeaveCLI')
$scriptDir = (Resolve-Path (Split-Path $Script -Parent)).Path
$obs = @()
if ($Fabric) {
  if (-not $Name) { Die "-Fabric needs -Name (how this process is shown on the server)" 2 }
  if ($Fabric -notmatch '^(https://|http://127\.0\.0\.1|http://localhost)') { Die "'$Fabric': a process reports to a server over https (plain http is accepted only for this computer's own address)." 3 }
  if (-not $Secrets) { $Secrets = Join-Path $scriptDir '.loom\secrets.store' }; $keyFile = if ($SecretsKeyFile) { $SecretsKeyFile } elseif ($env:LOOM_SECRETS_KEY_FILE) { $env:LOOM_SECRETS_KEY_FILE } else { [IO.Path]::ChangeExtension($Secrets, '.key') }
  New-Item -ItemType Directory -Force -Path (Split-Path $Secrets -Parent) | Out-Null
  if (-not (Test-Path $Secrets)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $keyFile -Parent) | Out-Null
    if (-not (Test-Path $keyFile)) { Set-Content -Path $keyFile -Value (Random-Secret 40); icacls $keyFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null }
    & java @run secrets create --secrets $Secrets --secrets-key-file $keyFile | Out-Null
    Say "Created the process's secret store $Secrets (master key in $keyFile; keep both out of git)."
  }
  $have = (& java @run secrets list --secrets $Secrets --secrets-key-file $keyFile 2>$null) -match 'LOOM_FABRIC_CLIENT_KEY'
  if (-not $have) {
    if ($KeyStdin) { $input | & java @run secrets set LOOM_FABRIC_CLIENT_KEY --stdin --secrets $Secrets --secrets-key-file $keyFile | Out-Null; Say "Stored the Fabric client key for '$Name' from standard input." }
    elseif ($Data) {
      $fab = Fabric-Jar
      if ((& java -jar $fab clients list --data $Data 2>$null) -match "(^|\s)$([regex]::Escape($Name))(\s|$)") { Die "the client '$Name' already exists on that Fabric but its key is not in $Secrets. The key is shown once: revoke it ('java -jar fabric.jar clients revoke $Name --data $Data') and run this again, or pipe the key in with -KeyStdin." 4 }
      (& java -jar $fab clients add $Name --data $Data) | Where-Object { $_ -match '^\s+lfk_' } | ForEach-Object { $_.Trim().Split(' ')[0] } | & java @run secrets set LOOM_FABRIC_CLIENT_KEY --stdin --secrets $Secrets --secrets-key-file $keyFile | Out-Null
      Say "Created the Fabric client '$Name' and stored its key in $Secrets (not printed)."
    } else { Die "the process has no Fabric client key in $Secrets. Give -Data DIR (a Fabric on this computer: the client is created) or pipe the key the admin gave you with -KeyStdin." 4 }
  }
  $obs = @('--observe-fabric', $Fabric, '--observe-name', $Name, '--secrets', $Secrets, '--secrets-key-file', $keyFile); if ($Ca) { $obs += @('--observe-ca', $Ca) }
} else {
  $obs = @("--observe=$Port"); if ($Linger) { $obs += @('--observe-linger', $Linger) }
  Say "The run's own page: http://127.0.0.1:$Port (it is served while the run lasts)."
}
if ($Detail) { $obs += @('--observe-detail', $Detail) }; if ($Rules) { $obs += @('--observe-rules', $Rules) }
Say "Running $Script with observing on."
& java @run run $Script @obs @More
exit $LASTEXITCODE
