# Design Document

## Overview

Today a `decision` carries its own rules: `DecisionDef` holds `UpRule`, `DropRule`, `Mistake` and the other fields as records, and `Engine`/`Ladder`
evaluate that fixed shape over `Case` values whose proposal and verdict are strings. This design keeps the runtime around the ledger (storage,
identity, blindness, levels, replay) and replaces the fixed shape with three things:

1. a **`ladder`** definition (rungs, modes, climb/slip expressions) bound to a decision,
2. a small **total expression language** over the ledger, with `measure` definitions,
3. a **generalised case record** that holds artifacts, three kinds of response, and late outcomes.

The existing `trust` block is **desugared** into a ladder, so there is one engine and one set of tests (R10.3).

```
 script ──parse──▶ LadderDef, MeasureDef, DecisionDef(binding)
                         │ load-time: names, types, modes, bindings (ScriptValidator)
                         ▼
 decide ──▶ Engine ──▶ Rung (mode enforced) ──▶ Ledger.append(Rec…)
                         ▲                              │
                         │ level change           Cases.fold ─▶ History (epoch-filtered)
                    LadderEval ◀── Expr (typed AST) ◀──────┘
                    (climb / slip / escalate / sample)
```

## 1. Language

### 1.1 Grammar

```
ladder      := 'ladder' NAME ['(' NAME {',' NAME} ')'] '{' {rung | setting} '}'
rung        := 'rung' NAME '{' 'mode:' MODE {clause} '}'
clause      := 'climb' 'when' expr
             | 'slip' ['to' NAME] 'when' expr
             | 'sample' PERCENT 'blind'
             | 'escalate' 'when' expr
setting     := 'climbing' 'needs' 'approval' 'from' NAME | 'climbing' 'is' 'automatic'
measure     := 'measure' NAME '(' NAME ')' '=' expr
binding     := 'ladder:' NAME ['(' NAME ':' expr {',' NAME ':' expr} ')']     // in decision or agent
             | 'ceiling:' NAME                                                  // in decision
             | 'proposes:' NAME                                                 // instead of choices:
expr        := or ; or := and {'or' and} ; and := not {'and' not} ; not := ['not'] cmp
cmp         := sum [('=='|'!='|'<'|'<='|'>'|'>='|'in') sum]
sum         := term {('+'|'-') term} ; term := unary {('*'|'/') unary}
unary       := ['-'] postfix ; postfix := primary {'.' NAME ['(' [arg {',' arg}] ')']}
primary     := NUMBER | PERCENT | DURATION | STRING | BOOL | '[' list ']' | NAME | '(' expr ')'
```

Multi-line `slip to … when a, b, c` in the sketches is sugar for `a or b or c`; a comma list after `when` is joined with `or` for slip and
`escalate`, and with `and` for `climb`. (Choice made for readability; the reverse would be a trap in `slip`.)

### 1.2 Words and compatibility

`ladder`, `rung`, `measure`, `climb`, `slip`, `ceiling`, `proposes`, `history` are **contextual**: the parser recognises them only where a
top-level block, a ladder body or a decision binding expects them, so a variable called `history` still works elsewhere. The repository-wide
load test of the earlier spec (V1.7) is extended to the new words.

### 1.3 Load-time checks (`ScriptValidator`)

Beyond R1.4:

- Name resolution per expression: a bare name is looked up as a ladder parameter, then a measure, then a case field (from the decision's `remember`
  list and the built-in case record fields of §3.1), then the standard library. A clash between a measure and a field is an error (R2.5).
- **Typing** (R3.8). Types: `bool`, `number`, `string`, `duration`, `ratio`, `case`, `cases` (a collection), `group(cases)`, `list`, `any` for
  fields whose type the script does not declare (`remember:` values are strings unless the decision's `fields:` types them; the checker coerces
  numeric comparison on a field to a warning, not an error, because inputs are untyped today).
- Recursion among measures: a depth-first walk of the measure reference graph; a cycle is an error naming the chain.
- `slip to` goes strictly down; `ceiling` names a rung of the bound ladder; a ladder with a parameter bound twice or not at all.
- Warnings: R4.4 (unpinned alias), R5.3 (climb on `confirm` history without a filter), R7.2 (`when the agent changes:` omitted).

## 2. Evaluation

### 2.1 Typed AST

The parser produces an `Expr` tree (`Lit`, `Name`, `Call(target, method, args)`, `Bin`, `Not`, `ListLit`). The checker annotates each node with a
type and resolves each `Name` to its binding. Evaluation interprets the annotated tree; there is no code generation and no reflection, so an
expression can only do what the interpreter has a node for.

### 2.2 Values and undefined

`Value` is a sealed type: `Bool`, `Num`, `Str`, `Dur`, `Ratio(value, n, lower95, upper95)`, `Cases`, `Groups`, `Undefined`. Every operator has an explicit
undefined rule (R3.7): arithmetic gives `Undefined`; `and`/`or` use three-valued logic reduced to `false` at the **top** of a `climb`, `slip`,
`escalate` (an expression's final value that is not `true` is `false`). This makes both directions fail safe: no climb on no data, no slip on no data.

### 2.3 Collections

`History` is a lazy view over the folded `Case` list (§3) filtered to the current decision, scope and epoch, and to cases that have a response and are
not superseded. Methods return new views; aggregates evaluate over them once. A single evaluation of one ladder for one scope touches the case list
at most a bounded number of times: the expression is analysed for the longest `last(n)` / `since(d)` it uses, and the view is materialised once to
that horizon (§6.3). Aggregates over `Undefined` values skip them and report `.n` of what was used.

### 2.4 Measures

A `measure` is stored as a typed `Expr` with one parameter bound to a `case`. A call `history.rate(agrees)` passes the case to the measure's tree.
Evaluation is memoised per case per evaluation, so ten uses of `edit_size` cost one distance computation. Measures are never persisted (R2.4).

### 2.5 Primitives in Java

Exactly two kinds: the **collection methods** of R3.3 and the **built-in functions** `distance(a, b)` and the Wilson interval. `distance` is
dispatched by the value types of its arguments:

| Types | Distance |
|---|---|
| choice, enum | 0 if equal, else 1 |
| string | normalised Levenshtein (edit distance ÷ the longer length), 0..1; very long texts use a token-level diff to keep it linear in practice |
| structured (JSON object) | the share of leaf fields that differ |
| any other pair | undefined |

## 3. The generalised ledger

### 3.1 Case record

`Case` today is `proposal: String`, `verdict: String`, `outcomes: List<String>`. The new shape keeps those accessors working for choice decisions and
adds:

| Field | Meaning |
|---|---|
| `proposalValue` | the proposal as a value: a choice string, or the artifact (text or JSON) |
| `response` | `kind` (`accept`/`edit`/`reject`), `choice` (choice decisions), `final` (artifact for an edit), `by`, `at` |
| `proposedAt`, `respondedAt` | for `review_time` and `latency` |
| `servedModel` | R4.4 |
| `outcomes` | list of `(name, value, at)`; `c.outcome` is the latest |

Artifacts are held in the run journal and referenced from the ledger when over a size limit, as evidence already is (loom-earned-autonomy R2.1);
the ledger holds a hash and a short head so `distance` can be recomputed from the journal. Retention (`keep records for`) strips the artifacts the
same way it strips fields today; measures that need them become *undefined* for stripped cases (so a retention shorter than a ladder's longest window
is a `weave check` warning).

### 3.2 Records

New record kinds are additive: `RESPONDED` (replacing the use of `DECIDED` for artifacts, with `kind`, `final` and `by`), `OUTCOME` gains `name` and
`value`, `EPOCH` (R7.1: both identities, both served models, the reason). `Cases.fold` is extended; old records fold into the new shape as R10.2
says. The `Ledger` interface does not change; the three implementations and the contract test do not gain methods.

### 3.3 Served model

The provider adapters already know the model id in the response for most providers. The runtime reads it where the provider reports it and writes
it on the case. The epoch rule compares the served id of this case with that of the last case in the epoch; a mismatch appends an `EPOCH` record
before the case. Providers that report nothing record `unreported` and never trigger this rule (so the rule does not give a false sense of safety;
hence the R4.4 warning).

## 4. Engine and rungs

### 4.1 Per case

1. Resolve the decision's ladder and bindings; resolve scope; read the level (a rung name) from the `LevelStore`.
2. Compute `escalate` for the rung (an expression over the case's inputs and `history`). If true, the case runs in `confirm` mode.
3. By the rung's mode:
   - `blind`: ask without the proposal (the existing blindness machinery: loom-earned-autonomy design §2.1);
   - `confirm`: ask with the proposal;
   - `autonomous`: apply the proposal, except `sample P%` (deterministic hash of the case id) goes blind.
4. Append `CASE`, `PROPOSED`, `RESPONDED` records. For an `accept`, the verdict is the proposal; an `edit`, the final; a `reject`, the person's
   choice or, for an artifact decision, the declared `on reject:` branch (R6.5 uses the existing `alt` on the bound value being empty).
5. After the response, evaluate the **slip** of the current rung and the **climb** of the next rung. A slip applies at once (recorded with the
   reason and the rules in force). A climb is **proposed** when it holds and the ceiling allows it; with `climbing is automatic` it applies, otherwise
   it waits for `weave autonomy approve` (existing machinery).

### 4.2 Evaluation triggers

Ladders are evaluated after every response and after every outcome (an outcome may cause a slip), and at load (R7.4). Each evaluation of each
scope is bounded by §2.3.

### 4.3 Ceiling

`ceiling` is applied at climb: a climb into a rung above the ceiling is not proposed. It is checked again at load, so lowering a ceiling demotes
(`ceiling lowered`), like any other ladder change.

### 4.4 Desugaring `trust`

The `trust` block compiles to a `LadderDef` with rungs `watch` (blind), `suggest` (confirm), `act` (autonomous) and these terms:

| `trust` phrase | Compiled |
|---|---|
| `to suggest: after N cases over D days, agreeing at least P%` | `climb when history.count >= N and history.span >= Dd and history.last(W).rate(agrees).lower95 >= P%` |
| `with no dangerous mistakes` | `and history.last(W).count(dangerous) == 0` |
| `drop to L when K dangerous mistakes in M cases` | `slip to L when history.last(M).count(dangerous) >= K` |
| `drop to L when agreement falls below P%` | `slip to L when history.last(W).rate(agrees).value < P%` |
| `check P% of cases …` | `sample P% blind` |
| `always ask a person when C` / `after N cases a day` | `escalate when C or history.since(1d).count >= N` |

`W` is the `judge on the latest W cases` window. A golden test compares the desugared ladder's levels with the old engine's over the existing
test ledgers; the old `Ladder.java` rule evaluator is removed once that passes.

## 5. Epochs and change

### 5.1 Identity and epochs

`AgentIdentity.of(...)` is unchanged (it already hashes model, prompt, persona, tools, schema, guards and the decision block). The ladder and the
measures are **deliberately excluded** from it (R7.4): they are policy over facts, not part of the agent. What a `when the agent changes:` line does
is unchanged: `start over` opens an empty epoch; `keep the trust` opens a new epoch but copies the level; `test it on past cases` replays (existing
`EpochReplay`). The served-model change (R7.1) is routed through the same code with the reason `served model`.

### 5.2 Re-evaluation at load

The runtime keeps, per decision, a `rules fingerprint` (hash of the ladder, its bound parameters, the measures it reads and the ceiling) in the
level store. At load, if it differs: evaluate each scope's current rung (slip conditions, plus "does the climb into the current rung still hold")
over the current history; demote to the highest rung whose conditions hold; for scopes that satisfy more than their rung, append a
`PROMOTION_PROPOSED` record. Then store the new fingerprint and the rule texts (R7.5). A first run has no fingerprint and does nothing.

"Climb into the current rung still holds" needs care: the first time a scope climbed, the cases were fewer. Re-evaluation therefore checks the
**climb condition as of now**, over the whole current history, which is what a stricter ladder should mean. A demotion by re-evaluation does not
itself trigger further slips in the same pass (one step per evaluation, to a rung computed once).

## 6. Operating

### 6.1 Commands

`weave autonomy status` is extended with the terms of the next `climb` (name, value, needed) and of the `slip` (R7.6). New: `weave ladder describe`,
`weave ladder test` (R11).

### 6.2 `describe`

The describer walks the typed AST and renders each node with a phrase template per node and per library measure
("`history.last(50).count(dangerous) >= 2`" → "at least 2 of the last 50 cases were dangerous mistakes"). A publisher's own measure is rendered by its
name and, if declared, a `describe: "…"` string on the measure. Unknown shapes are rendered literally; the describer never fails.

### 6.3 Cost

Case folding is already O(cases per decision). With a horizon analysis (§2.3), an evaluation reads at most the larger of the longest `last(n)` and
`since(d)` windows, or the whole epoch if a term uses `history` unbounded; the engine caches the folded list per decision in `LedgerCache` as it
does now. The expected size (low thousands of cases per decision and scope) makes this cheap; `ReplayScaleTest` style tests cover 100k.

## 7. Standard library (`loom/autonomy`)

```loom
measure accepted(c)  = c.response.kind == "accept"
measure edited(c)    = c.response.kind == "edit"
measure rejected(c)  = c.response.kind == "reject"
measure agrees(c)    = c.response.choice == c.proposal.choice
measure reversed(c)  = c.outcome == "reversed"
measure edit_size(c) = distance(c.proposal.value, c.response.final)      // 0 for an accept
measure untouched(c) = accepted(c) or edit_size(c) < 0.02
measure review_time(c) = c.responded_at - c.proposed_at
measure latency(c)     = review_time(c)
```

`dangerous` is *not* in the library as a fixed pair: a decision declares `dangerous mistake: propose approve, person decides reject` as today and the
runtime provides `dangerous(c)` bound to the declarations of the case's decision. (It is the one place a measure is generated.)

## 8. Failure paths

- An expression that fails at run time (it cannot, bar an interpreter bug) is treated as `false` for `climb`, `slip` and `escalate` and as a
  recorded `ladder error` on the case; the level does not move.
- A `distance` over a stripped artifact is `undefined` (§3.1), so a term using it is `false`; `status` shows the count of cases it could not use.
- A provider that never reports a served id never opens an epoch (§3.3); the warning stands.
- A ladder edit while cases are in flight: cases already open finish on the rung they started on; the next evaluation applies the new rules.
- Torn and duplicate records: as loom-earned-autonomy §3.

## 9. Security

- The expression language has no node that can reach the outside: no I/O, no clock (time is `case` fields and the injected evaluation time), no
  environment, no calls. A reviewer can read a ladder knowing it cannot do anything but compute (R9.2).
- Measures and ladder text are code in the script: the script review that already covers prompts and tools covers them. `weave audit` flags the
  changes in R9.5.
- The agent cannot reach the ledger (reserved paths); the response and outcome records are written by the runtime and commands only.
- A ladder cannot choose or fake a mode: modes are an enum read at load and enforced in `Engine` (R5.6).

## 10. Testing seams

An injected `Clock` (existing `MutableClock`); in-memory and fault ledgers (`FaultLedger`); a `LadderHarness` that appends fixture cases and
steps the engine; golden files for `describe`. Property tests: evaluation of any generated expression terminates and never throws; undefined
never makes a `climb` true; re-evaluation is idempotent.

## 11. Decisions and alternatives

| Decision | Alternative | Why |
|---|---|---|
| Method chains, no lambdas | Pipelines; query-style; Java-like lambdas | Chains read left to right and need no second operator syntax; no lambdas keeps the language total and typed so `weave check` catches typos. |
| Measures, not a scorer that runs tools | `signals` with `check`/`judge` over tools or models | The inputs the ledger already trusts are what people did; a scorer that runs code or a model brings back side effects and weakens the evidence. A publisher who needs more records an outcome. |
| Ladder separate from decision | Ladder inside agent only | Trust is earned per decision and scope; an agent can propose in several decisions. An optional agent default covers the common case. |
| Ladder edits re-evaluate; agent edits open an epoch | Both open an epoch | The facts are still true when a policy changes; they are not facts about the new agent when the agent changes. Tightening demotes, loosening only proposes. |
| Closed set of three modes | Open modes | Modes are what the runtime enforces about visibility and who decides; an open mode could fake blindness. |
| Stdlib in Loom | Built-in facts in Java | If a policy needs Java, a mechanism is missing; the library is the proof, and publishers can read and fork it. |
| `trust` desugars to a ladder | Keep two engines | One engine, one set of tests, and the desugaring is itself a continuous check that the language is expressive enough. |

## 12. Open questions

1. **Fourth mode: act after a delay unless vetoed.** Wanted for scripts and posts. Needs a timer, a veto channel and a "veto" response; deferred.
2. **Typing of `remember:` values.** Today they are untyped strings; numeric comparisons (`amount > 200`) work by coercion. Should `remember:` take
   types (`amount: number`)? Likely yes, with the coercion kept as a warning for old scripts.
3. **Retention versus windows.** A ladder reading `since(30d)` with `keep records for: 14 days` can never climb. Warn at check, or reject?
4. **`describe:` strings on measures.** Needed for useful plain-language output of publisher measures; the syntax is not settled.
5. **Multiple deciders** (judging people against each other) remains out of scope as in loom-earned-autonomy.

## 13. Amendments after implementation

- **Levels are events in the ledger** (§4.1, §5): the standing of a scope is its latest `level` record, a freeze is a `frozen` record, a promotion and its answer
  are records. There is no separate level store for ladders. A writer token on each level record tells two racing writers apart.
- **Reading cases** (§2.3): `LadderEngine` folds the ledger once per change to it and groups the cases by scope; an evaluation reads only the scope's cases.
  At 100,000 cases in 50 scopes a step costs about 180 ms (`LadderScaleTest`).
- **Where text is read** (§1): a `ladder` or `measure` declaration is set aside by the Loom lexer as one token of raw text and read by `LadderParser`, because its
  conditions use characters the Loom lexer rejects. Clauses may share a line; a comma list after `when` is `or` for `slip` and `escalate` and `and` for `climb`.
- **The standard library** (§7) is `src/main/resources/loom-lib/autonomy.loom`; `import "loom/autonomy"` loads it.
- **The `trust` desugaring** (§4.4) was not built; see requirements amendments.
- **Artifacts** (§3.1) are kept inline; large ones are not held by reference.
