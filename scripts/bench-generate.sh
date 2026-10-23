#!/usr/bin/env bash
# Live benchmark of `weave generate` on the 30 descriptions. Costs money: it calls the model in
# $LOOM_GENERATE_MODEL for each description. Run by hand; it records a transcript per description
# that GenerateBenchmarkTest replays offline.
#   usage: scripts/bench-generate.sh MODEL PRICES_FILE [MAX_COST_EACH] [FIRST] [LAST]
set -u
MODEL=${1:?model}; PRICES=${2:?prices file}; CAP=${3:-0.30}; FIRST=${4:-1}; LAST=${5:-30}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MOD=$ROOT/src/loom/ai-agent4j-loom
RES=$MOD/src/test/resources/generate
OUT=${BENCH_OUT:-/tmp/bench-generate}
mkdir -p "$OUT"
passed=0; ran=0
for i in $(seq "$FIRST" "$LAST"); do
  id=$(printf 'd%02d' "$i")
  rm -rf "$OUT/$id" "$RES/transcripts/$id.jsonl"
  java -cp "$MOD/target/classes:$MOD/target/lib/*" io.github.llm4j.loom.cli.WeaveCLI generate \
    "$(cat "$RES/descriptions/$id.txt")" --model "$MODEL" --allow-model "$MODEL" --prices "$PRICES" \
    --max-cost "$CAP" --no-ask --flat --keep-failing --out "$OUT/$id" \
    --record "$RES/transcripts/$id.jsonl" > "$OUT/$id.log" 2>&1
  code=$?; ran=$((ran+1)); [ $code -eq 0 ] && passed=$((passed+1))
  echo "$id exit=$code $(grep -ac 'attempt' "$OUT/$id.log") attempt-lines"
done
echo "passed $passed of $ran; logs in $OUT"
