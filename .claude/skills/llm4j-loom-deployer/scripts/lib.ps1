# Shared by the other .ps1 scripts in this folder. Dot-sourced, not run.
# The same rules as the .sh scripts: no secret on a command line or in anything printed; nothing listens beyond this computer without TLS or a proxy;
# every step is safe to run twice and says what it did.
$ErrorActionPreference = 'Stop'
$SkillDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Say($m) { Write-Host $m }
function Die($m, $code = 1) { [Console]::Error.WriteLine("error: $m"); exit $code }

function Need-Java {
  if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Die "Java 17 or newer is needed and 'java' was not found. Install a JDK, then run this again." 2 }
  $line = (& java -version 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'version "' } | Select-Object -First 1)
  if ($line -notmatch 'version "(\d+)') { Die "could not read the Java version" 2 }
  if ([int]$Matches[1] -lt 17) { Die "Java 17 or newer is needed (found version $($Matches[1]))." 2 }
}

function Find-Jar([string]$EnvVar, [string[]]$Patterns) {
  $given = [Environment]::GetEnvironmentVariable($EnvVar)
  if ($given) { if (-not (Test-Path $given -PathType Leaf)) { Die "$EnvVar points at '$given', which is not a file" 2 }; return $given }
  $dirs = @("$SkillDir\bin", "$SkillDir\..\..\..\src\loom\loom-fabric\target", "$SkillDir\..\..\..\src\loom\loom-inspect\target", "$SkillDir\..\..\..\src\loom\loom-inspect-core\target", "$SkillDir\..\..\..\src\loom\ai-agent4j-loom\target")
  foreach ($p in $Patterns) { foreach ($d in $dirs) {
    if (Test-Path $d) { $hit = Get-ChildItem -Path $d -Filter $p -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc|tests' } | Select-Object -First 1; if ($hit) { return $hit.FullName } }
  } }
  return $null
}
function Fabric-Jar { $j = Find-Jar 'FABRIC_JAR' @('fabric.jar', 'loom-fabric-*-all.jar'); if (-not $j) { Die "the Fabric jar was not found. Put it in $SkillDir\bin or set FABRIC_JAR." 2 }; $j }
function Weave-Jar  { $j = Find-Jar 'WEAVE_JAR' @('weave.jar', 'ai-agent4j-loom-*-cli.jar'); if (-not $j) { Die "the weave jar was not found. Put it in $SkillDir\bin or set WEAVE_JAR." 2 }; $j }
function Inspect-Jars {
  $a = Find-Jar 'INSPECT_JAR' @('loom-inspect.jar', 'loom-inspect-[0-9]*.jar'); $b = Find-Jar 'INSPECT_CORE_JAR' @('loom-inspect-core.jar', 'loom-inspect-core-[0-9]*.jar')
  if (-not $a -or -not $b) { Die "the loom-inspect add-on jars were not found. Put loom-inspect.jar and loom-inspect-core.jar in $SkillDir\bin or set INSPECT_JAR and INSPECT_CORE_JAR." 2 }
  "$a;$b"
}
function Is-Loopback([string]$a) { $a -match '^(127\.|localhost$|::1$|\[::1\]$)' }
function Healthy([string]$url, [int]$seconds) {
  for ($i = 0; $i -lt $seconds; $i++) {
    try { $r = Invoke-WebRequest -Uri "$url/api/health" -UseBasicParsing -TimeoutSec 3 -SkipCertificateCheck:($url -like 'https*'); if ($r.StatusCode -eq 200) { return $true } } catch { }
    Start-Sleep -Seconds 1
  }
  return $false
}
function Pid-Alive($p) { if (-not $p) { return $false }; [bool](Get-Process -Id ([int]$p) -ErrorAction SilentlyContinue) }
function Random-Secret([int]$n) { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count $n | ForEach-Object { [char]$_ }) }
