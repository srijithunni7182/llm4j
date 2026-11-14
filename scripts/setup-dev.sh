#!/usr/bin/env bash
# Gets a checkout ready for the working loop, so the first build of a session does not stall or run against stale artifacts:
#   1. full git history     (scripts/about-stats.sh and `git log` need it; a cloud checkout is shallow)
#   2. the A2A test kit (a test dependency of the core), the core, the tools module, Loom and loom-inspect-core installed from THIS checkout into ~/.m2: loom-inspect, loom-fabric and the
#      extension's real-engine tests compile and run against the installed jars, which are otherwise whatever an earlier
#      build left (a different commit, or the published release), and fail in confusing ways
#   3. the VS Code extension: its dependencies, its test build, and bin/weave.jar from the Loom build above
#
# Safe to run again; each step is skipped when it has nothing to do. Takes about a minute with a warm Maven cache.
# Usage: scripts/setup-dev.sh     (or: make setup)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

step() { echo "== $*"; }

if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
  step "fetching the full git history"
  git fetch -q --unshallow origin || echo "   could not fetch the full history; scripts/about-stats.sh will refuse to run" >&2
fi

step "installing the A2A test kit, the core, the tools module, eval4j, eval4j-report, Loom and loom-inspect-core from this checkout"
mvn -q -B -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true \
  -pl src/a2a-testkit,src/ai-agent4j,src/tools,src/tools/ai-agent4j-tools,src/eval4j,src/eval4j-report,src/loom/ai-agent4j-loom,src/loom/loom-inspect-core install

ext=src/loom/vscode-loom
if command -v node > /dev/null 2>&1 && [ -f "$ext/package.json" ]; then
  step "the VS Code extension: dependencies, test build and bin/weave.jar"
  (cd "$ext" && npm ci --no-audit --no-fund --loglevel=error && npx tsc -p tsconfig.test.json)
  mkdir -p "$ext/bin"
  cp src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar "$ext/bin/weave.jar"
else
  echo "== the VS Code extension: skipped (Node is not installed)" >&2
fi

echo "Ready. Next: make test-core after each sub-phase, make docs-check after a change to docs, commands, starters or review rules."
