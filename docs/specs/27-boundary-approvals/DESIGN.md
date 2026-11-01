# Phase 27 design: approval at the boundary

Status: **draft for review** (decisions D20 to D22 and D26 to D28 recorded 2026-11-01). Layer: the Loom language and runtime (every tool's effect, a tool step, approval on a step), the review engine (LA02, LH02, LH03, a new LH05, the Human oversight lens on), the starters, the skills and the tutorial. Part of the [engineering discipline program](../DISCIPLINE.md).
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Independent of phases 24 to 26 to build. [Phase 26](../26-policy-boundary/DESIGN.md) depends on R0 (its sinks are tools whose effect is `changes`); once phase 26 exists, `use` is one of its sinks.
Evidence owned: **E14 to E19**. Mistake: **M4, HITL in the middle of reasoning**.

## 1. Problem

The owner's rule: a person is asked at the boundary, about a risky or irreversible action, not in the middle of an agent's reasoning. Loom's earned autonomy (`decide` with `watch`, `suggest`, `act`) already does this well for teams that use it. But not every workflow uses earned autonomy (D20), and for those the path Loom offers is the middle one:

- LA02's only advice is to put the tool in the agent's `approve:` list (E14), which stops the agent mid-loop, holds its conversation, and shows the person one tool call without the context of the job.
- LH03 does not count those approvals (E15), and the whole Human oversight lens is off (E16).
- A script cannot call a tool itself (E17): an approved action must be handed back to a model to carry out, which is why starter 07 uses an LLM agent to post a webhook and asks the person twice.
- A task can ask for approval, but only from Java (E18).
- Whether a tool changes anything is decided out of sight in the audit, from its kind and options; only Java tools can state it (`reach:`), in words that mix two questions; an OpenAPI tool is always assumed to change, and an MCP tool is assumed to do everything (E19). Tasks, by contrast, already declare `NONE`, `READS` or `CHANGES`.

## 2. Goals and non-goals

Goals:

- **Every tool states whether it changes anything**, as tasks do, and the developer's word decides (R0).
- A script can **call a tool directly**, with no model (R1).
- A script can **ask for approval on a step**, once, showing the exact payload, on `use` and on `run` (R2).
- Review offers the boundary first, counts every question, and warns about approvals in the middle of a loop where nobody is watching (R3 to R6).
- Starter 07 and the skills teach the boundary shapes (R7, R8).

Non-goals:

- A ladder on `approve:` (D20). Earned autonomy stays on `decide`.
- Removing or discouraging `approve:` on an agent's tools for interactive use. It stays first-class (D20).
- A new kind of question. `approve` uses the existing approval channel, suspension and resume.

## 3. Requirements

**R0 Every tool has an effect** (D26 to D28). Built first (sub-phase 27a): phase 26's sinks and the rest of this phase depend on it.

Whether a tool changes anything is a property **of the tool**, stated by the developer, in the same three words a task already uses (`TaskEffect`):

```loom
tool Orders   { use: http  base_url: "https://api.acme.com"  methods: "GET,POST"  effect: reads }     // POST /search: the developer says it only reads
tool Refunds  { use: http  base_url: "https://pay.acme.com"  methods: "POST"  effect: changes }
tool Shop     { use: openapi  spec: "specs/shop.yaml"  effects: { listOrders: reads, getOrder: reads, createRefund: changes } }
tool Invoices { use: class  class: "com.acme.tools.InvoiceTool"  effect: reads }
```

- **`effect: none | reads | changes`** (D26). `none`: touches nothing outside the run (a calculator). `reads`: looks something up and changes nothing. `changes`: writes, sends, posts, pays, runs a program: anything that is different afterwards. Where the data comes from (untrusted, private) and whether it leaves the system (outward) stay the trifecta legs of the audit; they answer a different question and are unchanged.
- **The developer's declaration decides** (D27). A declared `effect:` is authoritative for the runtime, `weave check`, the audit and review. Loom does not second-guess it from an HTTP method or anything else, and it adds no finding for it beyond what LA15 already says about declared reach.
- **Kinds that hold several tools** declare per operation: `effects: { operation: reads, … }` for an `openapi` tool (by `operationId`) and for an `mcp` server (by tool name). An operation not listed falls back to the default below.
- **When nothing is declared, Loom assumes, and says so.** The assumption is shown by `weave explain`, `weave graph`, the editor's hover and `weave review --format json` (`effect`, `effectSource: declared | assumed`, and the reason), always with a pointer to `effect:`:

| Kind | Assumed effect when undeclared |
|---|---|
| calculator, datetime, translate and the other harmless built-ins | `none` |
| search, `sql`, `knowledge_graph` with `read_only: true`, `skill_registry` | `reads` |
| `webhook`, `email`, `shell`, `knowledge_graph` otherwise | `changes` |
| `file` | by `mode`: `read` reads, anything else changes |
| `http` | today's rule, kept so existing scripts do not change: `GET` only reads, any other method changes |
| `openapi` operation | `changes` (today every operation is assumed to change) |
| `mcp` tool | from the server's annotations (D28): `readOnlyHint: true` reads; `destructiveHint` or no `readOnlyHint` changes; no annotations changes |
| `class`, a host-registered tool, an unknown kind | `changes` |

- **MCP hints are used** (D28). They are the server author's declaration, read when Loom connects to list the server's tools (`weave check` with the server reachable, or a cached tool list from the last `weave mcp list`). When neither is available the tool is assumed to change, and the explanation says the hints could not be read.
- **`reach:` on class tools** keeps working: `none` and `reads` map to their effect, `fetches` to `reads` (plus the untrusted leg), `writes` and `sends` to `changes` (plus the outward leg for `sends`). `effect:` and `reach:` on one tool is an error that says to keep `effect:`. The guide documents `effect:` and lists `reach:` as the older form.
- **The editor offers to declare it** (D34). A tool whose effect is assumed has a quick fix in VS Code and a `weave review` option (`declare-effect`) that writes `effect: …` with the assumed value pre-filled for the developer to confirm or change.
- **One place decides.** `Capabilities.effect` is computed from the declaration first, then the table, in the one method the audit, the runtime and review already share (`Capabilities.resolve`), so they cannot disagree. Every rule and runtime decision in phases 26 and 27 that says "a tool that changes something" means `effect: changes`: sinks, `approve`'s purpose, LA02, LH02, LH05, the retry rule for non-idempotent tools, and what mock and simulated runs only record.

**R1 `use Tool(...)`: a tool step with no model** (D21).

```loom
use Notify(text = draft) -> receipt retry 1 timeout 30s on_failure { halt "The announcement may not have been sent: {_error}." }
use Search(query = "{topic} pricing") -> found
```

- Runs a declared tool (`tool X { use: … }`, a built-in, an OpenAPI or MCP tool, or a Java tool on the class path) with named arguments, exactly as an agent's call to it would run: the same reach checks (`NetPolicy`, allowed hosts, the file root, the shell allow-list), the same secrets resolution per host, the same journal entries and effect protocol (`effect_pending` and `effect_done`, idempotency keys, no repeat after a crash), the same budgets for tools that spend, and the same trace event `tool_call` with `reach`.
- Arguments are matched against the tool's declared parameters; an unknown or missing required argument is a `weave check` error naming the tool's parameters. Argument values are resolved like a `run` statement's.
- `-> var` binds the tool's result (text, or the parsed object for a tool that returns JSON). `retry`, `backoff` and `timeout` follow the rules that already apply to tools (a non-idempotent tool that changes things is never retried after an unknown outcome; `weave check` refuses `retry` on it, as for tasks).
- `use` is contextual: it is a statement only at statement level, followed by a tool name and `(`. `use partial` in a stop handler and `use:` inside a `tool` block are unchanged (`RepositoryScriptsTest`).
- Under phase 26, a `use` of a tool whose effect is `changes` is a **sink**.
- Under `weave test`, a `use` is scripted with `tools:` like a tool call by an agent; under `weave eval --mock` it answers `[mock tool result]` and a changing tool is described, not run.
- `weave graph` draws a `use` node (new kind `Kinds.USE`, synced with `scripts/sync-graph-render.sh`); eval4j gains a trajectory node kind `use` and `WorkflowAssertions.usesTool(name, times)`.

**R2 `approve` on a step** (D20, D21).

```loom
use Notify(text = draft) approve -> receipt
run IssueRefund(order = action.order, amount = action.amount) approve -> receipt
use Notify(text = draft) approve "Post this to the team channel?" -> receipt
```

- Before the step runs, the person is asked **once**, through the same approval channel as an agent's `approve:` (console, Telegram, a host's `HumanInterface`, suspension and resume), and shown **the exact arguments that will be sent**, with secrets never shown (named by `secret:<name>`) and PII masked by the step's guard where the workflow has one. An optional text after `approve` is the question's heading.
- A yes runs the step. A no fails it with `_error` "not approved by <who>"; `on_failure` runs. A timeout (when the workflow sets one for questions) is a no.
- The approval and the answer are journaled; a resumed run never asks again for an answered step and never runs a step whose answer was no.
- On `run`, `approve` works for any task, alongside `requiresApproval(args)` in Java (if both apply, the person is asked once).
- An approval on a step whose tool's effect is `none` or `reads`, or a task whose effect is `NONE` or `READS`, is allowed and is LH02 (R5).
- No ladder (D20). A step that should earn autonomy uses `decide`.

**R3 LA02 offers the boundary first.** LA02's "What to do" becomes, in this order:
1. For a tool that sends or changes something outside: let the agent propose what to do, approve once at the edge, and act with code: `use Tool(...) approve` or `run Task(...) approve` after the agent (or after an `extractor`, phase 24); or a `decide` if the decision should earn autonomy.
2. Where a person is working with the agent as it goes (an assistant that asks "may I run this?"): name the tool in the agent's `approve:` list.

The rule's condition is unchanged: it still fires on an effect with no approval anywhere.

**R4 LH03 counts every question.** The worst-case question count of one run adds, to `human_prompt` and `decide`:
- for an agent with `approve:` covering at least one tool: the worst-case number of tool rounds (`max_iterations`, or the assumed maximum) × the step's attempts, per delegate;
- for a step with `approve`: one per execution (times loop and `for each` bounds, as for `human_prompt`);
- for `check … ask person` (phase 26): one per execution.
The finding's message names where the questions come from ("up to 40 questions: 20 tool approvals by Sender at line 12, twice in the loop at line 9").

**R5 LH02 covers steps.** LH02 ("asks a person to approve a tool that only reads") also fires for `use … approve` on a tool whose effect is `none` or `reads`, and for `run … approve` on a task whose effect is `NONE` or `READS`.

**R6 LH05: an approval in the middle of an agent's work, in a workflow nobody is watching** (Human oversight lens, **medium**, D22).

> **LH05: Agent `name` asks a person part-way through its work, in a workflow nobody is watching.**
> **Fires when** an agent's `approve:` covers a tool whose effect is `changes`, **and** the workflow runs without a person present: it has a `schedule`, is started by a trigger, or is run by `weave daemon` according to the project's settings.
> **Why it matters:** the run stops in the middle of the agent's reasoning and holds its whole conversation until someone answers, possibly hours later, from a phone. The person sees one tool call without the job it belongs to.
> **What to do:** let the agent propose, then approve once at the edge: `use Tool(...) approve`, `run Task(...) approve`, or a `decide`. In an interactive assistant, where a person is working with the agent, an approval inside the loop is fine, and this rule does not fire.

Fix option **`to-boundary`** (D34; needs thought): when the agent's changing tool is called with arguments that can be named, removes the tool from the agent, adds `use Tool(...) approve` after the delegate with the arguments drawn from an `extractor` over the agent's answer (phase 24), and shows the diff; when the rewrite is not mechanical, the option is not offered and the finding says why.

"Nobody watching" is read from the script and the project, never guessed from prompts: a `schedule` block, a trigger file naming the workflow, or the daemon configuration. A workflow started by hand with `weave run` or by a host application counts as interactive.

**R7 Human oversight on by default** (D22). The Human oversight lens (LH01 to LH05) moves from the optional lenses to the default set. `lenses = -oversight` in `review.properties` restores the old default. LH01 fires only on `decide`, so it never pushes a ladder on a workflow that uses `approve` or `human_prompt`. The review summary card and `docs/review/rules/index.md` no longer mark the lens *(off)*.

**R8 Starter 07 and the skills teach the boundary.**
- Starter `07-approval` is rewritten:

```loom
agent Drafter { model: "gemini-2.5-flash"  system: "Draft a short, polite announcement from the facts you are given."  guard { pii: mask } }
tool Notify { use: webhook  url: env.SLACK_WEBHOOK  format: slack }

workflow Main(facts) {
    delegate "Draft an announcement from: {facts}" to Drafter -> draft
    use Notify(text = draft) approve "Send this to the team channel?" -> receipt retry 1 timeout 30s
        on_failure { halt "The announcement may not have been sent: {_error}. Check the channel before trying again." }
    note "Sent. {receipt}"
}
```

  One question, at the edge, about the exact payload; no model carries out the send; no free-text comparison (LH04 no longer fires). Its tests, README and diagram are regenerated. Its README says why the old shape asked twice.
- `LOOM_GUIDE.md` gains "Calling a tool from the script: `use`" and "Approval on a step"; the approvals section opens with **"Where to ask"**: the three shapes, in this table:

| Need | Shape |
|---|---|
| A plain yes or no before acting | `use Tool(...) approve`, `run Task(...) approve` |
| The person shapes the answer | `human_prompt`, then `alt`, then act |
| Trust earned over time | `decide` with a ladder (optional) |

  and then `approve:` on an agent, for interactive assistants.
- The skills: `llm4j-workflow-guide` (stage 6 and the human-in-the-loop guidance teach the three shapes; LH05 and the counting of approvals named in stage 7); `llm4j-harness-design` (principles: "ask at the boundary, about the payload"; who may step in, stage 7 of its checklist, gains the three shapes; earned autonomy described as optional, D20); `llm4j-loom-deployer` (approvals of `use` and `run` steps on the channel and in Fabric).

**R9 Review stays readable** (D35). This phase switches the first lens on by default, so it also builds what keeps the larger review readable (program page §4.6):
- **Grouped findings**: one line per rule per script with its locations listed, in the text report, the JSON (`groups`) and the editor's panel.
- **New first**: the summary card shows findings that are new since the last review (by fingerprint, as the baseline already computes) before the rest.
- **A baseline suggestion, once**: the first `weave review` of a project after the upgrade that brings new rules says, in one line, that `weave review --baseline write` records what exists today so only new problems are shown; serious findings are never hidden by a baseline, as today. The line is not repeated once a baseline exists or the user declines (`review.properties`: `baseline.suggested = done`).
- LH01 to LH05 each measure ≥ 0.9 precision on the benchmark corpus and pass the noise gate before the lens is switched on.

## 4. Java API

| Type | Change |
|---|---|
| `ast.ToolDef` | `getEffect()` returning `Optional<TaskEffect>` for the declared effect, and `getOperationEffects()` (an unmodifiable map) |
| `security.Capabilities` | `effect` computed from the declaration first, then R0's table; a new `effectSource` (`DECLARED`, `ASSUMED`) and reason |
| `ast.UseStmt` (new, public, final) | tool name, named arguments, variable, retry, backoff, timeout, approve (boolean and optional heading), on_failure |
| `ast.RunStmt` | `isApprove()`, `getApproveHeading()` |
| `execution.UseRunner` (new, package-private) | runs a tool through the same `NamedTool` / `ApprovalTool` / `RecordingTool` path an agent's call takes |
| `HumanInterface` | unchanged (an approval of a step is an approval of a tool call with the arguments shown) |

`docs/API_COMPATIBILITY.md` records `UseStmt` and the `RunStmt` accessors.

## 5. Compatibility

| Change | Existing users |
|---|---|
| `effect:` and `effects:` on tools | New. Undeclared tools keep today's assumed effect, so the audit, approvals and review behave as before; an OpenAPI or MCP tool that declares its read-only operations gets fewer findings. `reach:` keeps working. |
| `use` as a statement, `approve` on a step | New and contextual; existing scripts parse (proved on every `.loom` in the repository). |
| Human oversight lens on | More findings by default (LH01 to LH05). Advisory. `lenses = -oversight` restores. Listed in the release notes. |
| LH03 counting approvals | A script near the limit may get LH03; `oversight.max-questions` is unchanged. |
| LA02, LH02 | Wording; LH02 also fires on approved read-only steps. |
| Starter 07 | Rewritten; a user who copied it keeps a working script. |

## 6. Security notes

- `use` adds no new way to reach the outside: it goes through the same tool objects, policies, secrets resolution and effect protocol as an agent's call. A test asserts the same refusal for each `NetPolicy` case through `use` as through an agent.
- The approval shows the exact arguments that will be sent, so what the person approves is what happens; secrets are named, never shown.
- A no, a timeout or a missing answer never runs the step (fail closed).
- `use` removes a prompt-injection surface from the common "send what was drafted" pattern: the text is an argument, not an instruction to a model that holds the tool.

## 7. Open questions

1. R1: should `use` accept a tool's arguments as one object (`use Notify(draft_message)`) when the tool has one parameter? Proposed: no; named arguments only, as for `run`, so the script reads the same everywhere.
2. R6: which daemon or trigger configuration files mark a workflow as unattended in a project that has several? The inventory in §0 settles the exact sources.
3. R2: should an approval of a step be allowed inside `parallel` (several questions at once)? Proposed: yes, as `human_prompt` is today; LH03 counts them.

## 8. Documentation affected

`LOOM_GUIDE.md` (`effect:` and `effects:` on every tool kind, replacing `reach:` in the class-tool section with the old form noted; `use`, approval on a step, "Where to ask", LA02 wording referenced), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` `docs/guide/09-go-live.md` (approvals on the channel) and `docs/guide/13-earned-autonomy.md` (earned autonomy as the optional extra, D20), the tutorial (§9), starter 07 and the starters README, `docs/review/rules/` (LA02, LH02, LH03, LH05, the lens index), `docs/specs/09-review-engine/DESIGN.md` R33 (a note that the oversight lens is now default, linking here), `docs/security/secure-by-construction.md` (`use` goes through the same controls), the three skills in R8, the VS Code grammar, snippets and `CHANGELOG.md`, `graph-render` (`use` kind), eval4j's README, `docs/API_COMPATIBILITY.md`, `llms.txt`, the next release's notes.

## 9. Tutorial

| Chapter | Change |
|---|---|
| [6. Tools and tasks](../../tutorial/06-tools-and-tasks.md) | New section **"Does this tool change anything? `effect:`"** near the start of "Tools": the three words (the same as a task's), that it is the developer's decision, the per-operation form for OpenAPI and MCP, what Loom assumes when nothing is declared and where to see it, and why it matters (approvals, retries, policies, what a test only records). Each tool example in the chapter declares its effect. New section **"Calling a tool yourself: `use`"** after "Declared tools": when a model is not needed to call a tool, the syntax, and that it goes through the same controls. "Approvals: a person says yes first" shows `approve` on `use` and `run` first, then `approve:` on an agent for interactive assistants. |
| [8. People in the loop](../../tutorial/08-people-in-the-loop.md) | "Approvals on tools" becomes **"Where to ask: at the edge, not in the middle"**: the three shapes in a table and a diagram, why asking mid-loop in an unattended workflow costs momentum and context (LH05), and the rewritten starter 07 walked through. |
| [11. Advanced orchestration](../../tutorial/11-advanced-orchestration.md) | Earned autonomy introduced as the optional extra for a decision that should earn trust, beside the plain `approve` (D20). |
| [9. Safety and cost](../../tutorial/09-safety-and-cost.md) | The safe-by-default checklist: "approve once, at the edge, on the step that acts". |
| [14. Cookbook](../../tutorial/14-cookbook.md) | Recipe **"Approve once, at the edge"**; the cheat sheet gains `effect:`, `effects:`, `use` and `approve` on a step; "mistakes and their fixes" gains LH05 and the LH03 counting. |
| [README](../../tutorial/README.md) | Chapter 6's row names `use`; chapter 8's row says "where to ask". |

Every new `loom` block is checked by `TutorialExamplesTest`; the page is regenerated with `bash scripts/make-tutorial-html.sh`.
