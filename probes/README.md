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

## P3 and P7. A router, tested with no model: how much to write, how long it takes

The same router on both stacks: classify a message (order status, refund or other); an order question goes to a reply agent, anything else to an agent that summarises for a person. Fake models only. `router-tests/`.

| | Loom | LangGraph (1.2.18) |
|---|---|---|
| The workflow | 18 lines (`main.loom`, including the agent and classifier declarations) | 32 lines (`router.py`) |
| Tests written by hand, 3 branches | 21 lines (`tests/router.test.yaml`), 3 cases | 27 lines (`test_router.py`), 3 cases |
| Tests written by the tool | `TestSynth` wrote 4 cases (the plain path, each classifier label, each agent failing), **0 lines by hand** | none |
| Run time | 157 ms for 3 cases (1.0 s with JVM start) | 0.34 s for 3 tests (1.9 s with Python start) |
| Step coverage | **6 of 6 steps, reported** by `weave test --coverage` | not part of the framework (a line-coverage plugin could be added) |

## P4. A retry with a 2-second backoff: can it be tested without waiting?

`retry-tests/`. A model call fails twice (a timeout), then answers; the workflow retries twice with a 2-second backoff.

- **Loom:** the real `retry 2 backoff 2s` is tested with the failure injected (`fail: { Replier: { times: 2, category: timeout } }`) on a virtual clock: two cases (retry succeeds; retries run out and the handler takes over) finished in **about 100 ms**.
- **LangChain 1.4.7:** `ModelRetryMiddleware(max_retries=2, initial_delay=2.0, backoff_factor=2.0)` on a fake model that fails twice took **6.01 s** of real time (the 2 s and 4 s waits). With `initial_delay=0` it is instant, but then the configuration under test is not the one you ship.

## P5. An approval, then the process ends, then the answer

`approval-restart/`. A person must approve before a send; the first process ends while the question is open; a later process supplies the answer.

| | Loom | LangGraph (1.2.18, SQLite checkpointer) |
|---|---|---|
| Survives the restart | Yes: the run pauses (exit code 4), a later process records the answer, the run carries on | Yes: `interrupt()` returns the question to the caller, a later process resumes the thread with `Command(resume="yes")` |
| Sends | 1; a second `weave tick` sends nothing more | 1; resuming a finished thread again sends nothing more |
| How the question reaches the person | built in: a channel (a command that receives each question as one line of JSON, or Telegram) with a code, `weave questions` and `weave answer` | the question is returned to your code; delivering it and routing the answer back is yours to build |

This one is a draw on survival and sends; the difference is the delivery.

## P6. The lethal trifecta, found before the run

`trifecta/`. One agent that reads the web, reads a folder of customer files and can send mail.

- **Loom:** `weave audit main.loom` reports `HIGH LA01 One agent holds the lethal trifecta with an open way out` (mapped to OWASP LLM01, LLM02 and LLM06), names the three tools, adds no-budget and unapproved-effect findings, and exits 1.
- **LangChain 1.4.7, LangGraph 1.2.18, langchain-core 1.7.0 (the installed packages):** we searched them for any analysis of this kind and found none (the only hits were a comment in the shell tool about exfiltration and graph-drawing notes about static analysis of declared writes). Run-time protections do exist there (for example the PII middleware); a static review of the agent before it runs was not found.

## P8. What each stack shows about a finished run, with no account

`run-visibility/`. The same two-step flow ran to the end on both.

- **Loom:** `weave timeline` (a row per step with its generation, kind, state, tokens and cost), `weave why` (each step's result in words), `weave graph --format mermaid`, all offline; with a model, `--observe` adds a live page with cost, context and incidents, and Fabric shows many runs, both free and self-hosted.
- **LangGraph:** `get_state_history` (one snapshot per step with the state values and the next node) and `get_graph().draw_mermaid()`. Per-step tokens, cost and latency are not in that history; they come from the model's usage metadata or from LangSmith tracing (which needs an account).
