#!/usr/bin/env bash
# Probe P8: what each stack shows about a finished run with only the open-source parts and no account.
# The same two-step flow (a send, then a step) runs to the end on both; then we list what can be read afterwards.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
PY=${PROBE_PYTHON:-$HERE/../crash-resume/langgraph/venv/bin/python}
WORK=$(mktemp -d); mkdir -p "$WORK/classes/META-INF/services"
javac -cp "$CP" -d "$WORK/classes" "$HERE/../crash-resume/loom/src/probe/SendTask.java" 2>&1 | grep -v "Picked up"
printf 'probe.SendTask$Plain\nprobe.SendTask$Boom\n' > "$WORK/classes/META-INF/services/io.github.llm4j.agent.task.Task"
export PROBE_SENDS="$WORK/sends.log"
weave() { java -cp "$CP:$WORK/classes" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|HarnessExecutor"; }
weave run "$HERE/../crash-resume/loom/main.loom" --journal "$WORK/j" --classes "$WORK/classes" >/dev/null
echo "################ Loom: weave timeline (a finished run)"; weave timeline "$WORK/j"
echo "################ Loom: weave why (each choice the run made)"; weave why "$WORK/j" | head -12
echo "################ Loom: weave graph --format mermaid"; weave graph "$HERE/../crash-resume/loom/main.loom" --format mermaid | head -12
echo "################ LangGraph: the same flow, what is readable afterwards"
HERE="$HERE" "$PY" - "$WORK" <<'PYEOF'
import importlib.util, os, sqlite3, sys
work, here = sys.argv[1], os.environ["HERE"]
spec = importlib.util.spec_from_file_location("probe", os.path.join(here, "..", "crash-resume", "langgraph", "probe.py"))
probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)
from langgraph.checkpoint.sqlite import SqliteSaver
saver = SqliteSaver(sqlite3.connect(os.path.join(work, "cp.db"), check_same_thread=False))
graph = probe.build(os.path.join(work, "lg-sends.log"), False, "t").compile(checkpointer=saver)
config = {"configurable": {"thread_id": "t"}}
graph.invoke({}, config)
print("get_state_history (one snapshot per step, newest first):")
for s in graph.get_state_history(config):
    print("  step", s.metadata.get("step"), "next:", s.next, "values:", s.values)
print("draw_mermaid:"); print("\n".join("  " + l for l in graph.get_graph().draw_mermaid().splitlines()[:8]))
print("tokens / cost / latency per step: not in the state history (they come from the model's usage metadata or from LangSmith tracing, which needs an account)")
PYEOF
rm -rf "$WORK"
