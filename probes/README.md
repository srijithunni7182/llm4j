# Probes: claims you can check

Each probe runs the same scenario on Loom and on LangGraph/LangChain with **fake models and fake tools**, so it costs no API money and anyone can rerun it. They back the statements on [Why teams choose llm4j and Loom](../docs/WHY_LLM4J.md). Results below were produced on 2026-10-28.

Versions: llm4j (this repository, `main`), LangGraph 1.2.18, langgraph-checkpoint-sqlite 3.1.2, LangChain 1.4.7, Python 3.13, Java 17+. Probes P9 to P17 (2026-11-04) also use the **Python** Google ADK 2.13.0 (`google-adk`) and langchain-core 1.7.4, pinned in `probes/requirements.txt` and in each probe's `python/requirements.txt`.

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

---

## P9 to P17: the architecture mistakes, on Loom, LangGraph, LangChain and Google ADK

These nine probes back [Architecture you can review](../docs/REVIEWABLE_ARCHITECTURE.md). Each runs the same scenario with **fake models and fake tools**, no keys and no API money, on the pinned versions above. The Python side of each is `probes/<folder>/python/probe.py`, the Loom side `probes/<folder>/loom/run.sh`; `probes/run-frameworks.sh` reruns them all and stores what they print in each folder's `results/` (`python.md`, `loom.txt`). A finding is reported as "found no ..." only after a search of the installed packages (`_common/fakes.py` `search`), with the number of matching files shown. Where another framework does something well, the row says so. "Code you write" means the framework offers a hook and the rule is yours.

```
python3 -m venv venv && venv/bin/pip install -r probes/requirements.txt
PROBE_PYTHON=venv/bin/python probes/run-frameworks.sh            # all nine; or name folders
```

### P9. A utility step that pulls fields out of text: can it be given only its inputs?

`extractor-isolation/`. A chatty step runs first; then a step must read the fields of an invoice from the text. The fake models record every request.

| | Result |
|---|---|
| **Loom** | The `extractor` request did not contain the earlier step's output. The same job written as an `agent` did (`request 2 ... = true`). `weave review` reports the agent form as **LP09** before anything runs. |
| **LangGraph** | A node that passes only the text: the earlier output was not in its request. The same node written to pass the graph's messages: it was. Compiling raised no warning either way. |
| **LangChain** | `create_agent` receives the messages it is given; handed the previous agent's messages, the earlier output was in its request. Handing it only the text is a call you write. |
| **Google ADK** | In a `SequentialAgent`, `include_contents='none'` removed the user's message but **the previous agent's output still arrived**. An agent wrapped as an `AgentTool` ran on only the arguments of the call. |

Reading it: every framework can give a step only its inputs, with code you write or (ADK) by wrapping the agent as a tool. In none did anything notice before the run that a utility step was not isolated; a search of the four packages for least-privilege or stateless steps found 0 files. Full output: [python](extractor-isolation/results/python.md), [Loom](extractor-isolation/results/loom.txt).

### P10. A step that uses tools and must answer in a large nested schema

`large-schema-with-tools/`. An agent with one tool and an `Order` schema (nested objects, a list of objects, eight fields).

| | Result |
|---|---|
| **Loom** | `weave review` reports **LP10** (medium): the agent reasons (it has tools) and must answer in a schema with a nested object. |
| **LangChain** | `create_agent(tools=[...], response_format=Order)` built with 0 warnings. |
| **LangGraph** | A node that binds a tool and is expected to answer in `Order` compiled with 0 warnings; the schema lives in the node's code. |
| **Google ADK** | `LlmAgent(tools=[...], output_schema=Order)` was accepted with 0 warnings. |

A search of the installed packages for a warning about large or nested schemas, or about reasoning and a schema together, found 0 files. Full output: [python](large-schema-with-tools/results/python.md), [Loom](large-schema-with-tools/results/loom.txt).

### P11. A reply that breaks a written rule: can it leave without a check?

`exit-check/`. The rule is "never promise a refund"; the reply promises one; an agent also holds a sending tool.

| | Result |
|---|---|
| **Loom** | With a policy declared, `weave check` refuses `unchecked.loom` before it runs: a value written by a model reaches the `return` without a check against `HouseRules`. `checked.loom` and `sending-agent.loom` pass. An agent's own calls to a changing tool are checked by the runtime (the repository's `ToolCallPolicyTest`; not part of this probe's output). |
| **LangGraph** | A graph with no check: the rule-breaking reply was the graph's output; the sending tool call in a prebuilt `ToolNode` ran with nothing in between. 0 warnings. |
| **LangChain** | The model's sending tool call ran (`sends = 1`). With a `wrap_tool_call` middleware that we wrote, it was blocked. The package exports `before_agent, before_model, wrap_model_call, after_model, wrap_tool_call, after_agent` hooks. |
| **Google ADK** | The sending tool call ran (`sends = 1`). With a `before_tool_callback` that we wrote, it was blocked. The package also ships a Model Armor integration (a Google Cloud screening service; not run here). |

Reading it: all three have a place to put a check. The rule, and the decision to install it, are code you write, and a search found 0 files that analyse whether a workflow has an exit with no check before it. Full output: [python](exit-check/results/python.md), [Loom](exit-check/results/loom.txt).

### P12. A sending tool that needs a person's yes: where does the run pause?

`approval-placement/`.

| | Result |
|---|---|
| **Loom** | `use Notify(...) approve "..."` asks once, about the exact arguments, at the edge: `weave test` passes both the yes and the no. An `approve:` on the agent (the question inside its tool loop) is reported by `weave review` as **LH05** when the workflow is scheduled. `weave review` reports **LH03** for a script that can put twelve questions to a person (the limit is 10). It does not yet count `use ... approve` steps. |
| **LangGraph** | `interrupt()` inside the node that drafts and sends: the run paused with nothing sent; after the answer the node **started again from its first line**, so the model was called again and the text sent was `'draft two'` while the person was shown `'draft one'`. |
| **LangChain** | `HumanInTheLoopMiddleware(interrupt_on={'send_email': True})` paused before the tool call, inside the agent's loop; after an approving decision the tool ran once. |
| **Google ADK** | `FunctionTool(..., require_confirmation=True)` emitted a confirmation request and the tool had not run. |

Nothing in the three frameworks counted the questions a run can raise before it runs (a search for that found 0 files). Full output: [python](approval-placement/results/python.md), [Loom](approval-placement/results/loom.txt).

### P13. A guard that enforces a rule: can it be used with no test of it?

`untested-guard/`.

| | Result |
|---|---|
| **Loom** | `weave check` refuses an evaluator with no case that breaks a rule of its policy and no clean case; with both it passes. |
| **LangChain** | A guardrail written as `after_model` middleware, with no test anywhere: built and run with 0 warnings. |
| **LangGraph** | A guard node, with no test: compiled and run with 0 warnings. |
| **Google ADK** | A guardrail as an `after_model_callback`, with no test: built and run with 0 warnings. ADK ships an evaluation framework (`google.adk.evaluation`); nothing connects it to the build of an agent. |

A search found 0 files with a check that a guard has a test before it is used. Full output: [python](untested-guard/results/python.md), [Loom](untested-guard/results/loom.txt).

### P14. A tool that posts: is "it changes something" stated once and honoured?

`tool-effects/`. The tool posts and then times out, so the caller does not know it posted.

| | Result |
|---|---|
| **Loom** | `effect: changes` on the tool. `weave check` refuses `retry 2` on a step that uses it ("not marked safe to repeat"), and accepts the step with `approve` and no retry. |
| **LangChain** | With `metadata={'changes_something': True}` on the tool and `ToolRetryMiddleware`: the post happened **2 times**. The metadata is free-form and not read. |
| **LangGraph** | A node under `RetryPolicy(max_attempts=3)`: the post happened **2 times**. A node has no such property. |
| **Google ADK** | Under `ReflectAndRetryToolPlugin(max_retries=2)`: the post happened **2 times**. `FunctionTool` has `require_confirmation`, which was not set in this run. |

Full output: [python](tool-effects/results/python.md), [Loom](tool-effects/results/loom.txt).

### P15. One agent's invented amount handed to an agent that pays

`handoff-check/`.

| | Result |
|---|---|
| **Loom** | `weave review` reports **LR11** (medium): the paying agent acts on what the planner wrote, and nothing checks it on the way. With an `extractor` and a limit in code between the two agents it reports none. |
| **LangGraph** | planner to payer with nothing between: the payer paid the amount the planner made up. 0 warnings. |
| **LangChain** | Two `create_agent` agents: the paying tool ran with nothing between them (the payer is a scripted model that repeats the amount). 0 warnings. |
| **Google ADK** | `SequentialAgent` with `output_key`: same result, same caveat. 0 warnings. |

A search for an analysis of whether a hand-off is checked found 0 files. Full output: [python](handoff-check/results/python.md), [Loom](handoff-check/results/loom.txt).

### P16. A long agent loop that runs past its context window

`long-loop-progress/`.

| | Result |
|---|---|
| **Loom** | Starter 16: the progress is a typed variable the script owns; the worker has no transcript. `weave test` passes the three scenarios (progress carried from round to round, the bound, a failing worker). A resume of a journaled run is measured in P1, not repeated here. |
| **LangChain** | `SummarizationMiddleware` compacted the messages (4 left after 7 model calls); a field added through `state_schema` and an `after_model` hook that you write was untouched (`progress = 7`). After a crash at model call 4 the checkpoint held `progress = 3` and `invoke(None, config)` finished with 7. |
| **LangGraph** | A loop whose compact node removed all but two messages ended with 2 messages and `progress = 6`. |
| **Google ADK** | A tool writing `tool_context.state['progress']` left `progress = 6` in the session state. ADK also has a context-compaction setting on an `App`, which this probe did not run. |

So every framework can keep progress outside the messages, with a state field or tool you write. Full output: [python](long-loop-progress/results/python.md), [Loom](long-loop-progress/results/loom.txt).

### P17. A planner told to route a task to a privileged agent

`route-allowlist/`.

| | Result |
|---|---|
| **Loom** | `weave check` refuses `delegate "..." to {plan.owner}` when nothing bounds it and an agent holds a changing tool. With `in [Worker]` it passes, and `weave test` shows a route outside the list **refused** (the run fails, the privileged agent is not called). |
| **LangGraph** | `Command(goto=<the model's reply>)`: the privileged node ran. With `add_conditional_edges` and a list of allowed targets, the same reply was refused at run time with a `KeyError`. |
| **LangChain** | A supervisor whose tools are `[worker_tool]`: a call to `privileged_tool` ran nothing, and the model was told it is not a valid tool. |
| **Google ADK** | With `sub_agents=[worker, privileged]` the transfer to `privileged` ran. With `sub_agents=[worker]` only, it was refused at run time (`ValueError: Transfer target agent 'privileged' not found`). |

Reading it: in LangChain and ADK the allowed routes are the tools or agents you wired, which is an allow-list by construction; in LangGraph it is a choice you make when you write the edge. None of the builds reported a route that depended on a model's word. Full output: [python](route-allowlist/results/python.md), [Loom](route-allowlist/results/loom.txt).
