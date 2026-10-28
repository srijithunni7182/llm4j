#!/usr/bin/env bash
# Probe P1, Loom side: kill the process right after a send, resume, and count the sends.
# usage: run.sh [plain|idempotent] [early|late]   (early: die right after the send; late: die in the next step, after the send was recorded)      (needs the Loom module built: target/classes and target/lib)
set -u
KIND=${1:-plain}
WHEN=${2:-early}
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
WORK=$(mktemp -d)
mkdir -p "$WORK/classes/META-INF/services"
CLS=$([ "$KIND" = idempotent ] && echo probe.SendTask\$Idempotent || echo probe.SendTask\$Plain)
javac -cp "$CP" -d "$WORK/classes" "$HERE/src/probe/SendTask.java" 2>&1 | grep -v "Picked up"
printf "%s\nprobe.SendTask\$Boom\n" "$CLS" > "$WORK/classes/META-INF/services/io.github.llm4j.agent.task.Task"
export PROBE_SENDS="$WORK/sends.log"
weave() { java -cp "$CP:$WORK/classes" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive"; }

echo "== run 1: the process dies ($WHEN) =="
[ "$WHEN" = early ] && export CRASH_AFTER_SEND=1 || export CRASH_LATE=1
java -cp "$CP:$WORK/classes" io.github.llm4j.loom.cli.WeaveCLI run "$HERE/main.loom" --journal "$WORK/journal" --classes "$WORK/classes" >/dev/null 2>&1
RC=$?; unset CRASH_AFTER_SEND CRASH_LATE
echo "exit code: $RC   sends so far: $(wc -l < "$WORK/sends.log")"
echo "== run 2: resume the same run =="
weave resume "$WORK/journal" | grep -i -E "unknown|completed|failed|error:" | head -3
echo "sends after resume: $(wc -l < "$WORK/sends.log")"
echo "keys sent: $(tr '\n' ' ' < "$WORK/sends.log")"
rm -rf "$WORK"
