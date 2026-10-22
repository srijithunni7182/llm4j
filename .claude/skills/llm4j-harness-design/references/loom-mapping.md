# From the design to Loom

Each harness decision, the Loom construct that carries it, and the `weave guide` chapter where the `llm4j-workflow-guide` skill
builds it. Before writing any construct into a script, check its exact form with `weave guide loom` and `weave <command> --help`;
this table names constructs, it does not define them.

## Shape and boundary (stages 1 to 3)

| Decision | In Loom | Built in |
|---|---|---|
| One job per agent, prompts in files, a temperature with a reason | `agent` blocks; `prompt: "id"` with files in `prompts/` | `weave guide 1`, `weave guide 6` |
| Mandatory, exact or money-moving steps run by code | a task (`io.github.llm4j.agent.task.Task`) run with `run Name(...) -> result`; effect `NONE`, `READS` or `CHANGES` | `weave guide 6` ("Tasks") |
| A capability the agent may choose to call | a custom tool (`io.github.llm4j.agent.Tool`), mapped in a `.loot` file, listed in the agent's `tools: [...]` | `weave guide 6` |
| A step that only chooses a label (route, judge, rate) | an `intentclassifier` and `intentclassify`; `confidence at least N else escalate` turns a doubtful answer into `unsure`, which goes to a person; the text is data, the label never approves an effect alone | `weave guide loom` ("Classifying") |
| Dynamic work as a plan from a declared menu | a planner agent with `expecting { ... }`, then `for each` / `delegate ... to {task.kind}`; an undeclared agent name stops the run | `weave guide loom` ("for each"), `weave guide 6` |
| Schemas at every hand-off | `expecting { ... }` per step; `output_schema` per agent | `weave guide loom` ("Per-Step Schemas", "Output Schema Enforcement") |
| Least reach per tool | built-in tool options (`allow_paths`, `methods`, allowed programs, a read-only `sql` user); `reach: reads` in the script or `X.reach = reads` in the `.loot` file | `weave guide 7`, `weave guide 9` ("Built-in tools when deployed") |
| The lethal trifecta split, or accepted in writing | split agents joined by `expecting`; or `trifecta: accepted "<who looked, when, why>"` in `<script>.inspect`; found by `weave audit` | `weave guide 7`; deployer `weave guide d2` |
| Approval before an action | `requiresApproval` on the tool and `approve: [Name]` in the script; or `human_prompt "..." -> answer` then `alt (answer == "...")` before a `run` step | `weave guide 6`, `weave guide 9` |
| An approval that shows it was read (irreversible actions) | `human_prompt` that asks for the amount or id and an `alt` that compares it; approvals over `--ask-via telegram` need the code in the reply | `weave guide loom` ("Human-in-the-Loop", "Answering from Your Phone") |
| Secrets | `env.NAME` or `secret.NAME` (a literal is refused); `weave secrets create`, `weave secrets set NAME`; a `SecretStore` host for a vault | `weave guide 9` ("Keys and secrets") |
| Personal data | `guard { pii: mask }` or `pii: block` on the agent | `weave guide loom` ("PII Guardrails") |
| Rules every agent shares, kept stable | `shared_prompt: "..."` or `shared_prompt: prompt "id"` | `weave guide 6` |

## Loop, state and context (stages 4 and 5)

| Decision | In Loom | Built in |
|---|---|---|
| A maximum on every loop, and an outcome when reached | `loop until (cond) max N { ... } on_exhausted { ... }` | `weave guide loom` ("Bounded Loops") |
| Agent iteration and spend bounds | `max_iterations` and an agent `budget { ... }` | `weave guide loom` ("Cost Budgets") |
| Run budget, warning, refilling windows | `budget { tokens ... calls ... warn_at ... }`, `per day` windows, `when_exhausted: stop / suspend / ask`; `--max-tokens`, `--max-calls`, `--max-cost` | `weave guide 9`, `weave guide loom` ("Where a budget bites") |
| Rate limits as pauses | `rate_limits { on_limit: suspend  max_wait: ...  max_resumes: ... }` | `weave guide loom` ("Pausing and Resuming on Limits") |
| Behaviour rules for a looping agent | `supervise Agent { when looping(n) ... when tool_failing(Tool, n) ... when context > N% ... when steps > N ... }` | `weave guide 15` |
| Durable runs and crash recovery | a run journal (`--journal <dir>`; `FileRunJournal` or `JdbcRunJournal` from Java); `weave timeline <dir>` | `weave guide 9`, `weave guide loom` ("Durable Runs") |
| Idempotent effects and the crash window | a task with an idempotent effect policy and an `idempotencyKey()`; `effect_pending` / `effect_done` in the journal; `weave check` refuses `retry` on a non-idempotent `CHANGES` task | `weave guide loom` ("Failure, retries and crashes") |
| Points worth returning to | `checkpoint Name starting with ...`; `rewind to Name when (...) at most N times carrying ... side effects: ask first / keep / repeat`; `--max-rewinds`. A rewind cannot leave `parallel` or a `for each` body, nor point forward; `weave check` refuses it | `weave guide loom` ("Checkpoints, Rewind and Fork") |
| Waiting for a person without a thread | a `HumanInterface` that throws `RunSuspended`; `journal.answer(stepId, answer)`; run again | `weave guide 9` ("Plugging the host into the workflow") |
| A context plan for agents that loop on tools | a `context { compact: chain(trim_tool_results, drop_oldest) ... }` block; `summarize` only with a `summarizer` model | `weave guide 14` |
| Each model's window | a `<script>.windows` file or the shipped catalogue; never in the script | `weave guide 14` |
| A bound on the shared transcript every agent receives by default | `memory { max_tokens: N  compact: drop_oldest }` on the agent; an agent with a `context` block and a known window bounds it to a quarter of its window | `weave guide loom` ("Bounding Loom's shared transcript") |
| Harness-owned memory across steps | Engram: `executor.setMemoryEngine(engram)` from a Java host | `weave guide loom` ("Memory, Voice and Languages") |

## Concurrency and composition (stage 4, principles §15)

| Decision | In Loom | Built in |
|---|---|---|
| Independent steps side by side | `parallel { ... }`; execution waits for every branch | `weave guide loom` ("Concurrency Example") |
| The same question to several agents, results combined | `broadcast`; each agent's `output_schema` checks its answer | `weave guide loom` ("The Primitive Set") |
| One branch per item of a list | `parallel for each item in list { ... }`; cap the list where the plan is checked | `weave guide loom` ("for each") |
| What happens to the other branches when one is stopped | `intervention { sibling: ... }` (and `on_stopped` per step) | `weave guide 15` |
| Pauses and budgets across branches | branches that can still work finish and are journaled, then the run pauses once (limits, sleeps); all branches spend from one `budget` | `weave guide loom` ("Pausing and Resuming on Limits") |
| Checks and rewinds around a fan-out | `checkpoint` before the fan-out, the combined check and `rewind` after it; `retry` and `on_failure` inside branches; no `return` inside `parallel` or `for each` | `weave guide loom` ("Checkpoints, Rewind and Fork", "Ending a run") |
| A durable wait | `sleep 5m` (pauses with a journal when over 30 seconds; does not count against `budget` or `max_resumes`); something must wake the run (`weave daemon`, `weave triggers install`, `weave tick`, or `--wait`) | `weave guide loom` ("Sleeping") |
| Something that repeats on a clock | `schedule`, not `sleep` | `weave guide loom` ("Schedules and Triggers") |
| A sub-workflow with its own scope | `call Name(arg = value) -> result`; it hands back the variable `result`; names are one flat namespace across files; `budget` in the entry file only | `weave guide 6` ("When the script gets long") |
| End this branch by passing control on | `handoff "..." to Agent` | `weave guide loom` ("The Primitive Set") |
| A guard around a stretch of steps | `guardrail (PII) { ... } on_violation { ... }` (PII only; use an agent's `guard { }` otherwise) | `weave guide loom` ("PII Guardrails") |
| Named points in the trace; reasons on a path | `observe "label" {var}`; `note "..."` | `weave guide loom` ("Observability") |

## Failure and people (stages 6 and 7)

| Decision | In Loom | Built in |
|---|---|---|
| Retry what is safe, with backoff and a timeout | `retry N backoff 2s timeout 90s` on a step | `weave guide loom` ("Retry Backoff and Timeouts") |
| Another path when a step fails | `on_failure { ... }` with `_error` in scope; `handoff ... to ...` | `weave guide 6` |
| A failure that carries its reason into the next attempt | `on_failure { rewind to start at most 1 time carrying hint = "{_error}" }` | `weave guide loom` ("Checkpoints, Rewind and Fork") |
| Stop the run when a stage cannot go on | `halt "reason"` | `weave guide loom` ("Ending a run") |
| Hand back a result; check a deterministic step worked | `return value`; `x is ok` (fails closed) | `weave guide loom` ("Ending a run") |
| A named failed step | `failed_kind`, `failed_name`, `failed_step`, `failed_category` | `weave guide 6` |
| Who may steer | `steerable: true` with `streaming: true` on the agent; listed by `weave audit` | `weave guide 15` |
| What a stop means per step | `on_stopped { use partial / skip / discard / retry / suspend / conclude / ask / rewind to ... }`; script-wide `intervention { ... }` | `weave guide 15` |
| Reaching a running process | `weave run --steer-via console`; `weave daemon --ask-via ...`; `LoomRun` and `RunHandle` from Java | `weave guide 15` |
| Approvals from a phone, unattended | `weave triggers install` or `weave daemon` with `--ask-via telegram`; `TELEGRAM_CHAT_IDS` allowlist | `weave guide loom` ("Answering from Your Phone") |
| Earned autonomy under a ceiling | `decision` with `trust { ... }` or a `ladder`; `weave ladder describe`, `weave ladder test`; `weave autonomy status / freeze / ...` | `weave guide 13` |

## Watching, testing and change (stages 8 and 9)

| Decision | In Loom | Built in |
|---|---|---|
| The static review in CI | `weave check`, `weave audit --fail-on medium` | `weave guide 7` |
| Live trace | `weave run --trace` | `weave guide loom` ("Live Trace") |
| What counts as trouble | the `<script>.inspect` rules file; `weave observe rules <script>` | deployer `weave guide d3` |
| A page someone watches | `--observe` (the run's own page) or `--observe-fabric` (a Fabric server) | deployer `weave guide d4` to `d7` |
| Prices for the cost model | a prices file passed with `--prices`; `--max-cost` | `weave guide 9` |
| Trajectory tests on a scripted model | `ScriptedClient`, `LoomTrace.attach(executor)`, `expectPath(...)`, budget-stop tests | `weave guide 8` |
| Golden cases, hostile and fabricated-premise cases | eval4j golden dataset; `weave eval --check`, `--mock`, capped real runs | `weave guide 2`, `weave guide 11` |
| Try a change on real history without effects | `weave fork <run> --to <dir> --at <checkpoint> --effects simulate`; `weave replay` for a changed prompt or model | `weave guide loom` ("Checkpoints, Rewind and Fork"), `weave guide 13` |

## What Loom does not cover, so the design must

Say these plainly in the record, with what the user's own code or process does instead.

- **Locked surfaces against the agent's own tools.** Loom keeps the budget, the ledger and the autonomy level out of the agent's
  reach. Files such as tests, rubrics and the golden dataset are locked only if no tool the agent holds can write them: keep them out
  of every `allow_paths`, and protect them in the repository (code owners, review).
- **Held-out evaluation cases.** Keep them where no agent's tools reach, and run them from CI, not from the workflow.
- **Prompt-injection detection.** Not provided as a guarantee; the page reads patterns. Defence is the boundary in stage 3, and
  hostile cases that assert on tools not used.
- **What Java tools, MCP servers and tasks actually do.** `weave audit` reads the script. Review their code; test them with the
  project's own build.
- **A cost estimator.** Keep the cost model by hand and compare it with the measured spend.
- **Stall detection outside a run.** A run that never starts, or a scheduler that stopped, is the host's or the operator's to watch.
- **Novelty checks** (a change that repeats a rejected idea). Write them as a task if the workflow is an improvement loop.
