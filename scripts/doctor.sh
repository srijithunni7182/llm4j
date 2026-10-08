#!/usr/bin/env bash
# Finds the stale-jar trap: a module whose sources are newer than the jar installed in the local Maven repository (or than the jar the
# extension bundles), which makes later builds and tests run against old code. Prints the command that fixes it. Changes nothing.
# Usage: scripts/doctor.sh        (exit 0: all current, exit 1: something is stale)
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
version="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$root/pom.xml" | head -n 1)"
stale=0
newest() { find "$1/src" -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -n 1 | cut -d. -f1; }
age() { date -d "@$1" '+%Y-%m-%d %H:%M' 2>/dev/null || echo "$1"; }

check_module() { # <dir> <artifact>
  local dir="$root/$1" artifact="$2" src jar
  [ -d "$dir/src" ] || return 0
  src="$(newest "$dir")"
  jar="$(ls "$repo"/io/github/*/"$artifact"/"$version"/"$artifact-$version.jar" 2>/dev/null | head -n 1)"
  if [ -z "$jar" ]; then echo "MISSING  $artifact $version is not installed in $repo"; stale=1; return; fi
  if [ "$(stat -c %Y "$jar")" -lt "${src:-0}" ]; then
    echo "STALE    $artifact: installed jar is from $(age "$(stat -c %Y "$jar")"), sources changed $(age "$src")"; stale=1
  else echo "current  $artifact"; fi
}

check_module src/ai-agent4j ai-agent4j
check_module src/eval4j eval4j
check_module src/loom/ai-agent4j-loom ai-agent4j-loom

bundled="$root/src/loom/vscode-loom/bin/weave.jar"
built="$(ls "$root"/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar 2>/dev/null | head -n 1)"
if [ -f "$bundled" ]; then
  if [ -n "$built" ] && [ "$(stat -c %Y "$bundled")" -lt "$(stat -c %Y "$built")" ]; then echo "STALE    the extension's bin/weave.jar is older than $built"; stale=1
  elif [ "$(stat -c %Y "$bundled")" -lt "$(newest "$root/src/loom/ai-agent4j-loom")" ]; then echo "STALE    the extension's bin/weave.jar is older than the Loom sources"; stale=1
  else echo "current  extension bin/weave.jar"; fi
fi

if [ "$stale" -ne 0 ]; then
  cat <<FIX

Fix (builds and installs all three modules without running their tests; -Djacoco.skip is needed with -DskipTests):
  mvn -DskipTests -Djacoco.skip=true -pl src/ai-agent4j,src/eval4j,src/loom/ai-agent4j-loom install
  cp src/loom/ai-agent4j-loom/target/ai-agent4j-loom-$version-cli.jar src/loom/vscode-loom/bin/weave.jar
FIX
  exit 1
fi
echo "All jars are current."
