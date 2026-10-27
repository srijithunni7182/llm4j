#!/usr/bin/env bash
# Collects what a release publishes for the direct install: weave.jar (the fat CLI jar), the two
# launchers, and a checksum file. Usage: scripts/make-release-launchers.sh [outdir]
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/dist/launchers}"
jar=$(ls "$root"/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -n 1)
mkdir -p "$out"
cp "$jar" "$out/weave.jar"
cp "$root/install/weave" "$root/install/weave.cmd" "$out/"
(cd "$out" && sha256sum weave.jar > weave.jar.sha256)
echo "wrote $out"
