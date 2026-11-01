# Phase 26 design: the policy boundary

Status: **draft for review** (decisions D9 to D16 recorded 2026-11-01; **one new decision, D25, is needed before building starts**: see §7). Layer: the Loom language, validator and runtime (value provenance, a new internal node, a new statement, a guard); the review engine and audit; eval4j; the VS Code extension and graph renderer; the skills. Part of the [engineering discipline program](../DISCIPLINE.md); read its §3 and §4 first.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Depends on [phase 24](../24-stateless-nodes/DESIGN.md) (the internal-node contract, the extractor's typed fields). Depends on [phase 27](../27-boundary-approvals/DESIGN.md)'s R0 (every tool's effect, which defines the tool sinks). Used by phase 27 (`use` is a sink) and [phase 28](../28-evaluation-first/DESIGN.md) (the gate on untested evaluators and policy rules).
Evidence owned: **E10, E11**. Mistake: **M1, the god prompt**.

## 1. Problem

The owner's rule: governance does not belong in the reasoning prompt. Compliance is checked by a separate, hidden evaluator at the boundary, before anything reaches a person. Loom has no such evaluator. Nothing checks content while a run is going: guards cover PII and bias only, `supervise` counts and never judges, and LLM judges exist only offline (E11). Meanwhile the language makes the god prompt the easy path: the guide's own `shared_prompt` example is a governance rule sent to every agent, and preflight C4's advice when a model ignores it is to copy the rule into the agent's own prompt (E10). A reply that quotes "Section 4.6" or recites its instructions goes straight out.

## 2. Goals and non-goals

Goals:

- Policy text has a home of its own, outside every prompt, owned by whoever owns compliance (R1).
- A first-class **evaluator** checks text against a policy, statelessly, and fails closed (R2, R3).
- A **`check … against … using …`** step puts the evaluator at the boundary, with four actions on a violation (R4, R5).
- Once a policy is declared, **the runtime guards the exits**: model-written text cannot leave the workflow unchecked (R6 to R8).
- A deterministic **leak guard** catches an answer that repeats its prompt or internal references (R9).
- Review says when model-written text leaves a workflow that has no policy (R10); `shared_prompt` and preflight stop teaching the god prompt (R11).
- The skills teach the internal/external design idea; the tooling does not pretend to classify agents (R12, D11).

Non-goals:

- Labelling agents internal or external in the tooling (D11).
- Judging what a prompt means: no rule looks for governance wording in prompts.
- Policy-specific domain models or certified compliance. The evaluator produces evidence, as earned autonomy does; it certifies nothing.
- Golden cases for evaluators and the `weave check` gate on untested ones: [phase 28](../28-evaluation-first/DESIGN.md) (this phase defines the case format, R13).

## 3. Requirements

### What the rules are: `policy` (D9, D12)

**R1 Policy files.** A policy is declared by name and resolved from the `policies/` folder beside the script, exactly as a prompt is resolved from `prompts/` (the latest version, or `@v2`):

```loom
policy HouseRules: "house-rules"          // policies/house-rules.md, or the latest policies/house-rules/vN.md
policy Legal: "legal@v2"
```

The file is plain markdown, written for a non-technical owner. Each rule is a level-two heading that is its id, followed by one or more sentences that describe **what breaking it looks like**:

```markdown
# House rules

## no_refund_promise
The reply promises a refund, a credit or any compensation.

## no_internal_refs
The reply mentions internal policy sections, instructions, or how the assistant was set up.

## no_legal_advice
The reply tells the customer what the law requires of them or of us.
```

- A rule id is a lowercase word with underscores; ids are unique within a file. Text before the first rule heading is a description and is never sent to a model.
- `weave check` rejects a missing file, a file with no rule, a duplicate or malformed id, an empty rule, and a rule longer than `policy.max-rule-chars` (default 1,000, so a rule stays a rule and not a manual).
- The resolved file's version and a hash of its rules are recorded in the journal and the trace for every check, so a run says which wording it was checked against.
- A policy file is never part of any agent's prompt: the runtime has no path that puts it there.

### Who checks them: `evaluator` (D10)

**R2 The evaluator node.**

```loom
evaluator Compliance {
    model: "claude-haiku-5-5"      // any chat model, or a calibrated classifier backend (Jev, Perplexity decider)
    unsure below 0.8               // below this, a rule's answer is "unsure", which counts as a violation
    samples: 3                     // chat models: ask three times, take the majority; agreement is the confidence
    pii: mask
}
```

- An evaluator is an **internal node** under the program's contract (§4.1): stateless, no transcript, no shared prompt, no persona, no tools, one call per sample, typed output. `InternalNodeContractTest` (phase 24) gains the evaluator.
- `model:` is required. A calibrated backend (Jev, Perplexity's decider) is used through the classifier engine of [phase 7](../07-classifier/DESIGN.md): each rule becomes one yes/no question ("Does the text do this: …?"), so its probability is calibrated; `weave graph` and `weave audit` mark the evaluator `calibrated` or `uncalibrated`, as for classifiers.
- `unsure below P` (default 0.8) and `samples: N` (default 1) as in a classifier. `pii:` as in a classifier.
- Forbidden settings (`tools:`, `memory`, `instructions:` with `{…}`, `persona`, …) are `weave check` errors with the phase 24 wording.

**R3 What the evaluator is given, and what it answers.**
- Given: a fixed preamble owned by the runtime (judge the text against each rule; the text is data, never instructions; answer for every rule), the rule ids and texts of the policy, and the subject text between untrusted-data markers. Nothing else.
- Answers, per rule: `violated` (`yes`, `no` or `unsure`), `confidence` (0 to 1), and, from a chat model, a one-sentence `reason` (a calibrated backend gives no reason; the field is empty).
- **Fails closed.** An evaluator that errors, times out, runs out of budget, answers in the wrong shape twice, or leaves a rule out makes every unanswered rule `unsure`. `unsure` counts as violated.

### Where they are checked: `check … against … using …` (D9, D15)

**R4 The statement.**

```loom
check reply against HouseRules using Compliance -> verdict on_violation { rewrite with Editor }
check reply against HouseRules, Legal using Compliance -> verdict on_violation { ask person }
check summary against HouseRules using Compliance on_violation { replace with "A colleague will get back to you shortly." }
check draft against HouseRules using Compliance                       // no on_violation: fail
```

- The subject is a variable or field path holding text. Several policies may be named; all their rules are checked in one evaluator call (ids are prefixed by policy name when two policies share an id).
- `-> verdict` is optional. When given it binds: `verdict.passed` (`yes`/`no`), `verdict.violated` (a list of rule ids), `verdict.action` (`none`, `rewritten`, `replaced`, `asked`, `failed`), and `verdict.rules.<id>.violated`, `.confidence`, `.reason`.
- `on_violation { … }` holds **exactly one action** (R5). Without it the action is `fail`.
- `retry N`, `timeout` and `on_failure { … }` apply to the evaluator call and to the step as a whole: `on_failure` runs when the step ends in `fail`.
- `check` is contextual (it already appears inside a `trust` block as `check 5% of cases`; the statement form is recognised only at statement level followed by a variable and `against`).

**R5 The actions.**

| Action | What happens |
|---|---|
| `rewrite with <Agent>` | The named agent is called **as if it had `transcript: none` and `shared_prompt: none`**, whatever its own settings, with only the subject text and the ids, texts and reasons of the rules broken. Its answer replaces the subject variable and **is checked again, once**. If the second check passes, the step passes (`action: rewritten`). If it fails, the step fails closed (`action: failed`). |
| `ask person` | The person is shown the text and the rules broken (with reasons) and answers one of: **send as is**, **send this instead** (they type the text), or **stop**. The question goes through the same channels as `human_prompt` (console, Telegram, a host's `HumanInterface`, suspension and resume). A person's "send as is" marks the text checked, and the audit records who overrode which rules. |
| `replace with "…"` | The subject variable is replaced by the fixed text (`action: replaced`). The text may use `{…}` only for values that are not model-written (R7); `weave check` enforces it. |
| `fail` | The step fails (`action: failed`): `on_failure` runs with `_error` naming the rules broken, never the text; without `on_failure` the run fails. |

### The runtime guards the exits (D13, D14)

**R6 Sinks.** A sink is where a value leaves the workflow for people or the outside world:
1. the `return` of a workflow run as an entry point (by `weave run`, a trigger, a schedule, a daemon or a host's `LoomRun`);
2. a message the runtime sends on a channel to users that is not a question (a `notify` from a host channel, where present);
3. the arguments of a tool whose effect is `changes` ([phase 27 R0](../27-boundary-approvals/DESIGN.md): declared by the developer, or assumed), called by `use` ([phase 27](../27-boundary-approvals/DESIGN.md)) or by an agent (R8);
4. the arguments of a task whose effect is `CHANGES`, called by `run`.

Not sinks (D13): the text of an approval, a `human_prompt`, an `ask person` question, a `note`, a trace or the journal. Those reach an operator, who is meant to see the workflow's own working.

**R7 Model-written values, by type (D14).** The runtime tags every value it holds with its provenance, per variable and per field:

| Model-written (needs a check at a sink) | Not model-written |
|---|---|
| An agent's answer (prose) | A classifier label, an `enum` field, a number, true/false |
| A `string` field (at any depth, including list elements) of an extractor or an agent's schema answer | Workflow inputs, constants, values typed by a person |
| The `reason` of a classifier, evaluator or verdict | The result of a task that does not use a model |
| The result of a `Task.withModel` task (`usesModel()`) | A `replace with` text built only from values that are not model-written |
| Any string built by `set`, interpolation, `return { … }` or a `call` argument from a model-written value | |

- Provenance follows values through `set`, string interpolation, field paths, lists, objects, `call` arguments and results, `parallel` and `for each`, `rewind` (restored with the value), journal replay (recorded with the value) and resume.
- A value **passes** a check when it is the subject of a `check … against` that ended without a violation, or was rewritten and re-checked, or was sent as is by a person. A value built from checked values and values that are not model-written is checked. Editing a checked value with new model-written text makes it unchecked again.
- The tag is internal state. It never appears in a trace, a journal entry, a variable or a `toString` as text other than a name.

**R8 Enforcement.** Once the script declares at least one `policy`:
- **At run time**, a model-written value that has not passed a check against **every declared policy** fails the step that tries to send it to a sink, with: "`reply` was written by a model and has not been checked against HouseRules. Add `check reply against HouseRules using …` before this step." The step's `on_failure` runs if it has one. Always on, fail closed; no setting turns it off while a policy is declared.
- **Statically**, `weave check` follows each model-written value from where it is bound to each sink it can reach and reports an **error** for any path with no `check` against every declared policy, naming the line of the sink and the line of the agent. Paths it cannot follow (a dynamic `call`, a host's own channel) are left to the runtime and listed by `weave check --explain-sinks`.
- **An agent's own outward tool calls** (arguments written by the model inside its loop, where no `check` statement can stand): **decision D25 needed** (§7, question 1). Proposed: under a declared policy, an agent may call an outward or changing tool only if that tool is in its `approve:` list (a person sees the exact arguments, which counts as a person's check, recorded in the audit); otherwise `weave check` reports an error whose fix is to move the call to the boundary: the agent proposes, `check` runs, `use Tool(...)` sends (phase 27).
- A script with no policy behaves exactly as today (R10 is advice only).

### The leak guard (D16)

**R9 `guard { prompt_leak: warn | block }`.** A deterministic check, with no model, on an agent's answer before it is stored:
- **Repeated instructions**: the answer shares a run of at least `prompt_leak_words` (default 12) consecutive words, compared case-insensitively with whitespace and punctuation folded, with the agent's own system prompt, the shared prompt, any persona or skill text, or the text of any declared policy rule.
- **Internal references**: the answer matches one of the agent's `internal_refs: ["Section \\d+(\\.\\d+)*", "POL-\\d+"]` patterns. Patterns are checked by `weave check` (length at most 200, must compile), and each match runs under a time limit; a pattern that times out counts as a match (fail closed) and is reported once.
- `warn` records a `prompt_leak` guard event (with the kind and the number of words or the pattern index, never the text) and carries on. `block` fails the step with `_error` naming the kind.
- **Default** (D16): when the script declares a policy, every agent has `prompt_leak: warn` unless it says otherwise; `block` is opt-in; `prompt_leak: off` is allowed and is a review finding (LY07's page explains it). Without a policy the guard is off unless an agent asks for it.
- Internal nodes are not guarded (their output is typed and never prose for a person).

### Review, prompts and preflight

**R10 LY07: model-written text leaves the workflow and the script declares no policy** (Privacy and compliance lens, **medium**). One finding per script (not per path), listing the paths it found (agent → sink, with lines), so it is a single, actionable line in a hello-world and not a wall. What to do: declare a policy and check at the boundary, or accept with a reason ("an internal tool; no customer sees its output").

**R11 `shared_prompt` and preflight stop teaching the god prompt.**
- `LOOM_GUIDE.md`, `LOOM_PROMPT.md` and the tutorial describe `shared_prompt` as **tone, house style and shared facts**. The example becomes `"We write for Acme's customers: plain English, short sentences, no jargon."` and the section says, in one sentence, that rules about what must never be said belong in a policy checked at the boundary, with a link.
- Preflight C4's advice on `WARN` and `FAIL` becomes: "The model follows the shared prompt loosely. If it holds a rule that matters, put the rule in a policy and check replies against it (`check … against`); a prompt is a wish, not a guarantee."

### The skills (D11)

**R12 The design idea lives in the skills.** The tooling never classifies agents. The skills teach it:
- **`llm4j-workflow-guide`**: during agent design (stage 1), ask, for each agent, "do its words reach a person?"; those agents get tone and task only; governance goes in a policy file written with the person who owns compliance; internal work goes in internal nodes (`intentclassifier`, `extractor`, `evaluator`); place `check … against` before every exit; review `weave check`'s sink errors with the user. Stage 7 (validate and audit) names LY07 and the leak guard.
- **`llm4j-harness-design`**: principles §3 (boundary) gains "external and internal agents" (by audience, D11), "policies are checked at the edge, not written into prompts", and "an evaluator fails closed"; the surface table gains a column "words reach a person". `references/loom-mapping.md` maps each to Loom.
- **`llm4j-eval-design`**: an evaluator is an LLM judge running in production; design its validation like a judge's (cases per rule that break it, nearly break it, and are clean; agreement with a person; the unsure rate), and the near-miss cases are mandatory.
- **`llm4j-loom-deployer`**: a policy override (`ask person` answered "send as is") is an audit event to watch; the Fabric page shows checks and overrides.

### Testing support

**R13 Tests and evaluation.**
- `weave test` scripts evaluators per rule (`evaluators: { Compliance: { no_refund_promise: violated } }`), and asserts `expect: { checks: { HouseRules: 1 }, actions: { rewritten: 1 } }`.
- eval4j: trajectory node kind `check`; `WorkflowAssertions.checksPolicy(name)`, `violated(ruleId)`, `actionTaken(action)`.
- The golden-case format for an evaluator, used by `weave eval` and gated in phase 28: `eval/golden/<evaluator>.yaml` with cases `{ id, policy, text, expected: { <rule>: violated | clean } }`, where `clean` cases that nearly break a rule are tagged `near_miss: true`.
- Mock runs (`weave eval --mock`, `weave test` default): an unscripted evaluator answers `no` for every rule, and the report says the checks were not judged.

### Observability

**R14 Trace, audit, graph.**
- Trace event `policy_check`: policy names, versions and hashes, evaluator, model, calibrated or not, rules violated (ids), unsure count, action, attempts, duration and tokens. Never the subject text, never a reason (reasons are model-written text; they are available in the `verdict` variable and in the run's own journal only).
- Trace event `sink_blocked` when R8 stops a step: the sink kind, the variable name, the policies missing.
- Audit events `policy_violation`, `policy_override` (with who answered), `prompt_leak`.
- `weave graph` and the VS Code graph draw a `check` node (new kind `Kinds.CHECK`, synced with `scripts/sync-graph-render.sh`) with labelled edges for its action. `weave explain` says each check in words.
- The live run page and Fabric count checks, violations by rule id, overrides and blocked sinks.

## 4. Java API

| Type | Change |
|---|---|
| `ast.PolicyDef`, `ast.EvaluatorDef`, `ast.CheckStmt` (new, public, final) | as in R1, R2, R4; `CheckStmt.Action` is a sealed interface with records `Rewrite(String agent)`, `AskPerson()`, `Replace(String text)`, `Fail()` |
| `ast.LoomScript` | `getPolicies()`, `getEvaluators()`; `merge` keeps them (tested) |
| `ast.AgentDef` guard | `promptLeak` (`OFF`, `WARN`, `BLOCK`, or unset meaning the default), `internalRefs` |
| `policy.PolicyFile` (new, public) | parses a policy markdown file into rule ids and texts; a public parser so tools and the editor show the same rules |
| `execution.Provenance` (new, package-private) | the value tags of R7 |
| `execution.CheckRunner`, `execution.EvaluateRunner` (new, package-private) | |
| `HumanInterface` | gains a default method for the `ask person` question, so existing implementations keep compiling (they get a plain yes/no fallback: "send as is" or "stop") |

All new public types have Javadoc. `docs/API_COMPATIBILITY.md` records the new types and the default method.

## 5. Compatibility

| Change | Existing users |
|---|---|
| New keywords (`policy`, `evaluator`, `check … against`, `on_violation`, `rewrite with`, `replace with`, `ask person`) | Contextual; existing names parse (proved on every `.loom` file in the repository). |
| Enforcement (R8) | Only for scripts that declare a policy. No existing script does. |
| Leak guard default | Only for scripts that declare a policy. |
| LY07 | A new medium finding for most existing scripts that return agent text. Advisory; one finding per script. `LY07 = off` or an acceptance restores the old output. Listed in the release notes. |
| `shared_prompt` docs, C4 advice | Wording only. |
| `HumanInterface` | A new default method; source and binary compatible. |

## 6. Security notes

- The evaluator fails closed on every failure path (R3), and an unanswered rule is a violation.
- Policy text is never put into an agent's prompt by the runtime, and the leak guard counts policy text as something an answer must not repeat.
- The evaluator and the rewriter see only the subject and the rules: no transcript, so an earlier injected instruction cannot reach them (the contract of phase 24).
- Provenance (R7) is internal and cannot be set by a script, a tool, a task or a value. A value cannot claim to be checked.
- A Java task that does not use a model is trusted code: its result is not model-written (D14). A task that copies an unchecked argument into its result can therefore move model text past the boundary. This is the same trust Loom already places in tasks; the review flags a `run` whose arguments are unchecked model-written values and whose result reaches a sink (open question 3), and the guide says so.
- `internal_refs` patterns are bounded in length and in time; a timeout fails closed.
- `docs/security/owasp-llm-top-10.md`: the boundary maps to LLM02 (sensitive information disclosure), LLM05 (improper output handling) and LLM07 (system prompt leakage); `secure-by-construction.md` gains "policies are enforced at the exits, not in prompts".

## 7. Open questions

1. **D25 (decision needed before building): an agent's own outward tool calls under a policy.** The arguments are written by the model inside its loop, so there is no place for a `check` statement. Options: (a) **proposed**: allowed only with `approve:` on that tool (a person's check), otherwise a `weave check` error whose fix is propose, `check`, then `use Tool(...)`; (b) the runtime checks the tool's text arguments against every policy automatically, using an evaluator the policy names (`policy HouseRules: "house-rules" checked by Compliance`), before the call; (c) both: (b) by default, (a) as the way to skip the automatic check with a person instead. (a) keeps every check visible in the script and matches phase 27; (b) is more convenient but adds a hidden model call to every tool call.
2. Should a policy also serve as a judge in `weave eval` (`expect_policy: HouseRules` in a workflow case), so the runtime and the tests check against the same rules? Proposed: yes, in phase 28, reusing the evaluator.
3. R7 and the trusted-task note in §6: should review flag a `run` whose arguments are unchecked model-written values and whose result reaches a sink (a possible laundering path)? Proposed: yes, as a low finding in the same lens as LY07.
4. R6.2: which host channels send non-question messages today? The inventory in §0 of the verification settles whether this sink exists yet or is only reserved.

## 8. Documentation affected

`LOOM_GUIDE.md` (a new section "Policies: rules checked at the edge" with `policy`, `evaluator`, `check … against`, actions, sinks and the type rule; the leak guard under "Agent Guards"; "A prompt every agent starts with" rewritten per R11; internal nodes gain the evaluator), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` and `docs/guide/07-validate-and-audit.md`, the tutorial (§9), a new starter **`16-compliance-boundary`** (a support reply checked against a house-rules policy with a rewrite, its tests, README and diagram) and the starters README, `docs/review/rules/` (LY07), `docs/security/weave-audit.md`, `owasp-llm-top-10.md`, `secure-by-construction.md`, `SECURITY.md` (the boundary in one paragraph), the four skills in R12, the VS Code grammar, snippets (a `policy` file snippet too) and `CHANGELOG.md`, `graph-render` (`check` kind), eval4j's README, the Fabric and inspect pages that list trace events (`wiki/Observability-*.md`), `docs/API_COMPATIBILITY.md`, `llms.txt`, and the next release's notes.

## 9. Tutorial

| Chapter | Change |
|---|---|
| [3. Agents](../../tutorial/03-agents.md) | "A prompt every agent starts with" rewritten per R11 (tone and shared facts; rules go in a policy). The internal/external section from phase 24 gains the evaluator and the sentence "an agent whose words reach a person gets tone and task, never the rules". |
| [9. Safety and cost](../../tutorial/09-safety-and-cost.md) | New section **"Policies: rules checked at the edge"**, after "Guards": a policy file a non-technical owner can read, the evaluator, `check … against`, the four actions with a diagram of the boundary, what a sink is, the type rule in a short table, what fails closed, and what LY07 means. "Guards" gains the leak guard. "Everything in one place: the safe-by-default checklist" gains "declare a policy and check every exit". |
| [10. Testing](../../tutorial/10-testing.md) | Scripting an evaluator in `weave test`; asserting checks and actions. (Golden cases for evaluators come with phase 28.) |
| [14. Cookbook](../../tutorial/14-cookbook.md) | Recipe **"Check every reply against house rules"** (built on starter 16); the cheat sheet gains `policy`, `evaluator`, `check … against`, `on_violation` and the actions, `prompt_leak`, `internal_refs`; "mistakes and their fixes" gains the `weave check` sink error and LY07. |
| [README](../../tutorial/README.md) | Chapter 9's row names policies; the starter table gains starter 16; "How a Loom project fits together" shows the policy file beside `main.loom`. |

Blocks that need a policy file beside them are marked `loom-parse`, and starter 16 carries the complete, loadable version. Every block is checked by `TutorialExamplesTest`; the page is regenerated with `bash scripts/make-tutorial-html.sh`.
