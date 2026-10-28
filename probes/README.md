# Probes: claims you can check

Each probe runs the same scenario on Loom and on LangGraph/LangChain with **fake models and fake tools**, so it costs no API money and anyone can rerun it. They back the statements on [Why teams choose llm4j and Loom](../docs/WHY_LLM4J.md). Results below were produced on 2026-10-28.

Versions: llm4j (this repository, `main`), LangGraph 1.2.18, langgraph-checkpoint-sqlite 3.1.2, LangChain 1.4.7, Python 3.13, Java 17+.

## P1. A crash right after a send: is the send repeated on resume?

A task or node appends a line to a file (the "send"), and the process is killed (`halt(137)` / `os._exit(137)`) at a chosen moment. Then the run is resumed from its journal (Loom) or its SQLite checkpoint (LangGraph), and the sends are counted.

| When the process dies | Loom (not idempotent task, the default) | Loom (idempotent task) | LangGraph, `durability="sync"` | LangGraph, default | LangGraph, `durability="async"` |
|---|---|---|---|---|---|
| **Right after the send, before it is recorded** | **1 send.** The step is refused on resume: "Send may already have run: an earlier attempt's outcome is unknown ... Check whether it happened, then resume the step with a retry." | 2 sends **with the same idempotency key** (the runtime supplies it; a receiver that deduplicates sees one) | **2 sends** (the node runs again) | **2 sends** | **2 sends** |
| **In the next step, after the send was recorded** | **1 send** | **1 send** | **1 send** | **2 sends** | **2 sends** |

Reading it:

- Where the effect has happened and nothing yet records it, **LangGraph runs the node again in every mode**, which is what its documentation says ("side effects before an interrupt should (ideally) be idempotent"). Loom either refuses to run it again (the default, `on_unknown: skip`) or runs it again with the **same** key it supplies (`idempotent`).
- After the node's work was recorded, LangGraph did not repeat the send only with `durability="sync"`. With its default (which behaved like `"async"` in this version) the checkpoint had not been written when the process died, so the send was repeated.
- `durability="exit"` writes nothing until the graph exits, so there was nothing to resume from.
- With a key of your own (thread id + node) written with each send, a LangGraph receiver can deduplicate too; the framework does not supply or check one. Run `langgraph/run.sh keyed` to see the keys.

Run it:

```
# Loom side (needs the Loom module built: target/classes and target/lib)
probes/crash-resume/loom/run.sh plain early      # also: idempotent, late
# LangGraph side
python3 -m venv venv && venv/bin/pip install -r probes/crash-resume/langgraph/requirements.txt
PROBE_PYTHON=venv/bin/python probes/crash-resume/langgraph/run.sh plain early      # also: keyed, late
LANGGRAPH_DURABILITY=sync PROBE_PYTHON=venv/bin/python probes/crash-resume/langgraph/run.sh plain late
```

## P2. A token budget: does a model call start after the budget is spent?

Loom: `probes/budget/loom` has a script with `budget { tokens: 1000 }` and three questions to an agent whose scripted answers each report 600 tokens. `weave test` asserts that **two** calls happen and the run ends `budget`: no third call starts once the budget is spent (passes in about 150 ms, no key).

LangChain 1.4.7: the built-in limit middleware are `ModelCallLimitMiddleware(thread_limit, run_limit, exit_behavior)` and `ToolCallLimitMiddleware(tool_name, thread_limit, run_limit, exit_behavior)`. Both count calls. Nothing in `langchain.agents.middleware` takes a token or a cost limit (the installed package has `model_call_limit`, `tool_call_limit`, `summarization` (a context-size trigger) and no budget module). A token or dollar cap would be middleware you write.

```
weave test probes/budget/loom/main.loom
```

## Not yet probed

Router tests without a model (lines and time), failure injection without waiting, an approval that survives a restart, the static lethal-trifecta finding, and what each stack shows about a run without a paid service.
