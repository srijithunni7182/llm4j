# Engineering discipline program (phases 24 to 29): make the right architecture the easy one

Status: **decisions D1 to D46 recorded 2026-11-01; phases 24 to 29 built by 2026-11-04**. What is still owed is not code: the manual checks and reviews each phase's `VERIFICATION.md` §4 lists (phase 26's independent review of the provenance code, a non-developer reading the requirements page, the capped live runs) and the owner's review of the specs' open deviations; each phase's §7 says what was built and what was not. This page is the overview of six phases, [24](24-stateless-nodes/DESIGN.md) to [29](29-reviewable-architecture/DESIGN.md): five that build, and one that documents what they add up to. Each phase has the usual three documents (`DESIGN.md`, `TEST_STRATEGY.md`, `VERIFICATION.md`). This page holds what they share: the evidence, the decisions, the contracts that cross phases, the order, and the plan for the tutorial.

## 1. Why this program exists

The owner keeps a list of the architectural mistakes that teams make when they build multi-agent systems, and of the fixes that experience has taught. We asked one question of it: **does the way Loom, `weave review` and the skills are built stop a developer from making these mistakes, or does it make them easy?**

| # | The mistake | The fix the owner's list prescribes |
|---|---|---|
| M1 | **The god prompt.** Governance, tone and formatting crammed into one prompt dilute the reasoning, and internal rules ("Section 4.6") leak into the chat. | Workflow first. Move compliance out of the reasoning prompt into a separate, hidden evaluator node that checks or rewrites the output before a person sees it. |
| M2 | **The JSON trap.** A reasoning model forced to answer in a complex JSON schema makes logical leaps and brittle output. | Decouple thinking from formatting. The reasoning agent writes prose; a separate, cheap extraction node maps it to JSON. |
| M3 | **Evaluation as an afterthought.** Testing after the build means guessing what good looks like. | Evaluation-driven development. The golden dataset is the requirements document, written first, run continuously like unit tests. |
| M4 | **HITL in the middle of reasoning.** Pausing a workflow mid-thought for approval destroys momentum and bloats state. | Approval at the boundary, for risky and irreversible actions only. Earn autonomy with confidence scores and shadow mode. |
| M5 | **Global state leakage.** Utility nodes get the whole system prompt and conversation, adopt personas and break their parsers. | Contextual least privilege. Utility nodes are pure, stateless functions hydrated with only the text and schema they need. |
| M6 | **Premature agentification.** Agents used where deterministic code or one prompt would do: latency, cost, a tangled design. | Complexity budgeting. Never use an agent where code works; start with one prompt; split only when one context cannot hold the reasoning. |
| M7 | **Error cascading ("pass the trash").** A downstream agent acts on an upstream agent's hallucination. | Inter-node contracts. Validate an agent's output (schema, logic or a fast LLM check) before it drives the next step. |
| M8 | **Stringly-typed state.** Progress through a long process is tracked in the transcript, so steps are skipped. | An external state object the harness owns, updated deliberately, separating "what we know" from "what was said". |
| M9 | **Unbounded action loops.** An agent retries the same failing call over and over. | Circuit breakers and max steps enforced by the harness; repeated failure ends the loop or goes to a person. |
| M10 | **The model chooses the route.** A model's answer names which agent runs next and which variable its result goes into, with nothing but a prompt limiting the choice. | Route only within a declared allow-list; results go where the script says; the audit counts every agent a route can reach. |
| M11 | **Unbounded fan-out.** A step runs once per item of a list a model wrote, however long it is. | Bound the fan-out; when the list is far over the bound, fail and look upstream: the planner, its input, or an injection. |
| M12 | **The doer grades itself.** The agent that did the work decides it is done, or an evaluation judges a model with the same model. | Separate the doer from the checker: a reviewer, code-checked criteria, or a different judge model. |
| M13 | **Gating on self-reported confidence.** A number a chat model wrote about its own certainty is treated as a calibrated probability. | Use calibrated sources (a calibrated classifier) or measure calibration before trusting a threshold. |

M10 to M13 were found when the program was reviewed for further anti-patterns (E24 to E27; D43 to D46). The owner added M6 to M9 after the first five were specified. M6 and M9 were found already addressed (§2, E20, E23; D40), so they need no new work; M7 and M8 add a review rule, teaching and a starter (D41, D42).

The program has one aim:

> **The architecture the owner's list prescribes is the one Loom makes easiest to write, and the mistakes it lists are the ones `weave` notices.**

Where an idea depends on what a prompt *means*, the tooling does not pretend to understand it: that judgement is taught in the skills, and the tooling enforces only what it can decide from the structure of the script (decision D11).

## 2. The evidence: what the code does today

Read on 2026-11-01 at `d3b2b87a` (6.2.0). Every claim here is reproduced in the owning phase's `VERIFICATION.md` §0 before anything is built.

| Tag | Finding | Where | Mistake | Phase |
|---|---|---|---|---|
| E1 | Every `delegate` and `handoff` receives the whole run transcript ("Conversation History") first. `TranscriptAccumulationEngine.assembleContext` never looks at which agent is asking. It is the default engine. | `memory/TranscriptAccumulationEngine.java:70`, `HarnessExecutor.java:54`, `LOOM_GUIDE.md` "Bounding Loom's shared transcript" | M5 | 24 |
| E2 | The transcript can only be bounded, never removed: `Limit` rejects `maxTokens < 1` and the latest two exchanges are always kept. A Java host can replace the whole `MemoryEngine`, for every agent at once; no script can. | `TranscriptAccumulationEngine.Limit`, `LoomHost.Builder.memoryEngine` | M5 | 24 |
| E3 | `shared_prompt` is put in front of every agent's prompt. "There is no way to leave a single agent out." | `LOOM_GUIDE.md` "A prompt every agent starts with" | M5, M1 | 24, 26 |
| E4 | The stateless model step already exists, but only in Java: a `Task.withModel` run by `run X(...) model "…"` gets a bare metered client, no transcript, no shared prompt, and its answer is not written back. `intentclassify` is stateless the same way. A script-only author has no stateless model step. | `TaskRunner.java:206`, `HarnessExecutor.clientForTask`, `ClassifyRunner` | M5, M2 | 24 |
| E5 | The guide's "model reads, code decides" example and starter `08-extract-and-validate` use an `agent` with `output_schema` for extraction: the very node that should be stateless. | `LOOM_GUIDE.md` "Tasks: deterministic steps", `starters/08-extract-and-validate/main.loom` | M5, M2 | 24 |
| E6 | `weave audit` decides the lethal trifecta from tool reach only (`ToolReach`). Text an untrusted-reading agent puts into the shared transcript reaches every later agent, and no rule counts it. | `security/ToolReach.java:22`, `SecurityAudit` | M5 | 24 |
| E7 | A schema is enforced by appending "CRITICAL: You MUST respond in valid JSON format only, following this schema: …" to the reasoning agent's task: no room to think first. | `execution/DelegateRunner.java:358` | M2 | 25 |
| E8 | LR05 tells the developer to "give the agent an output_schema"; LP02 to "give it an output_schema". Both push the schema onto the reasoning agent. | `docs/review/rules/lr05.md`, `lp02.md` | M2 | 25 |
| E9 | 17 agents in the repository's `.loom` files have an `output_schema` **and** tools or grounding: they reason and format in one call. | getviral (8), inspect-soak (3), nirmaan-yantra crew (2), loom-inspect sim (3), the LC01 rule fixture (1) | M2 | 25 |
| E10 | The guide's own `shared_prompt` example is a governance rule ("Never promise a refund"), sent to every agent. Preflight C4, when the model ignores the shared prompt, advises "move the rule that matters into the agent's own prompt". | `LOOM_GUIDE.md`, `preflight/PreflightRunner.java:229` | M1 | 26 |
| E11 | Nothing checks content while a run is going. `guard` covers PII and bias only; `supervise` acts on counts only ("never a model's opinion"); LLM judges exist only offline in `weave eval`. A reply that quotes internal rules or the system prompt goes straight out. | `LOOM_GUIDE.md` "Agent Guards", "Rules that watch an agent" | M1 | 26 |
| E12 | The Testability lens is off by default, although the review design meant it to be on and switched it off only "until the workflow-test phase ships `weave test`". `weave test` has shipped. | `review/model/Lens.java:22`, `docs/specs/09-review-engine/DESIGN.md` R33 | M3 | 28 |
| E13 | `weave guide` lists the golden dataset as "Build a golden dataset (optional)". | `guide/Guide.java:35` | M3 | 28 |
| E14 | LA02's only advice for an effect with no approval is "Name the tool in the agent's approve: list": approval in the middle of the agent's loop. | `docs/review/rules/la02.md` | M4 | 27 |
| E15 | LH03 counts `human_prompt` and `decide` questions; a tool approval is recorded only as a wait. An agent with `approve: [Send]` and `max_iterations: 20` can ask 20 times in one step unnoticed. | `review/cost/RunProfile.java:416` | M4 | 27 |
| E16 | The Human oversight lens (LH01 to LH04) is off by default. | `docs/review/rules/index.md` | M4 | 27 |
| E17 | A script cannot call a tool without a model: `call` is for sub-workflows. Starter `07-approval` therefore hands a webhook to an LLM agent (`Sender`) and asks the person twice (a `human_prompt`, then `approve: [Notify]`); its `answer == "yes"` would trip LH04. | `starters/07-approval/main.loom` | M4 | 27 |
| E18 | A task can ask for approval (`requiresApproval(args)`), but only from Java. | `LOOM_GUIDE.md` "Writing a task" | M4 | 27 |
| E19 | Whether a tool changes anything is decided out of sight in the audit (`Capabilities.effect`) from its kind and options. Only `class` tools can state it, with `reach:` words that mix two questions (`reads`/`fetches` and `writes`/`sends`). An `openapi` tool is always assumed to change; an MCP tool is assumed to do everything. Tasks already declare `NONE`, `READS` or `CHANGES`. | `security/Capabilities.java`, `LOOM_GUIDE.md` "`reach:`" | M4, M1 | 27 |

| E20 | M6 is already served: a `run` task is "exact, free, testable and immune to prompt injection"; `docs/guide/01-decide-your-agents.md` says a step that must be exact is a task and one that picks a label is a classifier; LC07 ("asks a model for something a program could do"), LC01 and LM04 notice the common cases. | `LOOM_GUIDE.md` "Tasks", guide ch. 1, rules LC07, LC01, LM04 | M6 | none (D40) |
| E21 | M7: the building blocks exist (schemas checked by the runtime, tasks for logic, `decide`; and, once built, extractors and `check`), but nothing notices when an agent's free prose drives a step that changes something with nothing validating it on the way. | review rule index | M7 | 27 |
| E22 | M8: between steps the script is the state machine (statements run in order, typed variables, a journal, checkpoints). Inside one long agent loop, progress lives only in the conversation, which compaction may remove; the harness-design skill says nothing about it. | `LOOM_GUIDE.md` "Context and Compaction", the harness-design skill | M8 | 25 |
| E23 | M9 is already served: agents default to `max_iterations` 10; `loop … max N` and LR03; bounded `retry`; run, step and loop budgets; `supervise` with `looping(N)` and `tool_failing(Tool, N)` and `wrap_up` or `cancel` (to a person); Fabric's looping incident; LO04 and LB01. | `ReActAgent.java:2374`, `LOOM_GUIDE.md` "Rules that watch an agent" | M9 | none (D40) |
| E24 | M10: `delegate … to {x.owner} -> {x.name}` takes the agent and the variable from a model's value; `HarnessExecutor.resolveName` only checks it is not blank, so a model's text can route work to any agent and overwrite any variable. The audit skips dynamic delegates entirely (`SecurityAudit.java:774`). Starter 06 limits `owner` only in its planner's prompt. 17 scripts use dynamic names. | `execution/HarnessExecutor.java:3254`, `security/SecurityAudit.java:774`, `starters/06-plan-and-fan-out` | M10 | 27 |
| E25 | M11: `loop … max N` has a bound and `on_exhausted`; `for each` and `parallel for each` have neither. Budgets cap the total, so an over-long plan stops part-way with some items done; LB01 notices repeated effects, not repeated work. | `LOOM_GUIDE.md` "for each", rule LB01 | M11 | 25 |
| E26 | M12: `weave eval` judges rubric lines with the agent's own model unless `--judge` names another. The harness-design skill warns "done is declared by the doer", but nothing notices a loop whose exit is decided by the agent doing the work. | `LOOM_GUIDE.md` "Evaluating a Workflow", the harness-design skill | M12 | 28 |
| E27 | M13: classifiers are marked calibrated or uncalibrated and the eval-design skill covers calibration, but an agent's self-reported confidence field is treated like any number. Detecting it would rest on a field's name. | phase 7, the eval-design skill | M13 | 28 |

What already serves the list well, and is kept: earned autonomy (`decide`, `watch`/`suggest`/`act`, blind measurement, demotion, replay) for M4; the workflow-guide skill's "tests first" default, its step 2b ("a requirement that is only in a prompt is a wish"), `weave init`'s golden folder and `llm4j-eval-design` for M3; the harness-design principle "separate the doer from the checker" for M1; `intentclassify` ("a classification problem is not a generation problem") for M2.

## 3. Decisions (answered by the owner, 2026-11-01)

| # | Decision | Phase |
|---|---|---|
| D1 | A stateless model step is declared **in the script** as a first-class internal node, not by a flag on an agent and not only through Java. | 24 |
| D2 | It is called **`extractor`**, used as `extract X from …`. | 24 |
| D3 | Optional fields are listed on **a separate `optional: [ … ]` line**, which reads better for non-technical reviewers than a marker on each field. | 24 |
| D4 | An extractor's inputs are **only** what the statement names; its `instructions:` are fixed text with no `{var}`. | 24 |
| D5 | Thinking and formatting are separated by **two explicit steps** (`delegate` to an agent in prose, then `extract`), not by a shorthand that hides the second call. | 25 |
| D6 | The new review rule fires when an agent **reasons** (tools, grounding, a loop, or a prompt over about 300 tokens) **and** must answer in a **large schema** (a nested object, a list of objects, or more than 5 fields). | 25 |
| D7 | **Every shipped example** the rule flags is migrated in the same change: they are reference implementations. | 25 |
| D8 | That rule is **medium**. | 25 |
| D9 | Compliance is a **first-class boundary check**: `policy` and `check … against …`. | 26 |
| D10 | The **`evaluator`** is a first primitive of its own. *What* the rules are (`policy`) is separate from *who* checks them (`evaluator`). | 26 |
| D11 | **Internal and external are design ideas taught in the skills.** The tooling never labels an agent internal or external, because that depends on what prompts mean. There is no third category and no `external` keyword. An agent is external when its words reach people (defined by audience, not by whether it chats). | 26 |
| D12 | Policy text lives **in its own file**, versioned like prompts, owned by whoever owns compliance. | 26 |
| D13 | Once a script declares a policy, the **runtime guards the sinks**: model-written text that reaches a sink must have passed a check against it, or the step fails. Always on, fail closed. `weave check` rejects the paths it can see. Approvals and `human_prompt` are **not** sinks. | 26 |
| D14 | "Model-written" is decided **by type**: an agent's prose, strings built from it, and `string` fields of an extractor need the check; labels, enums, numbers and true/false do not. | 26 |
| D15 | Violation actions: `rewrite with <Agent>`, `ask person`, `replace with "…"`, `fail`. A rewrite is checked again **once**, then the step fails closed. | 26 |
| D16 | The deterministic **leak guard** is on as `warn` whenever a policy is declared; `block` is opt-in. | 26 |
| D17 | Evaluation **stays skippable, but louder**: the Testability lens on, LT06 medium, a one-line notice on run, deploy and the live page. | 28 |
| D18 | An `evaluator`, an `extractor` or a policy rule **without golden cases fails `weave check`**. | 28 |
| D19 | `weave eval --requirements` (a plain sign-off page) and **evaluating what changed** are in scope. | 28 |
| D20 | **`approve:` stays**, as a first-class option, with **no ladder**. Earned autonomy is an extra some implementations choose. | 27 |
| D21 | **`use Tool(...)`** runs a tool directly from the script; **`approve`** can be written on `use` and on `run`. | 27 |
| D22 | **LH01 to LH04 on by default**, and a new **LH05**. | 27 |
| D23 | The audit counts **"receives the transcript after an agent that reads untrusted content"** as reading untrusted content (structural, like the existing trifecta). | 24 |
| D24 | An ordinary agent may opt out: `memory { transcript: none \| own \| all }` and `shared_prompt: none`. **LP08 (low)** fires only when a delegate to such an agent passes no variables. | 24 |
| D25 | Under a declared policy, **every changing tool call an agent makes is checked automatically before it runs** (its text arguments, under D14), by the evaluator the policy names (`checked by`). A refused call never runs; the agent is told the rule ids and one-line reasons (never the rule text) and may try again, a bounded number of times. **`approve:` is optional and comes on top**: the person sees only calls that passed, with the verdict. A written `use` step still needs a written `check`. | 26 |
| D26 | Every tool has an **effect** in the three words tasks use: `none`, `reads`, `changes`. Where data comes from and whether it leaves stay the audit's trifecta legs. | 27 |
| D27 | The effect is **the developer's decision**, marked on the tool; a declared effect is authoritative and Loom does not second-guess it from the HTTP method. When nothing is declared, Loom assumes (today's rules) and says so. | 27 |
| D28 | Loom **uses MCP servers' tool annotations** (`readOnlyHint`, `destructiveHint`) as the server author's declaration. | 27 |
| D29 | Tool effects are built **in phase 27, first** (27a); phase 26 depends on it. | 27, 26 |
| D30 | After phase 28, the program's differentiator is documented as its own phase: **Loom distils multi-agent best practice into a language that can be reviewed, by people and by tools**. One page, the stack-level pages once, and every claim about another framework (LangChain, LangGraph, Google ADK) backed by a rerunnable probe, as `WHY_LLM4J.md` already requires. | 29 |
| D31 | `WHY_LOOM.md` gets one "see also" line linking the page; its words are not changed. | 29 |
| D32 | The probes use the Python Google ADK, pinned. | 29 |
| D33 | A short article version goes in `docs/articles/` once the page is verified. | 29 |
| D34 | **Complexity stays opt-in.** (A) A tested promise: starters 01 to 04 and the `weave init` templates need none of the new constructs and get no new findings. (B) Each construct is offered by a fix when it is needed (LP09, LP10, LY07, LH05, an undeclared effect). (E) Tutorial chapters 1 to 4 are unchanged; each new construct has a "you don't need this until …" box; new starters come last. (F) Once phase 28 is built, a fix that creates an extractor or an evaluator (LP09, LP10, LY07) leaves the script failing `weave check` only for its missing golden cases, converts the old agent's cases where it can, and its result names the file and the `--init` command: the one place a fix leaves work to do, on purpose (D18). | 24 to 28 |
| D35 | **Review stays readable.** (A) Every new or newly-default rule meets the existing precision bar (≥ 0.9 on the benchmark corpus, Phase 9 R18) or ships as heuristic. (B) Severity follows exposure: LY07 is medium only when output reaches people outside (a channel, trigger, schedule, daemon, deployment or a changing `use`), else info; LT06 follows LT01 (medium when the script can write, send or ask a person, else low). (C) Findings are grouped per rule per script, and the summary shows what is new since the last review first. (D) After the upgrade, `weave review` suggests a baseline once. (E) A noise gate before each phase ships: a new rule accepted in more than 50% of the places it fires on the reference corpus is retuned or demoted. | 24 to 28 |
| D36 | **Value tracking is built to be provably complete.** (A) Provenance lives beside the values, which stay plain Java objects. (B) Setting a variable requires stating its provenance, so the compiler finds every route. (C) Unknown provenance counts as unchecked model text. (D) Static types decide which fields are free text. (E) A canary oracle compares the tracking with real data flow, permanently in CI for changes to the runtime. (F) Report-only inside development before enforcement is switched on (never released that way). (G) An independent review and the strictest mutation threshold before release. | 26 |
| D37 | **Failing closed degrades gracefully.** (A) Evaluators can name fallback models. (B) `on_unavailable { … }` gives a safe answer when the evaluator cannot answer (default `fail`). (C) "Unsure" is counted apart from violations everywhere. (D) Incidents for a failing evaluator, a high unsure rate, a high refusal rate and many overrides. (E) `weave eval` fails an evaluator that flags more than 10% of its clean and near-miss cases (adjustable per evaluator). | 26, 28 |
| D38 | **The new model calls are costed, budgeted and watched like agents.** (A) `weave review` shows them as their own cost and time lines. (B) Generated extractors and evaluators default to the cheapest model of the provider; the skills recommend calibrated backends for evaluators. (C) Free deterministic checks run before any evaluator call. (D) A verdict is reused within a run for identical text, policy version and evaluator, never across runs. (E) A policy's checks get a budget reserve worked out automatically, shown in `weave review`, with an optional override. (F) Latency per evaluator and extractor in the trace and on the live page. And: extractors, evaluators and automatic tool-call checks are metered in the spend report under their own names, count against run and their own budgets, and appear in Fabric's model, cost and incident views and in `weave eval`'s "By agent and node". | 24, 26, 28 |
| D39 | **The transcript default stays `all`, and is made visible**: `weave graph`, `weave explain` and the editor mark each agent that receives the full transcript. No new default setting and no planned flip. | 24 |
| D40 | **M6 and M9 need no new work**: Loom already addresses them (E20, E23). | none |
| D41 | **M7, error cascading: notice it and teach it, with no new syntax.** A review rule, LR11, warns when an agent's free prose reaches a step that changes something with nothing validating it on the way; like every finding it can be accepted or turned off. The skills, guide and tutorial teach "never pipe an agent straight into an action" with the existing constructs: extract (shape), `alt` or a task (logic), `check` (judgement), `approve`. Proposed `ensure` and `check … given` were dropped: composition already covers them. | 27 |
| D42 | **M8, state inside a long agent: lift it into the workflow.** Taught as a principle and a recipe (a bounded `loop`, a worker with `transcript: none`, an `extractor` that records progress in a journaled variable), with a tested starter, `16-step-by-step-agent`. No `state { … }` construct for now. The compliance starter of phase 26 becomes `17-compliance-boundary`. | 25 |
| D43 | **M10, model-chosen routing.** (A) A dynamic agent needs an allow-list, inline (`to {t.owner} in [Researcher, Writer]`) or from an `enum` field; the runtime refuses any other name; `weave check` reports an unbounded dynamic target as an **error when a reachable agent holds a changing tool or private data**, else a warning. (B) A dynamic result name may only create a new plain variable, never overwrite one or use a reserved `_` name; the map form `-> results[t.name]` becomes the only dynamic form at the next major version, announced now. (C) The audit stops skipping dynamic delegates: a dynamic target reaches every agent in its list (or every agent when there is none), and LA22 reports it. (D) Starter 06 and the other examples are fixed, and the docs teach it. | 27 |
| D44 | **M11, unbounded fan-out.** `for each … max N` with `on_too_many { … }`; when the list is longer, **the workflow fails before any item starts** unless `on_too_many` says otherwise. The error names the agent that produced the list, a trace event and a live-page incident flag it as an upstream problem (planner, input or injection), and the run can be captured as a test. LR13 warns about a `for each` over a model-produced list with no `max`, and the cost estimate says the worst case is unknown. A bound in the list's type was considered and dropped. | 25 |
| D45 | **M12, the doer grades itself.** (A) `weave eval`'s default judge is a different model: a named judge if the dataset or project gives one, else the cheapest different model the project can reach, said in the report; `--judge same` is explicit and noted under Cautions; with only one model reachable it judges with it and notes it. (B) LR12 (medium) warns when a loop's exit condition reads a field written by the agent doing the loop's work, and (low) when an evaluator uses the same model as the agent it checks. (C) The skills and guide teach it. | 28 |
| D46 | **M13, self-reported confidence: taught, not ruled.** The eval-design and workflow-guide skills explain that a chat model's own confidence is not a calibrated probability, and point to calibrated classifiers and to measuring calibration. No review rule (it would rest on field names). | 28 |

Not taken, and why: a heuristic review rule that looks for governance wording ("never", "must not", "Section 4.6") in prompts was proposed and dropped with D11: it would claim to read meaning. That guidance lives in the skills.

## 4. Contracts shared by the phases

### 4.1 Internal nodes

`intentclassifier` (which is it?), `extractor` (what are the fields?, phase 24) and `evaluator` (does it break a rule?, phase 26) are **internal nodes**. One contract holds for all three, and the runtime enforces it:

| Property | Rule |
|---|---|
| Stateless | Never reads or writes the transcript or agent memory. Gets no `shared_prompt`, persona or skills. |
| Least privilege | Its inputs are only what its statement names. Its instructions are fixed text. |
| Data, not instructions | Inputs are passed between untrusted-data markers. |
| One call | No tools, no rounds. |
| Typed output | A label, fields or a verdict, checked by the runtime. |
| Same plumbing | Budgets, journal and resume, mocks under `weave test` and `weave eval`, `pii:`, trace events with names and never values. Metered and watched like an agent (D38): in the spend report under its own name, against run and its own budgets, with tokens, cost and latency in the trace, in Fabric's model, cost and incident views, and in `weave eval`'s "By agent and node". |
| Tested | Has golden cases of its own (D18, phase 28). |

`InternalNodeContractTest` (phase 24, extended in 26) asserts each property for every internal node kind, so a fourth kind cannot be added without meeting it.

### 4.2 Sinks and model-written values (phase 26, used by 27)

A **sink** is where a value leaves the workflow for people or the outside: the `return` of a workflow run as an entry point, a message on a channel, the arguments of a tool whose effect is `changes` (§4.3) or of a `CHANGES` task (including `use`, phase 27). The runtime tracks, for every value it builds, whether it is **model-written** under D14. Phase 26 owns this; phase 27's `use` statement is a sink from its first build.

### 4.3 Tool effects (phase 27 R0, used by 26)

Every tool has an effect, `none`, `reads` or `changes`, the same words as a task's. The developer declares it on the tool (`effect:`, or `effects:` per operation for OpenAPI and MCP), and the declaration decides. Undeclared tools get an assumed effect (today's rules; MCP from the server's annotations; unknown kinds `changes`), always shown with where it came from. Every rule in this program that says "a tool that changes something" means `effect: changes`, resolved in one place (`Capabilities.resolve`).

### 4.4 New review rule ids

| Id | Phase | Lens | Severity | What it looks for |
|---|---|---|---|---|
| LP08 | 24 | Prompts | low | An agent that does not see the transcript is given a task that passes no variables |
| LP09 | 24 | Prompts | medium | An agent used as an extractor (output schema, no tools, no memory, short prompt) |
| LA21 | 24 | Security | varies | An agent receives the transcript after an agent that reads untrusted content |
| LP10 | 25 | Prompts | medium | An agent that reasons must answer in a large schema |
| LY07 | 26 | Privacy and compliance | medium when the output reaches people outside, else info (D35) | Model-written text reaches a sink and the script declares no policy |
| LH05 | 27 | Human oversight | medium | An approval inside an agent's loop, for an outward tool, in a workflow nobody is watching |
| LR11 | 27 | Reliability | medium | An agent's free prose reaches a step that changes something with nothing validating it on the way |
| LA22 | 27 | Security | by the most dangerous agent reachable | A model's answer chooses which agent runs |
| LR13 | 25 | Reliability | medium | A `for each` over a list a model produced has no `max` |
| LR12 | 28 | Reliability | medium (low for an evaluator sharing the checked agent's model) | The agent doing a loop's work decides when it is done |

Changed rules: LR05, LP02 (25); LA02, LH02, LH03 (27); LT06 (28). LA15 is unchanged and keeps reporting declared reach and effect. Lenses switched on by default: Human oversight (27), Testability (28).

### 4.5 The simple path stays simple (D34)

A newcomer's first hour of Loom must not get harder. `SimplePathStaysSimpleTest` (built in phase 24, re-run in every phase's verification) asserts that starters 01 to 04 and every `weave init` template load and pass their tests **using none of the program's constructs**, and that `weave review` gives them **no finding they did not have before the program**. A phase that breaks it is not done. New constructs are met through fixes when they are needed, not learned up front: LP09 and LP10 write the extractor (24, 25), LY07 scaffolds a policy, an evaluator and the `check` (26), LH05 rewrites to `use … approve` (27), and the editor offers to declare an undeclared tool effect (27).

### 4.6 Review stays readable (D35)

Every phase that adds a rule or switches one on by default meets two gates before it ships: the rule's measured precision on the benchmark corpus is at least 0.9 (Phase 9 R18), or it ships as heuristic; and on the reference corpus (the starters, the examples and the field-test project) the noise report shows no new rule accepted in more than 50% of the places it fires. Findings are grouped per rule per script, the summary shows what is new since the last review first, and after the upgrade `weave review` suggests writing a baseline once (phase 27 R9). Severity follows exposure where a rule would otherwise fire on most scripts (LY07, LT06).

## 5. Order of work

```
24 stateless nodes ──► 25 reason, then extract
        │
        └──────────► 26 policy boundary ──► 28 evaluation first (its weave check gate needs 24 and 26) ──► 29 architecture you can review
27 boundary approvals: 27a tool effects first (26's sinks need it); its `use` is a sink as soon as 26 exists
```

Build 24 first: 25, 26 and 28 depend on the extractor and the internal-node contract. 27 can be built beside 25 and 26 by other hands. Each phase is shippable on its own and is documented when it ships (§6). **29 comes last**: it documents what 24 to 28 built, with probes against other frameworks, and makes the program's one update of the stack-level pages.

## 6. Documentation, and the tutorial in particular

[CLAUDE.md principle 7](../../CLAUDE.md) applies to every sub-phase: the documentation ships with the behaviour, never before and never later. Each phase's `DESIGN.md` has a **Documentation** section and a **Tutorial** section, and its `VERIFICATION.md` has acceptance criteria for both. The tutorial is the first thing a new Loom user reads, so this program treats it as a deliverable, not an afterthought:

**Teaching order (D34).** Chapters 1 to 4 are not changed by this program. Every new construct is introduced with a short box, "You don't need this until …" (your workflow sends something, pulls fields out of text, or must follow written rules), and the new starters come after the existing ones.

| Tutorial chapter | What changes | Phase |
|---|---|---|
| [README](../tutorial/README.md) | The chapter table names the new constructs and the new starter; "How a Loom project fits together" shows the policy check | 24 to 28 |
| [3. Agents](../tutorial/03-agents.md) | New section "Agents that talk to people, and nodes that work for the workflow" (the internal/external idea, in the words of D11); "A prompt every agent starts with" says it is for tone and shared facts, not rules, and shows `shared_prompt: none` and `transcript:` | 24, 26 |
| [4. Control flow](../tutorial/04-control-flow.md) | `for each … max N` and `on_too_many` (D44); routing only within an allow-list and results into a map (D43); why a prompt is not a boundary for routing | 25, 27 |
| [5. Structured answers and failure](../tutorial/05-structured-answers-and-failure.md) | New section "Extractors: fields from text, with nothing else"; "Putting it together: extract, then validate" rewritten around `extractor`; new section "Reason first, then extract" with when a small `output_schema` on an agent is still right | 24, 25 |
| [6. Tools and tasks](../tutorial/06-tools-and-tasks.md) | New section "Does this tool change anything? `effect:`" (the developer's decision, per operation for OpenAPI and MCP, what is assumed otherwise); every tool example declares its effect; new section "Calling a tool yourself: `use`"; "Approvals" shows `approve` on `use` and `run` beside `approve:` on an agent | 27 |
| [7. Knowledge and memory](../tutorial/07-knowledge-and-memory.md) | What the shared transcript is, who sees it, and `transcript: none \| own \| all` | 24 |
| [8. People in the loop](../tutorial/08-people-in-the-loop.md) | "Approvals on tools" becomes "Where to ask: at the edge, not in the middle", with the three shapes (approve on a step, `human_prompt`, `decide`) | 27 |
| [9. Safety and cost](../tutorial/09-safety-and-cost.md) | New section "Policies: rules checked at the edge" (`policy`, `evaluator`, `check … against`, actions, sinks, the type rule, and how an agent's own changing tool calls are checked automatically with `approve:` as an optional extra); the leak guard under "Guards"; LA21 under "The lethal trifecta"; the safe-by-default checklist gains the policy and the boundary approval | 24, 26, 27 |
| [10. Testing](../tutorial/10-testing.md) | The default judge is a different model, and `--judge same` (D45); self-reported confidence is not calibration (D46); Golden cases for an extractor and an evaluator; "the dataset is the requirements" and `weave eval --requirements`; evaluating what changed; the notice when there is no dataset | 28 |
| [11. Advanced orchestration](../tutorial/11-advanced-orchestration.md) | Earned autonomy is introduced as the optional extra beside `approve` (D20); a section "Keep progress in the workflow, not the conversation" with starter 16 (D42) | 25, 27 |
| [14. Cookbook and cheat sheet](../tutorial/14-cookbook.md) | Recipes "pull fields out of text", "let the reasoning agent think, then extract", "check every reply against house rules", "approve once, at the edge"; the cheat sheet gains every new keyword; "mistakes and their fixes" gains each new rule | 24 to 28 |

Every `loom` block added to the tutorial is checked by `TutorialExamplesTest`, and the web page is regenerated with `bash scripts/make-tutorial-html.sh` in the same sub-phase (`GuideTutorialPageTest` fails on a stale page). A block can only be added in the sub-phase that makes it load.

Other pages every phase touches, by [CLAUDE.md "things that change together"](../../CLAUDE.md): `LOOM_GUIDE.md` (parse-tested), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` (and the other guide chapters named per phase), the starters and their regenerated diagrams (`bash scripts/make-starter-diagrams.sh`, `StartersTest`), the `llm4j-workflow-guide`, `llm4j-harness-design` and `llm4j-eval-design` skills, the VS Code grammar, snippets and `CHANGELOG.md`, `bash scripts/sync-graph-render.sh` for new node kinds, a CTK scenario per construct, `docs/review/rules/` (regenerated with `weave rules docs core`), `docs/security/weave-audit.md` and `owasp-llm-top-10.md` for LA21 and the policy boundary, `docs/API_COMPATIBILITY.md`, `llms.txt`, and the next release's notes.

Stack-level pages (root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`) are updated **once, at the end of the program**, as the [spec method](README.md) requires; [phase 29](29-reviewable-architecture/DESIGN.md) owns that step, together with the new page "Architecture you can review".

## 7. Compatibility, across the program

Existing scripts keep loading and running. What changes for an existing user:

| Change | Effect | Way back |
|---|---|---|
| Testability and Human oversight lenses on by default; LT06 medium | `weave review` shows more findings. Advisory: a run is never stopped by a finding. A CI job using `--fail-on medium` may start failing. | `lenses = -testability,-oversight` in `review.properties` |
| LH03 counts tool approvals | A script near the question limit may get a new LH03. | `oversight.max-questions` |
| A notice when there is no dataset | One extra line on `weave run` and `weave deploy`. | `--quiet` |
| A declared `policy` is binding | Only for scripts that declare one; no existing script does. | none needed |
| New keywords (`extractor`, `extract`, `policy`, `evaluator`, `check … against`, `use` as a statement, `approve` and `on_declined` on a step, `transcript:`, `shared_prompt: none`) | Contextual words, so a name already used in a script still parses. `RepositoryScriptsTest` proves it on every `.loom` file in the repository. | none needed |

Every row is listed in the release notes and `docs/API_COMPATIBILITY.md` by the phase that makes it.

## 8. Phases

| # | Phase | Mistake | What it delivers |
|---|---|---|---|
| 24 | [Stateless nodes and least-privilege context](24-stateless-nodes/DESIGN.md) | M5 | `extractor` / `extract`; the internal-node contract; `transcript: none \| own \| all` and `shared_prompt: none` on agents; LP08, LP09; transcript taint in the audit (LA21) |
| 25 | [Reason, then extract](25-reason-then-extract/DESIGN.md) | M2, M8, M11 | LP10; LR05 and LP02 reworded; a `--fix-finding` that splits an agent into agent and extractor; the 17 examples migrated; progress kept in the workflow (recipe and starter 16); `for each … max N` and LR13 |
| 26 | [The policy boundary](26-policy-boundary/DESIGN.md) | M1 | `policy`, `evaluator`, `check … against …` with four actions; model-written values tracked to the sinks; the leak guard; LY07; `shared_prompt` repositioned |
| 27 | [Approval at the boundary](27-boundary-approvals/DESIGN.md) | M4, M7, M10 | Every tool has an effect (`none`, `reads`, `changes`), declared by the developer; `use Tool(...)`; `approve` on `use` and `run`; LA02 reworded; LH03 counting approvals; LH01 to LH04 on; LH05; starter 07 rewritten; LR11 and "never pipe an agent into an action"; dynamic routing within allow-lists, protected result names, LA22 |
| 28 | [Evaluation first](28-evaluation-first/DESIGN.md) | M3, M12, M13 | Testability lens on, LT06 medium, the notice; the `weave check` gate on untested internal nodes; `weave eval --requirements`; evaluating what changed; a different default judge, LR12, calibration taught |
| 29 | [Architecture you can review](29-reviewable-architecture/DESIGN.md) | all | `docs/REVIEWABLE_ARCHITECTURE.md` (the differentiator, the thirteen mistakes, real `weave` output, generated by a docs test); probes P9 to P17 on Loom, LangGraph, LangChain and Google ADK; `WHY_LLM4J.md`; the stack-level pages once |

## 9. Cautions, and how the program answers them

After the specs were written, six risks of the program itself were reviewed with the owner. Each is answered by a decision and lives in the phases named:

| Caution | Decision | Where |
|---|---|---|
| The new constructs make Loom feel large | D34: a tested simple path; constructs offered by fixes; teaching order | §4.5; phases 24 to 28; §6 |
| Review becomes noise and people stop reading it | D35: the precision bar, severity by exposure, grouping, a baseline after upgrade, a noise gate | §4.6; phases 24 to 28 |
| Value tracking misses a route and text leaks unchecked | D36: provenance beside values, required at every route, unknown is unchecked, a canary oracle, report-only before enforcing, an independent review | phase 26 R15 |
| A down or timid evaluator feels like an outage | D37: fallback models, `on_unavailable`, unsure counted apart, incidents, a false-alarm gate | phase 26 R2 to R5, R14; phase 28 R10 |
| The new calls add cost and latency unseen | D38: cost lines, cheap defaults, free checks first, verdict reuse, a budget reserve, latency, metering in Fabric | phases 24, 26 R16, 28 |
| Agents still get the whole transcript by default | D39: the default stays, and is shown in the graph, `weave explain` and the editor | phase 24 R8 |

## 10. When the plan bends

The aim of the program is the discipline of workflows built with Loom (§1), not the letter of a spec. When building shows a requirement cannot be met as written, the phase may deviate, but every deviation is recorded in that phase's `VERIFICATION.md` §7 with three things: what the spec asked, what was built instead, and **what discipline a Loom user loses by it and what covers that instead** (a different rule, the skills, the tutorial, a follow-up). A deviation with nothing covering the loss is not accepted until the owner decides. Deviations made so far: phase 24's four (see its §7), each with its cover.

