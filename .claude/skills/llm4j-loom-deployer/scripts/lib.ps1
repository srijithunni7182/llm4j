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

# Where the jars may be, in the same order as lib.sh: the variable (in Find-Jar), the editor extension's bundled jar (weave only), bin\ of this skill,
# ~\.llm4j\tools\<version>\ (where `weave tools fetch` puts what it downloads, newest first), then a build in the repository this skill sits in.
function Tool-Dirs {
  $home_ = [Environment]::GetFolderPath('UserProfile'); if ($env:HOME) { $home_ = $env:HOME }
  $d = @()
  $d += Get-ChildItem -Path $home_ -Filter '.vscode*' -Directory -ErrorAction SilentlyContinue | ForEach-Object { Get-ChildItem -Path (Join-Path $_.FullName 'extensions') -Filter 'srijithunni7182.vscode-llm4j-loom-*' -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'bin' } }
  $d += "$SkillDir\bin"
  $tools = Join-Path $home_ '.llm4j\tools'
  if (Test-Path $tools) { $d += Get-ChildItem -Path $tools -Directory | Sort-Object LastWriteTime -Descending | ForEach-Object { $_.FullName } }
  foreach ($m in 'loom-fabric', 'loom-inspect', 'loom-inspect-core', 'ai-agent4j-loom') { $d += "$SkillDir\..\..\..\src\loom\$m\target" }
  $d
}

function Find-Jar([string]$EnvVar, [string[]]$Patterns) {
  $given = [Environment]::GetEnvironmentVariable($EnvVar)
  if ($given) { if (-not (Test-Path $given -PathType Leaf)) { Die "$EnvVar points at '$given', which is not a file" 2 }; return $given }
  $dirs = Tool-Dirs
  foreach ($p in $Patterns) { foreach ($d in $dirs) {
    if (Test-Path $d) { $hit = Get-ChildItem -Path $d -Filter $p -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc|tests' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($hit) { return $hit.FullName } }
  } }
  return $null
}
function Fabric-Jar { $j = Find-Jar 'FABRIC_JAR' @('fabric.jar', 'loom-fabric-*-all.jar'); if (-not $j) { Die "the Fabric jar was not found. Run: weave tools fetch fabric, or set FABRIC_JAR, or put the jar in $SkillDir\bin." 2 }; $j }
function Weave-Jar  { $j = Find-Jar 'WEAVE_JAR' @('weave.jar', 'ai-agent4j-loom-*-cli.jar'); if (-not $j) { Die "the weave jar was not found. Install it (jbang app install weave@srijithunni7182/llm4j, or the VS Code extension), or set WEAVE_JAR, or put the jar in $SkillDir\bin." 2 }; $j }
function Inspect-Jars {
  $a = Find-Jar 'INSPECT_JAR' @('loom-inspect.jar', 'loom-inspect-[0-9]*.jar'); $b = Find-Jar 'INSPECT_CORE_JAR' @('loom-inspect-core.jar', 'loom-inspect-core-[0-9]*.jar')
  if (-not $a -or -not $b) { Die "the loom-inspect add-on jars were not found. Run: weave tools fetch inspect, or set INSPECT_JAR and INSPECT_CORE_JAR, or put loom-inspect.jar and loom-inspect-core.jar in $SkillDir\bin." 2 }
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
