# Design Document

## Overview

Earned autonomy adds one declaration (`decision`), one statement (`decide`), a ledger, a level store, a statistics
function, a replay engine and two command groups (`weave autonomy`, `weave replay`) to Loom. It reuses what exists:
the durable human prompt (a decider is asked without holding a thread), the run journal (a resumed run repeats
nothing), the effect journal and `Effectful` (what is a read and what is not), agent `output_schema`, guards, budgets,
audit and the trigger store directory.

```
 script ──parse──▶ DecisionDef ─┐
                                │ executor: decide
 case ──▶ DecideStep ──────────▶ LevelStore.read ─▶ level ─┬─ shadow: propose (hidden) + ask blind ─▶ verdict = human
                                                          ├─ assist: propose + ask shown          ─▶ verdict = human
                                                          └─ act:    propose (+ audit/limits)     ─▶ verdict = agent | human
                                │
                                ▼
                          Ledger.append (case, verdict, outcome …)
                                │
                  ┌─────────────┴──────────────┐
                  ▼                            ▼
          Stats (Wilson, unsafe, coverage)   Replay engine (candidate script, recorded evidence, stubbed tools)
                  │                            │
                  ▼                            ▼
          LadderEngine: promote/demote   Report (md/json): flips, agreement, cost, level it would earn
```

New code lives in `io.github.llm4j.loom.autonomy` (ledger, levels, statistics, identity, replay) with edits to the
lexer, parser, AST, validator, `HarnessExecutor`, the CLI and one additive method in `ai-agent4j`. Everything else is
untouched, which is what R1.7 and the regression gate check.

## 1. Language

### 1.1 Grammar

```loom
decision Refund {
    agent: Triager
    labels: approve, reject, escalate
    scope: tier                                   // variable; optional
    fields: [amount, reason, customer_since]      // captured as the case's inputs
    unsafe: [approve/reject]                      // proposal/decision pairs that cost the most
    decider: "support-lead"                       // who is asked (the human interface decides how)
    retain: 180 days
    on_change: replay                             // shadow | replay | keep

    autonomy {
        start: shadow
        ceiling: act                              // default assist
        promotion: approval                       // approval | auto
        approver: "risk-owner"

        shadow -> assist { cases: 100  days: 14  agreement: 0.90 }
        assist -> act    { cases: 300  days: 30  agreement: 0.97  max_unsafe: 0 }
        window: 300                               // most recent blind cases that count
        audit: 5%                                 // at act: blind sampling
        limit { where: amount > 200   per_day: 50 }   // sent to a person whatever the level

        demote { unsafe: 2 in 50   reversed: 2 in 100   agreement_below: 0.92   malformed: 5 in 50   to: assist }
    }
    notify: Slack                                 // a tool, called on a level change or proposal
}

workflow Triage(ticket) {
    decide Refund -> verdict
    alt (verdict == "approve") { delegate "Issue the refund for {ticket}" to Payer }
}
```

`decide` has no arguments: the decision declares its `fields` and `scope`, which are read from the workflow variables
in scope when it runs. `verdict`, `verdict_proposal` and `verdict_level` are bound as ordinary variables.

### 1.2 Words

`decision`, `decide` and `autonomy` are **contextual**: the parser treats them as keywords only at the start of a
top-level item (`decision`), at the start of a statement followed by an identifier and `->` (`decide`), and as the
first word of a block inside a `decision` (`autonomy`). In any other position they are identifiers, so existing
scripts keep parsing (R1.7). The lexer therefore gets no new keyword tokens; the parser looks at the identifier text.

### 1.3 Load-time checks (`ScriptValidator`)

Each of R1.3 and R1.4 is a problem with a line number. Two more are worth naming:

- **A decision reaches only what it declares.** `fields` must name variables that are certain to be set where the
  `decide` runs (the validator already tracks variables for `{x}` references; this reuses it).
- **`act` and `unattended` effects.** A decision with `ceiling: act` whose `then` branch calls an effect tool that is not
  approved or marked `unattended` is reported: reaching `act` must not silently remove the last human check on a tool that
  needed one (the shell rule's logic, reused).

## 2. Executing `decide`

Steps, all inside one journaled step id `<step>#decide:<Name>` so a resume repeats none of them:

1. **Case id** = `<runId>/<stepId>` (stepId already includes loop position and branch, as the run journal needs).
2. **Identity** (§5) of the incumbent agent is computed once per executor and cached.
3. **Level**: unless the journal already has `…#level` for this case (a resume), read `LevelStore.get(decision, scope)`,
   apply the epoch rule (§5.2), the freeze, the ceiling and the limits (§4.3), and write the result to the journal.
4. **Propose**: run the agent through the existing delegate path with the case's task text (the decision's `task:`
   template, default `Decide {Name} for this case: {fields as name=value lines}`), using the agent's `output_schema`.
   Every read tool call and result made during this is captured as evidence (§2.2). The proposal is journaled.
5. **Branch on level**:

   | Level | Who decides | Is the proposal shown | Written to the ledger |
   |---|---|---|---|
   | `shadow` | the person | no | case, then verdict |
   | `assist` | the person | yes (label, rationale, confidence) | case, then verdict (not counted as blind) |
   | `act`, normal | the agent | n/a | case with `verdict = proposal`, decider `agent` |
   | `act`, audit | the person | no | as shadow (blind evidence) |
   | `act`, limit | the person | yes | as assist |

6. **Ask** through the existing `HumanInterface.promptHuman(key, question)`, keyed `<step>#decide-ask`, so it pauses
   durably and a resumed run reads the stored answer instead of asking again. The question is built from the fields only
   (shadow, audit) or the fields plus the proposal (assist, limit). The answer is matched to a label (case-insensitive,
   unique prefix); an answer that is not a label is asked again once, then fails the step.
7. **Bind** `verdict`, `verdict_proposal`, `verdict_level`; **trace** and **audit** (`decision_proposed`,
   `decision_decided`, never the proposal text in shadow before the verdict exists).

### 2.1 Blindness

The proposal exists in two places before the person answers: the journal (needed for resume) and the trace. Both must
not leak it. Design rules:

- the proposal is journaled under a key the question builder never reads in shadow or audit mode;
- the trace event for a shadow proposal says only "proposal recorded (hidden)" and the audit event carries no label until the
  verdict is in;
- a console or web human interface receives a `Question` that has only the fields, so it can't show what it wasn't given;
- a test (V4.1) asserts the proposal's label and rationale appear in no string sent to the interface, the trace listener or
  the audit log before the answer.

### 2.2 Evidence

A decorator on the agent's tools during propose records `(tool, canonical args hash, result)` for calls whose tool is a
read (an `Effectful` with `isEffect(args)` false, or one of the built-in pure tools). Effect calls made during a
proposal are not expected (a proposing agent should not change anything); they are executed as normal, and the case is
marked `effects_during_proposal` so replay can refuse to treat it as clean. Evidence is capped (default 64 KB per case,
after masking); beyond the cap the case is marked `evidence_truncated`.

## 3. Ledger

```java
interface Ledger {
    void append(LedgerRecord r);                       // idempotent on r.id()
    Optional<Case> get(String decision, String caseId);
    List<Case> cases(String decision, CaseFilter f);   // scope, epoch, since, blind-only, limit
    long count(String decision, CaseFilter f);
    void purgeOlderThan(String decision, Instant t);   // retention: keep counts
}
```

`LedgerRecord` is one of `CaseOpened`, `Proposed`, `Decided`, `Outcome`, `LevelChanged`, `Frozen`, `PromotionProposed`,
`PromotionDecided`, each with `id` (derived, so a re-append is a no-op), `decision`, `at` and a typed body. A `Case` is the
fold of the records for one case id, in append order. Implementations:

| Impl | Storage | Notes |
|---|---|---|
| `MemoryLedger` | a list | tests, one-shot runs |
| `FileLedger` | `<store>/autonomy/<decision>/ledger.jsonl`, appended with `FileChannel` locking, one record per line | atomic line append; a torn last line (a crash mid-write) is ignored on read and reported by `status` |
| `JdbcLedger` | one table `loom_ledger(decision, id, at, kind, body)`, `PRIMARY KEY (decision, id)` | the same table-creation approach as `JdbcRunJournal`; `INSERT` that ignores duplicates |

A shared `LedgerContract` test class runs against all three (V2).

The level store has the same three implementations behind `LevelStore` (`get`, `compareAndSet(decision, scope, expected,
new, reason)`, `freeze`, `all`). `compareAndSet` is what stops two runs (or a run and `weave autonomy promote`) from
both moving a level.

Where the store is: the CLI's run store (the trigger store's parent, `Runs.defaultStore`), overridable with `--autonomy-store`
or a `ledger:` option; a JDBC URL option uses the same connection settings as `--journal`. The directory is a reserved path
for the `file` tool (R2.7), through the `reservedPaths()` hook added for the run journal.

## 4. Statistics, rules and the ladder

### 4.1 Statistics

One class, `AgreementStats`, pure functions of a list of `(proposal, decision)` pairs:

- agreement = matches / n, over the most recent `window` blind cases of the current epoch and scope;
- **lower bound** = Wilson score interval lower end at 95% (z = 1.959964):
  `(p + z²/2n − z·sqrt(p(1−p)/n + z²/4n²)) / (1 + z²/n)`, with n = 0 giving 0;
- unsafe rate and count from the declared pairs; coverage = share of cases with a proposal other than `escalate`;
- malformed count.

The Wilson interval, not the raw rate: 20 of 20 is not 100% evidence. Its inputs and outputs are checked against
published values (V4.5).

### 4.2 Promotion

`LadderEngine.evaluate(decision, scope)` runs after every `Decided`, `Outcome` and by `status`. For the next step up, it
tests the rule's parts in order (cases, days, lower bound, unsafe) and returns either `Eligible` or the first unmet part
with the numbers, which is what `status` prints. Eligible plus `promotion: approval` creates **one** `PromotionProposed`
(never a second while one is open) and asks the approver through the durable human interface from the workflow that
completed the case, or from `weave autonomy status --ask` when there is no run. `auto` calls `compareAndSet`.

### 4.3 Demotion and limits

`LadderEngine.demotions(scope)` checks each demote clause over its window (counts "n in m": n events among the last m
cases). A match calls `compareAndSet(current → target)` immediately and writes `LevelChanged` with the clause and the
figures. Limits (`where`, `per_day`, budget) are evaluated in step 3 of `decide`, so they apply to the case in hand; the
per-day count is read from the ledger, not kept in memory. `where` is a condition in the existing condition language
(`ConditionEvaluator`) over the captured fields.

### 4.4 Audit sampling

`audit: 5%` sends a case to a person when `hash(caseId + decision + "audit") mod 10000 < 500`. Deterministic, so a resume
makes the same choice, and not manipulable by an agent that cannot see the hash input of a case id it did not choose.

## 5. Identity and epochs

### 5.1 Identity

`AgentIdentity.of(DecisionDef, AgentDef, ScriptContext)` is SHA-256 over a canonical JSON of: the agent's resolved model
name, system prompt text (after persona and template expansion), persona text, temperature, output schema, guard settings,
skills and knowledge sources (name plus content hash for files, URL for remote), each of the agent's tools as
`(name, kind, options without secrets)`, and the decision block with whitespace and comments removed. It is computed on the
AST, not the source text, so comments and formatting do not matter (R6.1). The model name the provider returned for the
call (where available) is added to the case record, not the hash.

### 5.2 Epochs

The level store keeps `(decision, scope) → {level, epoch, identity}`. On step 3 of `decide`, if the identity differs from
the stored one, the engine starts a new epoch with the `on_change` policy:

- `shadow`: `level = start`, evidence of the old epoch is not counted;
- `replay`: run the replay engine (§7) over the old epoch's most recent blind, replayable cases under the new identity, using
  the new agent as the candidate; grade it with the same rules; set `level = min(old level, highest level whose rule it meets)`; record `LevelChanged(reason = replay, report id)`; if fewer than the rule's `cases` are replayable, `level = start`;
- `keep`: level unchanged, a new epoch, a warning at load unless `ceiling <= assist`.

The first case of a new epoch pays for the replay inside its own step, so the change is a visible, journaled, budgeted part of
the run (a rate limit pauses it and it resumes; the replay log is durable, §7.5).

## 6. Operating commands

`weave autonomy` is a picocli subcommand group in `cli/`, each command a thin function over `LevelStore`, `Ledger`,
`LadderEngine` and `AgentStats`:

| Command | Reads | Writes |
|---|---|---|
| `status` | levels, ledger | nothing (`--ask` may create a promotion approval) |
| `history` | ledger | nothing |
| `promote` / `demote` | levels | `compareAndSet` + `LevelChanged`; forced flag if beyond ceiling or evidence |
| `freeze` / `unfreeze` | levels | `Frozen` record + the store's freeze flag |
| `outcome` | ledger | `Outcome` record; may trigger a demotion |

Exit codes follow the existing CLI (0 done, 2 bad options). Output is plain text, with `--json` for scripts.

## 7. Replay

### 7.1 Inputs

For each selected case: the captured fields, the task text, the recorded evidence, the human verdict and the incumbent's
proposal. Selection: filters (`--since`, `--scope`, `--limit`), then a seeded shuffle, then take `limit` (default 500) so a
large ledger is sampled the same way every time for a seed.

### 7.2 Running a candidate

The candidate script is loaded and validated like any script. For each case the engine builds an isolated executor with:

- the candidate's agent for the decision (or the `--policy` text swapped in, §7.6);
- a **replay tool registry** that wraps every tool by class (§7.3);
- an in-memory run journal, a `ReplayEffectContext` whose `simulate()` is true (a default-false method added to
  `EffectContext` in `ai-agent4j`, so `EffectTool` refuses to perform an effect even if a wrapper were missed), a null human
  interface that throws if asked, and a null ledger and level store (it can't write them);
- the same budgets, so `--max-cost` stops it.

It then runs only the propose step (not the workflow around it) and compares the label.

### 7.3 Tool handling

| Tool | In replay |
|---|---|
| `Effectful` tool, call is a read (`isEffect` false) | answered from the case's evidence if the same `(tool, args hash)` was recorded; otherwise the case is *not replayable under this candidate* (unless `--live-reads`: run it, flag the case non-deterministic) |
| `Effectful` tool, call is an effect | stubbed: `(simulated: not performed in replay)` |
| built-in pure tools (`calculator`, `datetime`, `current_time`) | run (they depend on nothing external; `current_time` is answered from the case's timestamp so a replay isn't affected by today's date) |
| anything else (OpenAPI, MCP, Java `class` tools, search) | stubbed, unless the script says `replay: allow` on the tool, which is listed at the top of the report |
| memory and knowledge reads | run against the current store with the report flagged (they aren't versioned); `--no-memory` disables them |

"Stubbed" returns text, so the agent can carry on and propose a label; the report counts proposals made after a stubbed call
separately, since they were made on less information.

### 7.4 Not replayable

A case is skipped with a reason: `evidence_truncated`, `effects_during_proposal`, `unrecorded_read`, `purged`, `fields_masked`
(the masked fields no longer carry what the agent needs). The report shows the count per reason and the share of the ledger
that was replayable, so a good-looking number over a small share can't hide.

### 7.5 Durability

A replay writes `<store>/autonomy/<decision>/replays/<id>/` with `plan.json` (selection, seed, candidate identity),
`log.jsonl` (one result per case, appended as they finish) and the report. A rate limit or `--max-cost` ends the process
cleanly; `weave replay --resume <id>` continues. This reuses the run-pause machinery's contract, not its journal.

### 7.6 Policy replay

An agent prompt may reference a file (`system: file("refund-policy.md")`, or the existing skill and knowledge files). `--policy
<file>` replaces the content of the referenced file whose name matches, for the candidate only, and records both hashes. It
supports "what if the refund limit were 150?" with no script edit. If the script has no such reference, the option is
an error naming that.

### 7.7 Report

Markdown and JSON from one model object, `ReplayReport`. Sections in order: what was replayed and what was not; tools in
replay (stubbed, allowed, live); headline table (incumbent vs candidate: agreement, lower bound, unsafe, coverage, cost); level
the candidate would earn; flips, unsafe first; per-scope tables; repeat stability (if `--repeat`). Flips and rationales pass
through the same masking as the ledger.

## 8. Failure paths

| Situation | Behaviour |
|---|---|
| Ledger or level store unreadable | decision runs at `shadow` (or pauses if the decider is unreachable), audit `autonomy_degraded`; never higher |
| Ledger append fails after the person answered | the verdict stands (it's in the run journal); the case is re-appended on resume; `status` flags journal-only cases |
| Two runs reach `compareAndSet` | one wins; the other re-reads and re-evaluates against the new level |
| Decider never answers | the run pauses as any human prompt does; `status` lists the case as stale after `stale_after` |
| Agent returns an invalid label or no JSON | one retry through the existing `expecting` retry; then `escalate` + `malformed` |
| Agent call fails (model error, budget stop) | the existing `retry`/`on_failure` rules; at `act`, a failed proposal is sent to a person as an `assist` case |
| Budget stops a replay | partial report, marked partial |
| Candidate script doesn't load | `weave replay` exits 2 with the load problems |
| Torn last line in a file ledger | ignored on read, reported, repaired on next append |
| Retention purges cases a ladder's evidence needs | the window shrinks; `status` says cases are below the rule's minimum, nothing is promoted on counts alone |

## 9. Security

The ledger and level store are written only by the runtime and the commands. The agent holds no tool that reaches them (R2.7)
and its output is parsed, not interpreted, for the label; proposals can't carry instructions to the runtime. The decider sees
fields as inert text (R8.3). Replay runs candidate code with simulated effects, and stubs everything not proven to be a read;
the replay tool wrapper is class-based, so a new tool kind is stubbed by default until it opts in. A forced or hand-set level is
marked until the evidence catches up (R8.5).

## 10. Testing seams

- `Clock` (days in rules, retention, stale cases), injected as in the trigger and effect code.
- `Ledger` and `LevelStore` fakes: memory, a fault-injecting wrapper (fail the Nth append, tear a line).
- A scripted human interface that answers per case id, records what it was asked, and can pause.
- A scripted model that returns a label per case (the existing `ScriptedRun` approach), including malformed output.
- A fixed seed for selection, audit sampling and `--repeat`.
- Reference values for Wilson from a published table, and a brute-force reference implementation for property tests.

## 11. Decisions and alternatives

| Decision | Alternative | Why |
|---|---|---|
| A declaration plus a `decide` statement | a library function the author calls, or a flag on `delegate` | The rules (thresholds, ceiling, on_change) are policy and belong in the reviewable script; a statement gives `verdict` as a first-class variable |
| Blind shadow | show the proposal and ask "agree?" | Agreement measured with the answer in view is inflated; the numbers would mean nothing |
| Wilson lower bound | raw agreement | A small run of agreement is not evidence; the bound makes "more cases" matter |
| Per-scope ladders | one ladder per decision | A model can be good at small refunds and bad at large ones; autonomy should follow the evidence |
| Promotion needs approval by default; demotion never does | symmetric | Moving up is a risk decision; moving down is a safety action and must be immediate |
| Default ceiling `assist` | default `act` | Reaching `act` should be a sentence in the script that someone read |
| New epoch on any identity change, with replay to inherit | keep the level across versions | A new model has not earned the old one's record; replay lets it earn it from history before it runs live |
| Replay stubs by class, default deny | an allow-list per tool of "dangerous" ones | New tools are safe in replay by default; a missed classification fails safe |
| Evidence captured at propose time | re-run reads live in replay | Live reads change with time, so replay would answer a different question |
| Append-only ledger, folded on read | mutable case rows | Audit and replay need what was true then; later facts are new records |
| Contextual keywords | reserved words | Existing scripts, including users', must keep loading |

## 12. Open questions

- **Several deciders.** v1 records one name per case. If a decision is made by whichever person is on shift, agreement mixes
  their habits. A later version could add per-decider breakdowns to `status` (data is already recorded).
- **Reversal sources.** v1 takes outcomes by command or statement. A pull-based `outcome_source:` tool (poll a payments API
  for chargebacks) is a natural follow-up using the generic tools.
- **Non-categorical decisions.** A number (an amount to refund) or free text isn't a label. v1 is labels only; a numeric
  tolerance rule is a follow-up.
- **Drift of the cases themselves.** A ladder trained on last quarter's mix can be wrong this quarter. v1 offers the window
  and `days:`; a distribution-shift warning is a follow-up.
