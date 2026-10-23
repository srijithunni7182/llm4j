#!/usr/bin/env bash
# Regenerates test/fixtures/review/*.json from the real engine (weave review and weave graph). Never edit those files by hand.
# Needs the Loom module built: (cd ../ai-agent4j-loom && mvn -o -q compile dependency:copy-dependencies -DoutputDirectory=target/lib)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
module="$here/../ai-agent4j-loom"
cp_="$module/target/classes:$module/target/lib/*"
weave() { java -cp "$cp_" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>/dev/null; }
out="$here/test/fixtures/review"
for script in "$out"/scripts/*.loom; do
  name="$(basename "$script" .loom)"
  prices=()
  [ -f "$out/scripts/$name.prices" ] && prices=(--prices "$out/scripts/$name.prices")
  weave review "$script" --format json --no-env "${prices[@]}" > "$out/$name.review.json" || true
  weave graph "$script" > "$out/$name.graph.json" || true
  echo "$name: $(python3 -c "import json;d=json.load(open('$out/$name.review.json'));print(len(d['findings']),'findings')")"
done

# the semantic index of the editor's fixtures; the folder is written as @SCRIPTS@ so the files do not depend on where they were made
sem="$here/test/fixtures/semantic"
for script in "$sem"/scripts/*.loom "$sem"/scripts/lib/*.loom; do
  name="$(basename "$script" .loom)"
  weave symbols "$script" | sed "s#$sem/scripts#@SCRIPTS@#g" > "$sem/$name.symbols.json" || true
  echo "$name: symbols"
done
