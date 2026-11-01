# Phase 27 design: approval at the boundary

Status: **draft for review** (decisions D20 to D22 recorded 2026-11-01). Layer: the Loom language and runtime (a tool step, approval on a step), the review engine (LA02, LH02, LH03, a new LH05, the Human oversight lens on), the starters, the skills and the tutorial. Part of the [engineering discipline program](../DISCIPLINE.md).
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Independent of phases 24 to 26 to build; once [phase 26](../26-policy-boundary/DESIGN.md) exists, `use` is one of its sinks.
Evidence owned: **E14 to E18**. Mistake: **M4, HITL in the middle of reasoning**.

## 1. Problem

The owner's rule: a person is asked at the boundary, about a risky or irreversible action, not in the middle of an agent's reasoning. Loom's earned autonomy (`decide` with `watch`, `suggest`, `act`) already does this well for teams that use it. But not every workflow uses earned autonomy (D20), and for those the path Loom offers is the middle one:

- LA02's only advice is to put the tool in the agent's `approve:` list (E14), which stops the agent mid-loop, holds its conversation, and shows the person one tool call without the context of the job.
- LH03 does not count those approvals (E15), and the whole Human oversight lens is off (E16).
- A script cannot call a tool itself (E17): an approved action must be handed back to a model to carry out, which is why starter 07 uses an LLM agent to post a webhook and asks the person twice.
- A task can ask for approval, but only from Java (E18).

## 2. Goals and non-goals

Goals:

- A script can **call a tool directly**, with no model (R1).
- A script can **ask for approval on a step**, once, showing the exact payload, on `use` and on `run` (R2).
- Review offers the boundary first, counts every question, and warns about approvals in the middle of a loop where nobody is watching (R3 to R6).
- Starter 07 and the skills teach the boundary shapes (R7, R8).

Non-goals:

- A ladder on `approve:` (D20). Earned autonomy stays on `decide`.
- Removing or discouraging `approve:` on an agent's tools for interactive use. It stays first-class (D20).
- A new kind of question. `approve` uses the existing approval channel, suspension and resume.

## 3. Requirements

**R1 `use Tool(...)`: a tool step with no model** (D21).

```loom
use Notify(text = draft) -> receipt retry 1 timeout 30s on_failure { halt "The announcement may not have been sent: {_error}." }
use Search(query = "{topic} pricing") -> found
```

- Runs a declared tool (`tool X { use: … }`, a built-in, an OpenAPI or MCP tool, or a Java tool on the class path) with named arguments, exactly as an agent's call to it would run: the same reach checks (`NetPolicy`, allowed hosts, the file root, the shell allow-list), the same secrets resolution per host, the same journal entries and effect protocol (`effect_pending` and `effect_done`, idempotency keys, no repeat after a crash), the same budgets for tools that spend, and the same trace event `tool_call` with `reach`.
- Arguments are matched against the tool's declared parameters; an unknown or missing required argument is a `weave check` error naming the tool's parameters. Argument values are resolved like a `run` statement's.
- `-> var` binds the tool's result (text, or the parsed object for a tool that returns JSON). `retry`, `backoff` and `timeout` follow the rules that already apply to tools (a non-idempotent tool that changes things is never retried after an unknown outcome; `weave check` refuses `retry` on it, as for tasks).
- `use` is contextual: it is a statement only at statement level, followed by a tool name and `(`. `use partial` in a stop handler and `use:` inside a `tool` block are unchanged (`RepositoryScriptsTest`).
- Under phase 26, a `use` of a tool whose reach is outward or that changes something is a **sink**.
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
- An approval on a step whose tool reach is none or reads only, or a task whose effect is `NONE` or `READS`, is allowed and is LH02 (R5).
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

**R5 LH02 covers steps.** LH02 ("asks a person to approve a tool that only reads") also fires for `use … approve` on a tool whose reach is none or reads, and for `run … approve` on a task whose effect is `NONE` or `READS`.

**R6 LH05: an approval in the middle of an agent's work, in a workflow nobody is watching** (Human oversight lens, **medium**, D22).

> **LH05: Agent `name` asks a person part-way through its work, in a workflow nobody is watching.**
> **Fires when** an agent's `approve:` covers a tool whose reach is outward or that changes something, **and** the workflow runs without a person present: it has a `schedule`, is started by a trigger, or is run by `weave daemon` according to the project's settings.
> **Why it matters:** the run stops in the middle of the agent's reasoning and holds its whole conversation until someone answers, possibly hours later, from a phone. The person sees one tool call without the job it belongs to.
> **What to do:** let the agent propose, then approve once at the edge: `use Tool(...) approve`, `run Task(...) approve`, or a `decide`. In an interactive assistant, where a person is working with the agent, an approval inside the loop is fine, and this rule does not fire.

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

## 4. Java API

| Type | Change |
|---|---|
| `ast.UseStmt` (new, public, final) | tool name, named arguments, variable, retry, backoff, timeout, approve (boolean and optional heading), on_failure |
| `ast.RunStmt` | `isApprove()`, `getApproveHeading()` |
| `execution.UseRunner` (new, package-private) | runs a tool through the same `NamedTool` / `ApprovalTool` / `RecordingTool` path an agent's call takes |
| `HumanInterface` | unchanged (an approval of a step is an approval of a tool call with the arguments shown) |

`docs/API_COMPATIBILITY.md` records `UseStmt` and the `RunStmt` accessors.

## 5. Compatibility

| Change | Existing users |
|---|---|
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

`LOOM_GUIDE.md` (`use`, approval on a step, "Where to ask", LA02 wording referenced), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` `docs/guide/09-go-live.md` (approvals on the channel) and `docs/guide/13-earned-autonomy.md` (earned autonomy as the optional extra, D20), the tutorial (§9), starter 07 and the starters README, `docs/review/rules/` (LA02, LH02, LH03, LH05, the lens index), `docs/specs/09-review-engine/DESIGN.md` R33 (a note that the oversight lens is now default, linking here), `docs/security/secure-by-construction.md` (`use` goes through the same controls), the three skills in R8, the VS Code grammar, snippets and `CHANGELOG.md`, `graph-render` (`use` kind), eval4j's README, `docs/API_COMPATIBILITY.md`, `llms.txt`, the next release's notes.

## 9. Tutorial

| Chapter | Change |
|---|---|
| [6. Tools and tasks](../../tutorial/06-tools-and-tasks.md) | New section **"Calling a tool yourself: `use`"** after "Declared tools": when a model is not needed to call a tool, the syntax, and that it goes through the same controls. "Approvals: a person says yes first" shows `approve` on `use` and `run` first, then `approve:` on an agent for interactive assistants. |
| [8. People in the loop](../../tutorial/08-people-in-the-loop.md) | "Approvals on tools" becomes **"Where to ask: at the edge, not in the middle"**: the three shapes in a table and a diagram, why asking mid-loop in an unattended workflow costs momentum and context (LH05), and the rewritten starter 07 walked through. |
| [11. Advanced orchestration](../../tutorial/11-advanced-orchestration.md) | Earned autonomy introduced as the optional extra for a decision that should earn trust, beside the plain `approve` (D20). |
| [9. Safety and cost](../../tutorial/09-safety-and-cost.md) | The safe-by-default checklist: "approve once, at the edge, on the step that acts". |
| [14. Cookbook](../../tutorial/14-cookbook.md) | Recipe **"Approve once, at the edge"**; the cheat sheet gains `use` and `approve` on a step; "mistakes and their fixes" gains LH05 and the LH03 counting. |
| [README](../../tutorial/README.md) | Chapter 6's row names `use`; chapter 8's row says "where to ask". |

Every new `loom` block is checked by `TutorialExamplesTest`; the page is regenerated with `bash scripts/make-tutorial-html.sh`.
