# Test Strategy

How the ladders work is tested: what kinds of tests, where they live, what stands in for the outside world, and what is deliberately not tested
this way. It supports [design.md](design.md). What counts as *done* is a separate document, [verification.md](verification.md), written to be read
without this spec.

## 1. Principles

1. **Test the promise, not the implementation.** The risk in this work is not a parser bug, it is an agent getting freedom it did not earn, or
   the language being too narrow for the next agent. Tests are weighted to those two risks.
2. **Fail-safe is tested as hard as success.** For every rule that lets something happen (a climb, an autonomous action) there are tests that the
   same rule, given missing, malformed or hostile data, does *not* let it happen.
3. **No per-policy Java.** The three reference ladders run on one engine. A test that needs engine code for one particular ladder is a defect in
   the engine, not a reason to write the test differently.
4. **Deterministic by construction.** No wall clock, no network, no unseeded randomness, no real model. Everything that varies is injected.
5. **Existing behaviour is a regression suite.** The existing earned-autonomy tests are not rewritten to pass; they are run unchanged against the
   new engine (the `trust` block desugars into a ladder), and any change to them needs a stated reason.
6. **One failure, one cause.** A test asserts one behaviour; the harness names the case, rung and term that failed.

## 2. Layers

| Layer | What it covers | Size | Lives in |
|---|---|---|---|
| **L1 Unit: language** | Lexer, parser, type checker, interpreter, collection methods, `distance`, Wilson interval, durations/percentages, three-valued logic | Many, milliseconds | `autonomy/expr/*Test` |
| **L2 Unit: loader** | Ladder/measure/binding checks and their error messages, name resolution, contextual words, imports | Many | `parser/`, `validation/` |
| **L3 Component: ledger** | Generalised case record, fold of old and new records, contract tests on memory, file and JDBC (H2), retention, torn writes | Medium | `autonomy/StoreContractTest` extended |
| **L4 Component: engine** | Rung modes, escalate, sample, slip/climb ordering, ceiling, approval, epochs, re-evaluation at load, fingerprint | Medium | `autonomy/Ladder*Test`, `LadderHarness` |
| **L5 Property / fuzz** | Random expressions and random case sequences against invariants (§5) | Few, long | `autonomy/Ladder*PropertyTest` |
| **L6 Scenario: reference ladders** | Refund, script author and blog publisher as `.loom` resources, driven through a life cycle | Few, large | `autonomy/ReferenceLaddersTest` |
| **L7 Compatibility** | `trust` desugaring golden test, old ledger files, every repository script loads | Few | `autonomy/Compat*Test` |
| **L8 CLI and tooling** | `weave check/audit/describe/ladder test/autonomy …` output and exit codes; golden files; LSP completion | Medium | `cli/`, `vscode-loom/test` |
| **L9 Safety and sabotage** | The agent, a tool, a measure or a case value tries to reach the ledger or a level; deliberate breakage of each safeguard must make a test fail | Few | `security/`, `scripts/sabotage_*.py` |
| **L10 Live (optional)** | One real provider reporting a served model id; a real channel answering | Tagged `live`, off by default | `live/` |

The pyramid is deliberately fat in L1 and L4: expressions and the engine are where the logic is, and both can be tested without a model or a person.

## 3. Stand-ins

Reuse what the earlier spec built; add only what is new.

| Stand-in for | What | New? |
|---|---|---|
| Model | scripted `LLMClient` returning a given proposal per case id (a choice, or an artifact), malformed output, an error, and a **served model id** per call | served id is new |
| Decider | scripted `HumanInterface` answering per case id with `accept` / `edit(final)` / `reject` / a choice; records every question *and what it showed*; can pause | edit and reject are new |
| Time | `MutableClock`; ladders with days-long terms are tested by moving it | no |
| Storage | `MemoryLedger`, `FileLedger` on `@TempDir`, `JdbcLedger` on H2, `FaultLedger` | no |
| Outcomes | a helper that appends outcomes with names and values at a given time | new |
| History builder | `Histories.of(...)`: build N cases with a given distribution of responses, edit sizes, review times and outcomes, in one call, so a ladder test is a table and not forty lines of setup | new |
| Randomness | fixed seed, printed on failure (`-Dloom.ladder.seed=…`) so a failure replays | extended |

## 4. What each layer must show

### L1 Language
- Every operator and collection method: normal values, empty collection, all-undefined, mixed, one element.
- **Undefined** through every operator (the table is a parameterised test: operator × operand kinds → expected result).
- `rate(...).lower95` against known Wilson values (20/20, 0/20, 1/1, large n); a bare comparison on a ratio uses `.value`.
- Windows: `last(n)` with n larger than the set; `since(d)` at the boundary instant (inclusive/exclusive is documented and tested); `between`.
- `where` + `by` + aggregates compose; a measure used inside `where`, inside `mean`, and inside another measure.
- `distance` by type, including unicode, empty strings, very long strings (time bound), nested structures, mismatched types (undefined).
- Rejection: a recursive measure, an unknown method, a method on the wrong type, a measure that shadows a field.

### L2 Loader
- A table of malformed ladders, each with the exact message and line (golden), including a misspelt name with a suggestion.
- Parameters: missing, extra, wrong type. Ladder imported from another file. Two bindings of one ladder with different parameters.
- Contextual words: `history`, `rung`, `slip`, `climb`, `ladder`, `measure`, `ceiling`, `proposes` still usable as ordinary names.

### L3 Ledger
- Old-format files read into the new case shape (fixtures captured from the current release and committed).
- Idempotent append of the new record kinds; ordering; orphaned records; torn last line; the same suite on three stores.
- Artifacts: over-limit artifact stored by reference with a hash; retention strips it and measures that need it become undefined.

### L4 Engine
- Each mode: what the decider is shown is asserted from the stand-in's record, not from the engine's own flags.
- Blindness: nothing printed, traced or audited contains the proposal before the response (the earlier spec's technique: capture all sinks).
- Climb needs approval vs automatic; climb blocked by ceiling; slip immediate; slip to a named rung; slip never climbs.
- Escalate and sample: deterministic from the case id; a resume makes the same choice.
- Epochs: model string change, prompt change, served-model change with identical script; `start over`, `keep the trust`, `test it on past cases`.
- Ladder edit at load: tighten → demote; loosen → proposal only; measure edit; ceiling lowered; parameters changed; unchanged → nothing happens.
- A case in flight when the ladder changes.

### L5 Properties (§5)
Seeded generators for expressions (well-typed by construction) and for case sequences with interleaved outcomes, edits, freezes and ladder swaps.

### L6 Reference ladders
The three ladders from the design discussion, as `.loom` resources:

| Ladder | What makes it a distinct test |
|---|---|
| Refund triager | Choices; dangerous mistakes; sample and escalate; compared with the equivalent `trust` block |
| Script author | Artifacts; edit size; outcomes from CI arriving late and sometimes never; slip on a late outcome |
| Blog publisher | Parameterised ladder; a "careful review" filter against rubber-stamping; numeric late outcomes (a week later); a topic-based escalate |

Each is driven through: start → climb → slip → climb again → ceiling hit → epoch change (model) → epoch change (served model only) → ladder
tightened → ladder loosened → frozen → recovered. The test asserts the rung, the stated reason and the term values at each step.
**Rule:** these files may import the standard library and define measures, and nothing else; the test fails if a ladder resource needs any Java
class that is named after it.

### L7 Compatibility
- Golden: the desugared form of each existing `trust` test script gives the same level sequence as the old engine over the committed ledgers.
- Every `.loom` in the repository loads with each new word used as a variable name.

### L8 CLI
- Golden output for `describe` of each reference ladder (reviewed by a person once, then pinned).
- `check` exit codes and messages; `audit` findings; `autonomy status` term table; `ladder test` over a fixture history.

### L9 Safety and sabotage
- An agent prompt and a tool result that name the ledger path, a level, or a command; a case value shaped like a ledger record; none changes a level.
- A measure or ladder expression attempting each effect (there should be no syntax for it; the test is that the parser rejects every attempt).
- **Sabotage** (extending `scripts/sabotage_autonomy.py`): disable each safeguard in turn (fail-closed on empty history, blind hiding, approval gate,
  ceiling, epoch on model change, served-id check, immediate slip, idempotent append) and require at least one test to fail for each. A
  safeguard with no failing test is untested.

## 5. Property invariants

After every step of every generated sequence:

1. Evaluation of any well-typed expression terminates within a step budget and does not throw.
2. A `climb` is never true over an empty history, nor over a history of only undefined values.
3. No promotion happens without the approval the ladder names, except under `climbing is automatic`.
4. The level never exceeds the ceiling, except by an explicit forced promotion that is recorded as one.
5. A scope's level changes only by a recorded event with a reason; replaying the ledger from the start reproduces the current levels exactly.
6. Evidence from one epoch is never counted in another's `history`.
7. A `blind` question never contained the proposal; an `autonomous` case with no sample hit and no escalate asked nobody.
8. Re-evaluating at load twice in a row changes nothing the second time.
9. Appending the same record twice, in any order relative to others, leaves the same folded state.
10. A ladder change never raises a level by itself.

Seeds and sequence counts are as in the earlier property test (`20 000` by default, a long run with an option).

## 6. Test data

- **Fixtures** are generated by `Histories`, not hand-written JSON, so the intent ("120 cases, 92% accepted, median edit 3%") is visible in the test.
- Old-format ledgers are the only committed binary-ish fixtures, captured once from the current release.
- Golden files (describe output, error messages) live next to the test and are reviewed in the diff when they change.

## 7. Coverage and quality gates

- Line and branch coverage of `autonomy/expr` and the engine: ≥ 95% / ≥ 90% (the existing gate is reused; the report is in the evidence).
- Mutation testing on `autonomy/expr` and the climb/slip/ceiling code, if the tool can be added cheaply; otherwise the sabotage script above is the
  mutation check for the safeguards, and a hand-run on the interpreter is recorded in the evidence.
- No test is skipped, `@Disabled`, or tagged out of the default run, except `live`.
- The default test run stays under the existing time budget; the long property run is a separate target.

## 8. What is not tested this way

- **Whether a ladder's thresholds are wise.** That is the publisher's judgement; the tests show a threshold is applied as written.
- **A real model's behaviour.** Models are scripted. The only live test is that a provider's served-model id is read.
- **Human factors** (do reviewers rubber-stamp?). The design gives a filter; whether it is enough is observed in use, not in unit tests.

## 9. Order of work

Tests are written with each task of [tasks.md](tasks.md), not after. Specifically: L1 before the interpreter is trusted by anything; the
`trust` golden test (L7) *before* the old evaluator is deleted; the three reference ladders (L6) as a failing test suite **first**, since they
are the definition of "flexible enough"; sabotage (L9) last, once the safeguards exist.

## 10. Retrospective: how much checking of the checks is worth it

Written after the work was built, because the cost was out of proportion to what it found.

**What was done.** §7 allowed hand-written sabotage as the mutation check for the safeguards. It grew to 32 breaks of the real source (`scripts/sabotage_ladders.py`), each needing a rebuild, run one after another because they edit the shared tree. PIT was run once (90% of 1,785 mutants killed).

**What it found.** One real gap in 32 (S15, a superseded generation counted). Everything else confirmed tests already believed in. The defects that mattered were found by other means, at a fraction of the time: a comparison with the *previous release* (about six behaviour differences: level words, audit sampling, status wording, the outcome message, approve's exit path, editing a trust block demoting scopes), porting the earlier tests (a freeze arriving mid-case changed the rung; the status lost its freeze reason), running the tests the default build excludes (`fragile`), and reading each plan row literally (C8). A guard that lists earlier tests changed on the branch would have caught the worst mistake (an earlier test class overwritten and reported as unchanged), which no sabotage could.

**What to do instead, from here.**
1. A table per safeguard, written *with* the requirement: scenario, the failing input, the assertion that would fail. The negative case is written before the code, not discovered by breaking the code. (L9 already asked for this; it was not a table.)
2. When a new test passes on its first run, break the one line it guards by hand and run that test. Thirty seconds, no framework.
3. Compare with the previous release wherever behaviour must be preserved: build it from the base commit, generate fixtures, assert equality. Highest yield per minute here.
4. Mutation testing with the real tool (PIT), incrementally on changed classes, on demand or nightly, never as a step of a working session. Hand-written sabotage is kept only for about eight safety-critical rules: failing open, showing the proposal at a blind rung, the approval gate, the ceiling, the seal.
5. A check that lists tests that exist on the base branch and differ or are gone on this one, with the reason for each, run before any result says "unchanged" (`evidence/G1-altered-tests.md` was produced this way).
6. Include the excluded groups (`fragile`, `integration`) in the run that closes a piece of work, or list which ones touch it.
