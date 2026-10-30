#!/usr/bin/env bash
# Moves the repository between a release and the next development version, in every place that names it.
#   scripts/release-version.sh release 6.2.0        sets every pom to 6.2.0, and llm4j.released and jbang-catalog.json to 6.2.0
#   scripts/release-version.sh next 6.2.1-SNAPSHOT  sets every pom to the next -SNAPSHOT; llm4j.released stays the release
# Between releases main carries X.Y.Z-SNAPSHOT, so a jar built from main is never mistaken for the release
# (docs/PUBLISHING.md). Changes files only; it commits, tags and pushes nothing.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-}"; version="${2:-}"
case "$mode:$version" in
  release:*-SNAPSHOT) echo "a release version must not end in -SNAPSHOT: $version" >&2; exit 2 ;;
  release:[0-9]*.[0-9]*.[0-9]*|release:[0-9]*.[0-9]*) ;;
  next:*-SNAPSHOT) ;;
  *) echo "usage: $0 release <X.Y.Z> | next <X.Y.Z-SNAPSHOT>" >&2; exit 2 ;;
esac
cd "$root"
mvn -q -B versions:set -DnewVersion="$version" -DgenerateBackupPoms=false -Pextended
if [ "$mode" = release ]; then
  sed -i "s|<llm4j.released>[^<]*</llm4j.released>|<llm4j.released>$version</llm4j.released>|" pom.xml
  sed -i "s|\(\"script-ref\": \"io.github.srijithunni7182:ai-agent4j-loom:\)[^:]*\(:cli\"\)|\1$version\2|" jbang-catalog.json
fi
echo "set $version in every pom$([ "$mode" = release ] && echo ", llm4j.released and jbang-catalog.json")"
grep -n "<llm4j.released>" pom.xml
grep -n "script-ref" jbang-catalog.json
