#!/usr/bin/env bash
# Probe P5, Loom side: a person must approve before a send; the process ends while the question is open;
# a later process records the answer and the run carries on. Counts the sends. (needs the Loom module built)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
WORK=$(mktemp -d)
mkdir -p "$WORK/classes/META-INF/services" "$WORK/store"
javac -cp "$CP" -d "$WORK/classes" "$HERE/../../crash-resume/loom/src/probe/SendTask.java" 2>&1 | grep -v "Picked up"
echo 'probe.SendTask$Plain' > "$WORK/classes/META-INF/services/io.github.llm4j.agent.task.Task"
export PROBE_SENDS="$WORK/sends.log" ASK_LOG="$WORK/asked.log"; : > "$PROBE_SENDS"
# the channel is a program that receives each question as one line of JSON (here it just writes it to a file)
printf '{ "channel": "command", "command": ["/bin/sh", "-c", "cat >> \\"$ASK_LOG\\""], "chats": { "default": [1] } }\n' > "$WORK/store/channel.json"
weave() { java -cp "$CP:$WORK/classes" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|HarnessExecutor"; }

echo "== process 1: runs until the question, then ends"
java -cp "$CP:$WORK/classes" io.github.llm4j.loom.cli.WeaveCLI run "$HERE/main.loom" --journal "$WORK/j" --store "$WORK/store" --ask-via command --classes "$WORK/classes" >"$WORK/run1.out" 2>&1
echo "exit code: $?   sends: $(wc -l < "$PROBE_SENDS")   question sent to the channel: $(grep -c question "$ASK_LOG" 2>/dev/null)"
CODE=$(weave questions "$WORK/store" | grep -o -E '^[A-Z0-9]{6}|\[[A-Z0-9]{6}\]' | head -1 | tr -d '[]')
echo "waiting question code: ${CODE:-none}"
echo "== process 2 (a new process, later): the person answers, the run carries on"
weave answer "$WORK/store" "${CODE:-X}" yes | tail -2
weave tick "$WORK/store" | tail -3
echo "sends after the answer: $(wc -l < "$PROBE_SENDS")"
echo "== process 3: tick again, does anything run twice?"
weave tick "$WORK/store" | tail -2
echo "sends at the end: $(wc -l < "$PROBE_SENDS")"
rm -rf "$WORK"
