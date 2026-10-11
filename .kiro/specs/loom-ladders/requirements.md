# Requirements Document

## Introduction

[loom-earned-autonomy](../loom-earned-autonomy/requirements.md) lets an agent earn freedom: it starts at `watch`, climbs to `suggest` and `act` as a
ledger of human decisions supports it, and slips back when the record turns. It works, but the rules are written as English phrases inside each
`decision` (`to act: after 300 cases over 30 days, agreeing at least 97%`), and the vocabulary is fixed: *agreement*, *dangerous mistakes*,
*reversals*, *unusable proposals*. That vocabulary fits an agent that picks from a short list of choices. It does not fit a script-authoring agent
(what matters is how much a reviewer edits, and whether CI later reverts it) or a blog publisher (edit size, review care, traffic a week later).

This spec makes the **ladder a primitive of its own**, and turns the judging of an agent into logic the publisher writes:

| Idea | What it means |
|---|---|
| **The ledger is the only input** | Whatever an agent does is recorded with what the humans did about it: accepted, edited (with the final version) or rejected, plus outcomes that arrive later. Every rung's logic is a function of that history. |
| **A ladder is a named, reusable policy** | `ladder Name { rung … }` declares rungs; each rung has a **mode** (what the runtime enforces), a **climb** condition and a **slip** condition. A decision or an agent *binds* a ladder; it does not contain one. |
| **Judgement is code, not vocabulary** | Climb and slip are expressions over `history`, using **method chains** (`history.last(50).where(careful).rate(agrees)`). Anything beyond the few built-in facts is a `measure` the publisher defines. |
| **Loom provides mechanisms, not policy** | The runtime fixes only the ledger, the expression language, the rung modes and the safety invariants. Accept rate, edit size and agreement are a standard library written in Loom. If a new policy needs a Java change, a mechanism is missing. |

The standing rules from earlier Loom specs still apply:

- **Nothing is silently ignored.** Anything the runtime can't honour is a load error that names the line.
- **Existing valid scripts keep working.** The `trust { … }` block of loom-earned-autonomy keeps its meaning (R10).
- **Logic lives in the framework**, with the approvals, budgets, guards, audit and journal that already exist.
- **The record is the authority.** Levels move because of the ledger and the ladder's rules, never because of anything the model says.

**Out of scope** (each a follow-up, not a gap in this one):

- Learning from the ledger: fine-tuning, prompt rewriting, model choice. The ledger *grades*; it does not *train*.
- A fourth rung mode, **act after a delay unless vetoed**. It is wanted by the script and blog cases and is the first candidate for a follow-up; the modes in R5 are the closed set for now.
- Scorers that run tools or models over a case (`check`, `judge`). The only inputs are what people did and what outcomes were recorded. A publisher who needs more records it as an outcome.
- Detecting outcomes automatically. Outcomes arrive through an explicit command or call, as in loom-earned-autonomy R5.5.
- A dashboard or hosted service, and any claim of regulatory compliance. The runtime produces evidence; it does not certify.

## Glossary

- **Ledger**: the append-only record of cases, proposals, responses, outcomes and level changes (as in loom-earned-autonomy).
- **Case**: one occurrence of a decision, with its captured inputs, its **scope** value, and its place in an **epoch**.
- **Proposal**: what the agent produced for the case: a choice, or an artifact (a script, a post).
- **Response**: what a person did with the proposal: `accept` (as is), `edit` (with the final version), or `reject`. For a choice decision the response also carries the choice the person made.
- **Outcome**: a later fact about a case (`reverted`, `taken down`, a number such as views after seven days), recorded after the fact.
- **Rung / mode**: a named step of a ladder, and what the runtime enforces there (`blind`, `confirm`, `autonomous`).
- **Climb / slip**: the condition to move up from the rung below into this one / the condition to move down out of it.
- **History**: the cases of this decision and scope in the current epoch, as the expression language sees them.
- **Measure**: a pure, named function from a case to a value, defined by the publisher.
- **Epoch**: a period in which the agent's identity (and the served model) did not change. Evidence from one epoch is never credited to another.

## Requirements

### Requirement 1: Declaring a ladder

**User Story:** As a workflow author, I want to declare a ladder once, by name, and reuse it, so that the rules for earning autonomy are written down, reviewed and shared.

#### Acceptance Criteria

1. THE grammar SHALL accept a top-level `ladder Name { … }` (and `ladder Name(param, …) { … }`) containing one or more `rung <name> { … }` blocks in ascending order, and the settings `climbing needs approval from <who>` (or `climbing is automatic`).
2. A rung SHALL declare a `mode:` (R5), and, except for the first rung, a `climb when <expression>`; and MAY declare `slip when <expression>` or `slip to <lower rung> when <expression>` (default: the rung below), `sample <P>% blind`, and `escalate when <expression>`.
3. THE first rung is where every new scope starts. It SHALL NOT have a `climb`; a `slip` on it SHALL be a load error.
4. WHEN a script is loaded, THE loader SHALL report, naming the line: a duplicate rung name; a ladder with no rung; a `climb` or `slip` that is not a boolean expression; an unknown rung named in `slip to`; a `slip to` that does not go down; an unknown mode; a ladder parameter that is used but not bound where the ladder is bound; a reference to a name that is not a measure, a case field, a ladder parameter or a standard-library name (R3.9).
5. A ladder MAY take parameters (`ladder BlogLadder(baseline_views)`); a binding SHALL supply every parameter by name (`ladder: BlogLadder(baseline_views: 1200)`), and a missing, extra or mistyped one is a load error.
6. A ladder SHALL be importable from another file, like an agent or a tool.

### Requirement 2: Measures

**User Story:** As a publisher, I want to define what "good" means for my agent in the script, so that the runtime does not decide that for me.

#### Acceptance Criteria

1. THE grammar SHALL accept `measure name(c) = <expression>`, a pure function of one case. A measure SHALL be usable by name wherever a case predicate or a case value is expected (R3.4).
2. A measure SHALL be **pure and total** (R3.1): it may read the case record (R4.1), other measures, the standard library and literals, and nothing else. It SHALL NOT read the environment, the clock, a tool, a model or anything the agent controls.
3. A measure's value is a boolean, a number, a string or *undefined* (a missing field, a division by zero, an outcome that has not arrived). *Undefined* propagates (R3.7); it never throws.
4. THE value of a measure for a case SHALL be computed by the runtime from the ledger on demand and SHALL NOT be stored as evidence; changing a measure therefore changes how the same cases are read, and R7.4 applies.
5. A measure whose name equals a case field name SHALL be a load error.

### Requirement 3: The expression language

**User Story:** As a workflow author, I want rung conditions written as ordinary expressions I can read, test and have checked, so that they are code and not prose.

#### Acceptance Criteria

1. THE language SHALL be **total and side-effect-free**: no loops, no recursion, no assignment, no calls out of the language. Every expression terminates. A recursive measure is a load error.
2. `history` SHALL denote the cases of the current decision, scope and epoch that count (R4.6), in the order they were decided. `history.all` and `history.epoch(-1)` (the one before) SHALL denote the wider sets, for rungs that want to look back.
3. A collection SHALL support, by **method chain**: `where(p)`, `last(n)`, `since(d)`, `between(a, b)`, `by(field)` (grouping), `count()`, `count(p)`, `rate(p)`, `mean(m)`, `median(m)`, `quantile(m, q)`, `min(m)`, `max(m)`, `sum(m)`, `any(p)`, `all(p)`. `n` and `d` are literals or ladder parameters.
4. An argument SHALL be either a measure name or an expression in which a bare name resolves to a field of the case (`history.where(amount > 200)`), measures taking precedence (R2.5 makes a clash impossible). There SHALL be no user-written lambda.
5. `rate(p)` SHALL return a ratio with `.value`, `.n`, `.lower95` and `.upper95` (the Wilson interval). A comparison written on the ratio itself (`history.rate(agrees) < 0.92`) SHALL use `.value`; the confidence bound is only ever used when written (`.lower95`).
6. `history.count`, `history.span` (the time between the first and last case in the set) and `n` SHALL be available directly. Literals: numbers, strings, booleans, durations (`30s`, `2h`, `14d`), percentages (`90%` is `0.9`), and lists with `in`.
7. **Undefined** SHALL propagate through every operator and aggregate that cannot use it (an aggregate skips undefined values and reports `.n`), and any comparison involving it SHALL be false. Consequences, which SHALL be tested: a `climb` over an empty history is false (fail closed); a `slip` over an empty history is false (no demotion on no data).
8. THE type of every expression SHALL be checked at load against the case schema of the decision it is bound to, so that a misspelt field, aggregate or measure fails `weave check` with the line and a suggestion (R11.1). An expression that cannot be typed because the ladder is bound to decisions with different schemas is checked at each binding.
9. THE standard library SHALL be written in Loom and shipped as `loom/autonomy` (R8); the language itself SHALL know only the collection methods above, the case record and the arithmetic and comparison operators.

### Requirement 4: The ledger records what people did

**User Story:** As a risk owner, I want every case to record the proposal, exactly what the person did with it and what happened afterwards, for any kind of agent, so that any policy can be judged from the record.

#### Acceptance Criteria

1. THE case record SHALL carry: id, decision, scope, epoch, identity, **served model id** (R4.4), inputs (`remember:` values), the **proposal** (a choice with reasoning and optional confidence, or an artifact), the **response** (kind `accept`, `edit` or `reject`; the person's choice for a choice decision; the **final** artifact for an edit), who responded, when the proposal was made and when the response came (`review_time` is derived), whether the proposal was shown (R5), and the **outcomes** (R4.3).
2. A decision SHALL declare what it proposes: `choices: a, b, c` (as today) or `proposes: <artifact name>`. THE loader SHALL require an `output_schema` of the agent that fits the declaration, as in loom-earned-autonomy R1.4.
3. AN outcome SHALL be a named fact with an optional value (`reverted`; `views_7d = 5400`; `status = "taken down"`). Outcomes SHALL be recorded by `weave autonomy outcome … --result <name> [--value …]` and by a workflow statement, SHALL be append-only, and SHALL be readable in expressions as `c.outcome` (the latest) and `c.outcomes`. An outcome that has not arrived is *undefined*, never false.
4. THE runtime SHALL record on every case the model id the provider says served the call, where the provider reports it, in addition to the model string in the script. A provider that reports nothing SHALL be recorded as `unreported`, and `weave check` SHALL warn when a ladder with an `autonomous` rung is bound to an agent whose model is an unpinned alias on such a provider.
5. THE ledger SHALL keep to loom-earned-autonomy R2 (append-only, idempotent, memory/file/JDBC with one contract test, guard masking, retention), extended with the fields above. The existing records SHALL keep reading (R10.2).
6. A case counts in `history` when it is not superseded, belongs to the current epoch, and has a response; whether it counts as **evidence** for a climb is a property of its mode (R5.3).
7. THE ledger and the level store SHALL stay unreachable to the agent, its tools and the case's values (loom-earned-autonomy R2.7). Measures are evaluated by the runtime, outside the agent.

### Requirement 5: Rung modes

**User Story:** As a risk owner, I want what the runtime enforces at each rung to come from a small set I can trust, so that no ladder can pretend to be blind or pretend someone approved.

#### Acceptance Criteria

1. THERE SHALL be exactly three modes. **`blind`**: a person responds without seeing the proposal, its reasoning or its confidence; the response is the verdict. **`confirm`**: the person sees the proposal and accepts, edits or rejects it; the response is the verdict. **`autonomous`**: the proposal is the verdict, except for cases sent to a person by `sample` (R5.4) or `escalate` (R5.5).
2. THE modes SHALL be enforced by the runtime in the way loom-earned-autonomy R3.1 and design §2.1 describe: in `blind`, nothing the runtime prints, traces or audits shows the proposal before the person has answered, and the entries are held and released afterwards.
3. WHICH cases count as **evidence** SHALL be set by mode: all responded cases of a `blind` rung, the blind sample of an `autonomous` rung, and for a `confirm` rung every responded case but **flagged as shown**. An expression SHALL be able to select on this (`shown`, `blind`), and `weave check` SHALL warn when a `climb` reads a `confirm` rung's history without selecting on a measure or `blind` (the anchoring problem: a person who sees the answer tends to agree).
4. `sample P% blind` SHALL send that share of an `autonomous` rung's cases to a person without the proposal, chosen by a deterministic function of the case id so a resume makes the same choice.
5. `escalate when <expression>` SHALL send a case to a person regardless of the rung (as a `confirm` case), using the case's inputs and `history` (for limits such as `history.since(1d).count >= 50`).
6. A rung's mode SHALL NOT be changeable by an expression, a measure or the agent; it is read from the script at load.

### Requirement 6: Binding a ladder

**User Story:** As a workflow author, I want a decision (or an agent) to use a ladder and say how high it may go, so that the same ladder serves many decisions.

#### Acceptance Criteria

1. A decision SHALL bind a ladder with `ladder: Name` or `ladder: Name(param: value, …)`, and SHALL declare `ceiling: <rung>` (default: the first rung), the highest rung the decision may reach however good the record is. A ladder bound to a decision with no ceiling therefore climbs nowhere until someone writes one.
2. A ladder MAY be bound to an **agent** as a default (`ladder: Name` inside `agent`), used by any decision that names that agent and does not bind its own; a decision's binding wins.
3. THE ledger and the levels SHALL remain keyed by decision, scope and identity: a ladder is a policy, trust is earned per decision. Two decisions bound to the same ladder do not share evidence.
4. WHEN a decision binds a ladder, the decision's own `trust { … }` block SHALL be a load error (one source of rules), naming both lines.
5. A decision that proposes an artifact SHALL be usable by `decide Name -> result` exactly as a choice decision is, binding `result` (what takes effect), `result_proposal` and `result_level`, with `result` an artifact value.

### Requirement 7: Change, epochs and re-evaluation

**User Story:** As a risk owner, I want any change that could invalidate the evidence to be visible and to have a defined effect, so that trust is never carried over by accident.

#### Acceptance Criteria

1. A change of **agent identity** (model string, prompt, persona, tools, schema, guard, the decision block; loom-earned-autonomy R6.1) SHALL start a new epoch. A change of the **served model id** (R4.4) with the script unchanged SHALL start a new epoch too, and SHALL be recorded as such with both ids.
2. EVERY ladder-bound decision SHALL declare `when the agent changes:` with `start over`, `keep the trust` or `test it on past cases`. WHEN it is omitted the decision SHALL load with the effect of `start over` and `weave check` SHALL warn, naming the decision; a ladder with an `autonomous` rung and `keep the trust` SHALL be a load error unless the ceiling is below that rung.
3. A new epoch SHALL NOT delete anything: the earlier epochs' records stay in the ledger and are reachable through `history.all` and `history.epoch(-n)`. Replay (loom-earned-autonomy R7) is unchanged.
4. A change to a **ladder** (rungs, climb, slip, parameters, modes) or to a **measure** SHALL NOT start an epoch. Instead THE runtime SHALL, at the next load, re-evaluate the ladder over the current history under the new rules: a scope whose level no longer satisfies its rung's conditions is **demoted at once**, with the reason `ladder changed`; a scope that now satisfies more is **proposed** for promotion and waits for the approval the ladder names (never promoted by the change itself).
5. THE ladder's text and the text of every measure it reads SHALL be recorded with each level change, so `weave autonomy history` shows which rules were in force.
6. `weave autonomy` SHALL keep `freeze`, `demote`, `promote --force`, `approve` and `outcome`, and `status` SHALL show for each scope the rung, the value of each term of the next `climb` (what is met, what is missing) and of the `slip`.

### Requirement 8: The standard library

**User Story:** As a publisher, I want the common measures available without writing them, and visible so I can change them, so that nothing is hidden in Java.

#### Acceptance Criteria

1. `loom/autonomy` SHALL be a Loom source file shipped in the runtime's resources and imported with `import "loom/autonomy"`. It SHALL define at least: `accepted`, `edited`, `rejected`, `agrees` (the proposal's choice equals the person's), `dangerous` (a pair given by the decision), `reversed`, `untouched`, `edit_size` (using `distance`), and the case-timing measures (`review_time`, `latency`).
2. THE only things implemented in Java SHALL be the primitives the library cannot write: `distance(a, b)` by value type (choice: 0 or 1; text: normalised edit distance; structured: the share of fields changed), and the Wilson interval behind `rate`.
3. A publisher SHALL be able to import the library under another name, copy and change it, or not import it; the engine SHALL work with none of it.
4. THE `distance` primitive SHALL be computed by the runtime from the proposal and the final artifact and SHALL NOT be settable by the agent or by a tool.

### Requirement 9: Safety invariants

**User Story:** As a risk owner, I want the flexibility of this language to stop short of the guarantees that make the ledger worth trusting.

#### Acceptance Criteria

1. THE ledger and the levels SHALL be written only by the runtime and the commands; nothing an agent, a tool, a measure or a case's values say can change them.
2. A measure or ladder expression SHALL have no way to cause an effect, read a secret, or reach the network or file system (R2.2, R3.1).
3. EVERY level change SHALL be recorded with its reason, the rules in force (R7.5), and who approved it.
4. A promotion into a rung SHALL need the approval the ladder names unless the ladder says `climbing is automatic`; a ladder change SHALL NOT promote (R7.4).
5. `weave audit` SHALL report, for each ladder: every `autonomous` rung and its ceiling; whether it climbs without approval; whether it has no `slip`; whether a `sample` is below a stated floor (default 1%); a `climb` that reads `confirm` history without a filter (R5.3); a ladder bound with `keep the trust` (R7.2).
6. THE ledger SHALL apply the agent's `guard` masking before writing, as before.

### Requirement 10: Compatibility

**User Story:** As an existing user, I want my current `decision … trust { … }` scripts to keep working.

#### Acceptance Criteria

1. THE `trust { … }` block of loom-earned-autonomy SHALL keep loading and behaving as it does, with the same results in its tests.
2. EXISTING ledger and level files SHALL keep reading. A case written before this spec reads as a choice decision with a response of `accept` or `reject` (and the person's choice), no served model (`unreported`), and the epoch it had.
3. THE runtime SHALL implement the `trust` block **by desugaring it into a ladder** (three rungs `watch`/`suggest`/`act`, with the climb, slip, sample and limits expressed in the new language over the standard library), so there is one engine. A test SHALL show the desugared form and the original give the same level for the same ledger.
4. `ladder`, `rung`, `measure`, `climb`, `slip`, `history`, `ceiling` and `proposes` SHALL be contextual words, recognised only where the grammar expects them; every script in the repository SHALL keep loading unchanged (test).

### Requirement 11: Tooling

**User Story:** As a workflow author, I want the ladder checked before it runs and explained in plain words afterwards.

#### Acceptance Criteria

1. `weave check` SHALL report every load error of R1–R6 with the line, a suggestion for a misspelt name, and the warnings of R4.4, R5.3 and R7.2.
2. `weave ladder describe <file> [Name]` SHALL render a ladder (and the measures it uses) back into plain sentences for a risk owner ("To reach `act` the agent needs at least 300 cases over 30 days, and …"), generated from the parsed ladder, not from its text.
3. `weave ladder test <file> Name --history <ledger-or-fixture>` SHALL evaluate a ladder over a recorded history and print, for each scope, the rung it would hold and the value of each term, with nothing written.
4. THE language server and the VS Code extension SHALL highlight the new words and complete rung, measure and collection names.

### Requirement 12: The acceptance test

**User Story:** As a Loom maintainer, I want proof that the mechanisms are enough, so that "flexible" is checked and not assumed.

#### Acceptance Criteria

1. THREE ladders — a refund triager (choices), a script author (artifacts, outcomes from CI) and a blog publisher (artifacts, parameters, a care filter, late numeric outcomes) — SHALL be written in Loom as test resources, each with measures and a standard-library import and nothing else, and each SHALL be driven through climb, slip, a ceiling, an epoch change and a ladder edit by tests.
2. THE tests SHALL run with no per-ladder Java: the same engine, the same classes. A test that needs a change in Java to express one of the three SHALL be recorded as a missing mechanism and fixed in the mechanism, not in that ladder.
3. THE refund ladder SHALL give the same levels as the equivalent `trust` block over a fixed ledger (R10.3).
