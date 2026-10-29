#!/usr/bin/env bash
# Shared by the other scripts in this folder. Sourced, not run.
# Rules every script follows: no secret on a command line or in anything printed; nothing listens beyond this computer without TLS or a proxy;
# every step is safe to run twice and says what it did.
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$1" >&2; exit "${2:-1}"; }

need_java() {
  command -v java >/dev/null 2>&1 || die "Java 17 or newer is needed and 'java' was not found. Install a JDK, then run this again." 2
  local v
  v="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
  [ -n "$v" ] && [ "$v" -ge 17 ] 2>/dev/null || die "Java 17 or newer is needed (found version '${v:-unknown}')." 2
}

# Where the jars may be, in order (the same order as lib.ps1, and one rule for every jar):
#   1. the environment variable (FABRIC_JAR, WEAVE_JAR, INSPECT_JAR, INSPECT_CORE_JAR), handled in find_jar
#   2. the editor extension's bundled jar (weave only: it holds nothing else)
#   3. bin/ of this skill
#   4. ~/.llm4j/tools/<version>/, where `weave tools fetch` puts what it downloads (newest first)
#   5. a build in the repository this skill sits in
# A `weave` on PATH is a launcher, not a jar: scripts/weave in the skill package uses it; these scripts need the jars themselves.
tool_dirs() {
  local d
  for d in "$HOME"/.vscode*/extensions/srijithunni7182.vscode-llm4j-loom-*/bin; do [ -d "$d" ] && printf '%s\n' "$d"; done
  printf '%s\n' "$SKILL_DIR/bin"
  # ls -t, because a glob cannot order by modification time on both Linux and macOS: the newest fetched version first
  # shellcheck disable=SC2045
  for d in $(ls -dt "$HOME"/.llm4j/tools/*/ 2>/dev/null); do printf '%s\n' "${d%/}"; done
  for d in loom-fabric loom-inspect loom-inspect-core ai-agent4j-loom; do printf '%s\n' "$SKILL_DIR/../../../src/loom/$d/target"; done
}

# find_jar ENVVAR PATTERN...  -> prints the jar: the variable if set, else the first place in tool_dirs that has one (the newest file there)
find_jar() {
  local var="$1"; shift
  local given="${!var:-}"
  if [ -n "$given" ]; then [ -f "$given" ] || die "$var points at '$given', which is not a file" 2; printf '%s\n' "$given"; return 0; fi
  local pat dir hit
  for pat in "$@"; do
    while IFS= read -r dir; do
      # the pattern is a glob and the newest file wins (ls -t); the names are jar names, never anything else
      # shellcheck disable=SC2010
      hit="$(ls -t "$dir"/$pat 2>/dev/null | grep -v -E 'sources|javadoc|tests' | head -n 1 || true)"
      [ -n "$hit" ] && { printf '%s\n' "$hit"; return 0; }
    done < <(tool_dirs)
  done
  return 1
}

fabric_jar() {
  find_jar FABRIC_JAR 'fabric.jar' 'loom-fabric-*-all.jar' \
    || die "the Fabric jar was not found. Run: weave tools fetch fabric (it asks first and checks the checksum), or set FABRIC_JAR=/path/to/loom-fabric-<version>-all.jar, or put the jar in $SKILL_DIR/bin/." 2
}

weave_jar() {
  find_jar WEAVE_JAR 'weave.jar' 'ai-agent4j-loom-*-cli.jar' \
    || die "the weave jar was not found. Install it (jbang app install weave@srijithunni7182/llm4j, or the VS Code extension), or set WEAVE_JAR=/path/to/ai-agent4j-loom-<version>-cli.jar, or put the jar in $SKILL_DIR/bin/." 2
}

# the add-on's two jars, joined for a class path
inspect_jars() {
  local a b
  a="$(find_jar INSPECT_JAR 'loom-inspect.jar' 'loom-inspect-[0-9]*.jar')" || die "the loom-inspect add-on jar was not found. Run: weave tools fetch inspect, or set INSPECT_JAR and INSPECT_CORE_JAR, or put loom-inspect.jar and loom-inspect-core.jar in $SKILL_DIR/bin/." 2
  b="$(find_jar INSPECT_CORE_JAR 'loom-inspect-core.jar' 'loom-inspect-core-[0-9]*.jar')" || die "the loom-inspect-core jar was not found (see INSPECT_CORE_JAR)." 2
  printf '%s:%s\n' "$a" "$b"
}

# a class path separator for the shell that runs this: ':' here; the .ps1 scripts use ';'
is_loopback() {
  case "$1" in 127.*|localhost|::1|'[::1]') return 0 ;; *) return 1 ;; esac
}

http_get() { # URL -> body on stdout, nonzero on failure; certificates are not checked: only ever used on this computer's own address
  if command -v curl >/dev/null 2>&1; then curl -fsS -k --max-time 3 "$1"; else die "curl is needed for the health check" 2; fi
}

wait_healthy() { # URL SECONDS
  local url="$1" n="${2:-30}" i
  for i in $(seq 1 "$n"); do
    if http_get "$url/api/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

pid_alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

random_secret() { # LENGTH -> letters and digits, from the system's random source (head closes the pipe: that is not an error)
  ( set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}" )
}
