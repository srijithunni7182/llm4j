# Engineering discipline program (phases 24 to 28): make the right architecture the easy one

Status: **decisions D1 to D24 recorded 2026-11-01; D25 open (§3); specs draft for review**. Nothing here is built. This page is the overview of five phases, [24](24-stateless-nodes/DESIGN.md) to [28](28-evaluation-first/DESIGN.md). Each phase has the usual three documents (`DESIGN.md`, `TEST_STRATEGY.md`, `VERIFICATION.md`). This page holds what they share: the evidence, the decisions, the contracts that cross phases, the order, and the plan for the tutorial.

## 1. Why this program exists

The owner keeps a list of the architectural mistakes that teams make when they build multi-agent systems, and of the fixes that experience has taught. We asked one question of it: **does the way Loom, `weave review` and the skills are built stop a developer from making these mistakes, or does it make them easy?**

| # | The mistake | The fix the owner's list prescribes |
|---|---|---|
| M1 | **The god prompt.** Governance, tone and formatting crammed into one prompt dilute the reasoning, and internal rules ("Section 4.6") leak into the chat. | Workflow first. Move compliance out of the reasoning prompt into a separate, hidden evaluator node that checks or rewrites the output before a person sees it. |
| M2 | **The JSON trap.** A reasoning model forced to answer in a complex JSON schema makes logical leaps and brittle output. | Decouple thinking from formatting. The reasoning agent writes prose; a separate, cheap extraction node maps it to JSON. |
| M3 | **Evaluation as an afterthought.** Testing after the build means guessing what good looks like. | Evaluation-driven development. The golden dataset is the requirements document, written first, run continuously like unit tests. |
| M4 | **HITL in the middle of reasoning.** Pausing a workflow mid-thought for approval destroys momentum and bloats state. | Approval at the boundary, for risky and irreversible actions only. Earn autonomy with confidence scores and shadow mode. |
| M5 | **Global state leakage.** Utility nodes get the whole system prompt and conversation, adopt personas and break their parsers. | Contextual least privilege. Utility nodes are pure, stateless functions hydrated with only the text and schema they need. |

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

**Pending, needed before phase 26 is built:**

| # | Question | Proposed | Phase |
|---|---|---|---|
| D25 | Under a declared policy, how is an **agent's own outward tool call** checked? Its arguments are written by the model inside its loop, so no `check` statement can stand in front of them. | Allowed only when the tool is in the agent's `approve:` list (a person sees the exact arguments, which counts as a person's check); otherwise `weave check` reports an error whose fix is: the agent proposes, `check` runs, `use Tool(...)` sends. Alternatives in [phase 26 §7](26-policy-boundary/DESIGN.md#7-open-questions). | 26 |

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
| Same plumbing | Budgets, journal and resume, mocks under `weave test` and `weave eval`, `pii:`, trace events with names and never values. |
| Tested | Has golden cases of its own (D18, phase 28). |

`InternalNodeContractTest` (phase 24, extended in 26) asserts each property for every internal node kind, so a fourth kind cannot be added without meeting it.

### 4.2 Sinks and model-written values (phase 26, used by 27)

A **sink** is where a value leaves the workflow for people or the outside: the `return` of a workflow run as an entry point, a message on a channel, the arguments of a tool or task that sends or changes something outside (including `use`, phase 27). The runtime tracks, for every value it builds, whether it is **model-written** under D14. Phase 26 owns this; phase 27's `use` statement is a sink from its first build.

### 4.3 New review rule ids

| Id | Phase | Lens | Severity | What it looks for |
|---|---|---|---|---|
| LP08 | 24 | Prompts | low | An agent that does not see the transcript is given a task that passes no variables |
| LP09 | 24 | Prompts | medium | An agent used as an extractor (output schema, no tools, no memory, short prompt) |
| LA21 | 24 | Security | varies | An agent receives the transcript after an agent that reads untrusted content |
| LP10 | 25 | Prompts | medium | An agent that reasons must answer in a large schema |
| LY07 | 26 | Privacy and compliance | medium | Model-written text reaches a sink and the script declares no policy |
| LH05 | 27 | Human oversight | medium | An approval inside an agent's loop, for an outward tool, in a workflow nobody is watching |

Changed rules: LR05, LP02 (25); LA02, LH02, LH03 (27); LT06 (28). Lenses switched on by default: Human oversight (27), Testability (28).

## 5. Order of work

```
24 stateless nodes ──► 25 reason, then extract
        │
        └──────────► 26 policy boundary ──► 28 evaluation first (its weave check gate needs 24 and 26)
27 boundary approvals (independent; its `use` is a sink as soon as 26 exists)
```

Build 24 first: 25, 26 and 28 depend on the extractor and the internal-node contract. 27 can be built beside 25 and 26 by other hands. Each phase is shippable on its own and is documented when it ships (§6).

## 6. Documentation, and the tutorial in particular

[CLAUDE.md principle 7](../../CLAUDE.md) applies to every sub-phase: the documentation ships with the behaviour, never before and never later. Each phase's `DESIGN.md` has a **Documentation** section and a **Tutorial** section, and its `VERIFICATION.md` has acceptance criteria for both. The tutorial is the first thing a new Loom user reads, so this program treats it as a deliverable, not an afterthought:

| Tutorial chapter | What changes | Phase |
|---|---|---|
| [README](../tutorial/README.md) | The chapter table names the new constructs and the new starter; "How a Loom project fits together" shows the policy check | 24 to 28 |
| [3. Agents](../tutorial/03-agents.md) | New section "Agents that talk to people, and nodes that work for the workflow" (the internal/external idea, in the words of D11); "A prompt every agent starts with" says it is for tone and shared facts, not rules, and shows `shared_prompt: none` and `transcript:` | 24, 26 |
| [5. Structured answers and failure](../tutorial/05-structured-answers-and-failure.md) | New section "Extractors: fields from text, with nothing else"; "Putting it together: extract, then validate" rewritten around `extractor`; new section "Reason first, then extract" with when a small `output_schema` on an agent is still right | 24, 25 |
| [6. Tools and tasks](../tutorial/06-tools-and-tasks.md) | New section "Calling a tool yourself: `use`"; "Approvals" shows `approve` on `use` and `run` beside `approve:` on an agent | 27 |
| [7. Knowledge and memory](../tutorial/07-knowledge-and-memory.md) | What the shared transcript is, who sees it, and `transcript: none \| own \| all` | 24 |
| [8. People in the loop](../tutorial/08-people-in-the-loop.md) | "Approvals on tools" becomes "Where to ask: at the edge, not in the middle", with the three shapes (approve on a step, `human_prompt`, `decide`) | 27 |
| [9. Safety and cost](../tutorial/09-safety-and-cost.md) | New section "Policies: rules checked at the edge" (`policy`, `evaluator`, `check … against`, actions, sinks, the type rule); the leak guard under "Guards"; LA21 under "The lethal trifecta"; the safe-by-default checklist gains the policy and the boundary approval | 24, 26, 27 |
| [10. Testing](../tutorial/10-testing.md) | Golden cases for an extractor and an evaluator; "the dataset is the requirements" and `weave eval --requirements`; evaluating what changed; the notice when there is no dataset | 28 |
| [11. Advanced orchestration](../tutorial/11-advanced-orchestration.md) | Earned autonomy is introduced as the optional extra beside `approve` (D20) | 27 |
| [14. Cookbook and cheat sheet](../tutorial/14-cookbook.md) | Recipes "pull fields out of text", "let the reasoning agent think, then extract", "check every reply against house rules", "approve once, at the edge"; the cheat sheet gains every new keyword; "mistakes and their fixes" gains each new rule | 24 to 28 |

Every `loom` block added to the tutorial is checked by `TutorialExamplesTest`, and the web page is regenerated with `bash scripts/make-tutorial-html.sh` in the same sub-phase (`GuideTutorialPageTest` fails on a stale page). A block can only be added in the sub-phase that makes it load.

Other pages every phase touches, by [CLAUDE.md "things that change together"](../../CLAUDE.md): `LOOM_GUIDE.md` (parse-tested), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` (and the other guide chapters named per phase), the starters and their regenerated diagrams (`bash scripts/make-starter-diagrams.sh`, `StartersTest`), the `llm4j-workflow-guide`, `llm4j-harness-design` and `llm4j-eval-design` skills, the VS Code grammar, snippets and `CHANGELOG.md`, `bash scripts/sync-graph-render.sh` for new node kinds, a CTK scenario per construct, `docs/review/rules/` (regenerated with `weave rules docs core`), `docs/security/weave-audit.md` and `owasp-llm-top-10.md` for LA21 and the policy boundary, `docs/API_COMPATIBILITY.md`, `llms.txt`, and the next release's notes.

Stack-level pages (root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`) are updated **once, at the end of the program**, as the [spec method](README.md) requires; phase 28's `VERIFICATION.md` owns that step.

## 7. Compatibility, across the program

Existing scripts keep loading and running. What changes for an existing user:

| Change | Effect | Way back |
|---|---|---|
| Testability and Human oversight lenses on by default; LT06 medium | `weave review` shows more findings. Advisory: a run is never stopped by a finding. A CI job using `--fail-on medium` may start failing. | `lenses = -testability,-oversight` in `review.properties` |
| LH03 counts tool approvals | A script near the question limit may get a new LH03. | `oversight.max-questions` |
| A notice when there is no dataset | One extra line on `weave run` and `weave deploy`. | `--quiet` |
| A declared `policy` is binding | Only for scripts that declare one; no existing script does. | none needed |
| New keywords (`extractor`, `extract`, `policy`, `evaluator`, `check … against`, `use` as a statement, `approve` on a step, `transcript:`, `shared_prompt: none`) | Contextual words, so a name already used in a script still parses. `RepositoryScriptsTest` proves it on every `.loom` file in the repository. | none needed |

Every row is listed in the release notes and `docs/API_COMPATIBILITY.md` by the phase that makes it.

## 8. Phases

| # | Phase | Mistake | What it delivers |
|---|---|---|---|
| 24 | [Stateless nodes and least-privilege context](24-stateless-nodes/DESIGN.md) | M5 | `extractor` / `extract`; the internal-node contract; `transcript: none \| own \| all` and `shared_prompt: none` on agents; LP08, LP09; transcript taint in the audit (LA21) |
| 25 | [Reason, then extract](25-reason-then-extract/DESIGN.md) | M2 | LP10; LR05 and LP02 reworded; a `--fix-finding` that splits an agent into agent and extractor; the 17 examples migrated |
| 26 | [The policy boundary](26-policy-boundary/DESIGN.md) | M1 | `policy`, `evaluator`, `check … against …` with four actions; model-written values tracked to the sinks; the leak guard; LY07; `shared_prompt` repositioned |
| 27 | [Approval at the boundary](27-boundary-approvals/DESIGN.md) | M4 | `use Tool(...)`; `approve` on `use` and `run`; LA02 reworded; LH03 counting approvals; LH01 to LH04 on; LH05; starter 07 rewritten |
| 28 | [Evaluation first](28-evaluation-first/DESIGN.md) | M3 | Testability lens on, LT06 medium, the notice; the `weave check` gate on untested internal nodes; `weave eval --requirements`; evaluating what changed |
