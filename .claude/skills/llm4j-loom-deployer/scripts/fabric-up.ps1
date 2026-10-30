# Brings up a Fabric server in the background (the same as fabric-up.sh).
#   fabric-up.ps1 [-Data DIR] [-Port N] [-Bind ADDR] [-GeneratePassword] [-Extra 'fabric serve' options...]
# The admin password is never taken from a command line and never printed: set LOOM_FABRIC_ADMIN_PASSWORD (12+ characters) for the first start, or pass
# -GeneratePassword to have one written to DIR\admin-password.txt for the developer to read. A second run changes nothing.
param([string]$Data = '.\fabric-data', [int]$Port = 9090, [string]$Bind = '127.0.0.1', [switch]$GeneratePassword, [string[]]$Extra = @())
. "$PSScriptRoot\lib.ps1"
$fa = @('up', '--data', $Data, '--port', "$Port", '--bind', $Bind); if ($Extra) { $fa += '--'; $fa += $Extra }
$rc = Weave-Fabric $fa; if ($null -ne $rc) { exit $rc }   # `weave fabric up` when the installed weave is new enough; the logic below is the fallback
Need-Java; $jar = Fabric-Jar
New-Item -ItemType Directory -Force -Path $Data | Out-Null; $Data = (Resolve-Path $Data).Path
if (-not (Is-Loopback $Bind)) {
  $safe = $Extra | Where-Object { $_ -match '^--(trust-proxy|tls-cert|tls-keystore)' }
  if (-not $safe) { Die "'$Bind' is not this computer. Fabric listens beyond it only with TLS (--tls-keystore ... or --tls-cert ... --tls-key ...) or behind a proxy that terminates it (--trust-proxy). Ask the user which, then pass it with -Extra." 3 }
}
$url = "http://127.0.0.1:$Port"; if ($Extra | Where-Object { $_ -like '--tls-*' }) { $url = "https://127.0.0.1:$Port" }
$pidFile = Join-Path $Data 'fabric.pid'
if ((Test-Path $pidFile) -and (Pid-Alive (Get-Content $pidFile)) -and (Healthy $url 2)) { Say "Fabric is already running: $url (pid $(Get-Content $pidFile), data $Data). Nothing changed."; exit 0 }
if (-not (Test-Path (Join-Path $Data 'vault.json'))) {
  if (-not $env:LOOM_FABRIC_ADMIN_PASSWORD) {
    if (-not $GeneratePassword) { Die "the first start needs an admin password: set LOOM_FABRIC_ADMIN_PASSWORD (12+ characters) in the environment, or pass -GeneratePassword. It is never taken from a command line." 2 }
    $pw = Random-Secret 24; $file = Join-Path $Data 'admin-password.txt'
    Set-Content -Path $file -Value $pw; icacls $file /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
    $env:LOOM_FABRIC_ADMIN_PASSWORD = $pw
    Say "A new admin password was written to $file (readable only by you). It was not printed."
  }
  & java -jar $jar init --data $Data | Out-Null
  if ($LASTEXITCODE -ne 0) { Die "fabric init failed (is the password 12 or more characters?)" 4 }
  Say "Initialised $Data: vault.json and master.key. master.key sits next to the vault for development; in production move it elsewhere (see the deployment chapter)."
}
Remove-Item Env:\LOOM_FABRIC_ADMIN_PASSWORD -ErrorAction SilentlyContinue   # the server holds only the hash, in the vault
$serveArgs = @('-jar', $jar, 'serve', '--data', $Data, '--port', "$Port", '--bind', $Bind) + $Extra
$p = Start-Process -FilePath java -ArgumentList $serveArgs -RedirectStandardOutput (Join-Path $Data 'fabric.log') -RedirectStandardError (Join-Path $Data 'fabric.err') -WindowStyle Hidden -PassThru
Set-Content -Path $pidFile -Value $p.Id
if (Healthy $url 40) {
  Say "Fabric is up: $url (pid $($p.Id), data $Data, log $Data\fabric.log)."
  if (Test-Path (Join-Path $Data 'admin-password.txt')) { Say "Sign in on that page with the admin password in $Data\admin-password.txt." } else { Say "Sign in on that page with the admin password." }
} else {
  Get-Content (Join-Path $Data 'fabric.err') -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { [Console]::Error.WriteLine($_) }
  Stop-Process -Id $p.Id -ErrorAction SilentlyContinue; Remove-Item $pidFile -ErrorAction SilentlyContinue
  Die "Fabric did not answer within 40 seconds (the log is above)." 5
}
