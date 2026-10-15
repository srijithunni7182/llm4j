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

# find_jar ENVVAR PATTERN...  -> prints the jar: the variable if set, else bin/ of this skill, else a build in the repository this skill sits in
find_jar() {
  local var="$1"; shift
  local given="${!var:-}"
  if [ -n "$given" ]; then [ -f "$given" ] || die "$var points at '$given', which is not a file" 2; printf '%s\n' "$given"; return 0; fi
  local pat dir hit
  for pat in "$@"; do
    for dir in "$SKILL_DIR/bin" "$SKILL_DIR/../../../src/loom/loom-fabric/target" "$SKILL_DIR/../../../src/loom/loom-inspect/target" "$SKILL_DIR/../../../src/loom/loom-inspect-core/target" "$SKILL_DIR/../../../src/loom/ai-agent4j-loom/target"; do
      hit="$(ls "$dir"/$pat 2>/dev/null | grep -v -E 'sources|javadoc|tests' | head -n 1 || true)"
      [ -n "$hit" ] && { printf '%s\n' "$hit"; return 0; }
    done
  done
  return 1
}

fabric_jar() {
  find_jar FABRIC_JAR 'fabric.jar' 'loom-fabric-*-all.jar' \
    || die "the Fabric jar was not found. Put it in $SKILL_DIR/bin/ or set FABRIC_JAR=/path/to/loom-fabric-<version>-all.jar (build: mvn -DskipTests -pl src/loom/loom-fabric -am package)." 2
}

weave_jar() {
  find_jar WEAVE_JAR 'weave.jar' 'ai-agent4j-loom-*-cli.jar' \
    || die "the weave jar was not found. Put it in $SKILL_DIR/bin/ or set WEAVE_JAR=/path/to/ai-agent4j-loom-<version>-cli.jar." 2
}

# the add-on's two jars, joined for a class path
inspect_jars() {
  local a b
  a="$(find_jar INSPECT_JAR 'loom-inspect.jar' 'loom-inspect-[0-9]*.jar')" || die "the loom-inspect add-on jar was not found. Put loom-inspect.jar and loom-inspect-core.jar in $SKILL_DIR/bin/ or set INSPECT_JAR and INSPECT_CORE_JAR." 2
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
