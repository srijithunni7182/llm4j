# Implementation notes: where the build differs from the spec

The spec ([requirements](../requirements.md), [design](../design.md)) was written before the code. Building it changed some decisions and left some
things undone. This file lists every difference, so the spec can be amended or the work finished; nothing here is hidden in the tests.

## Decisions that changed while building

| # | Spec | What was built | Why |
|---|---|---|---|
| N1 | Levels in a separate level store (design §4) | **Levels are events in the ledger.** The standing of a scope is its latest `level` record; a freeze is a `frozen` record; a promotion proposal and its answer are records. | The ledger is already append-only, idempotent and implemented for memory, files and a database. A second store would have been a second thing to keep consistent. A writer token on each record tells two racing writers apart (S8 in the sabotage run). |
| N2 | `distance` of choices is 0 or 1 (R8.2, design §2.5) | Text is edit distance for any two strings, including choices. Choices are compared with `==` (the `agrees` measure). | Strings carry no "this is a choice" marker, and a measure that wants 0/1 can say `==`. |
| N3 | `c.outcome` is the latest outcome, with `.views_7d`, `.status` (the sketches were inconsistent) | `c.outcome` is the **name** of the latest outcome; `c.outcomes.<name>` is its value (`true` for an outcome with none). An outcome that has not arrived is unknown. | One shape that serves `reversed`, `views_7d = 5400` and `status = "taken down"`. |
| N4 | A percentage literal is a number (R3.6) | It is a number that **keeps its text** (`90%` shows as `90%`, not `0.9`). | `describe` and messages read better, and the fingerprint is the same for the same rules. |
| N5 | Remembered values are untyped; typing left open (design §12.2) | `remember: amount: number, tier: text, vip: flag`. A declared type is checked at load and read that way at run time (`"01234"` stays text). An undeclared one is read as a number if it looks like one. | The verification plan's "a number where a yes/no is wanted" slipped through untyped fields (found by `TypicalMistakesTest`). |
| N6 | `final` only for an edit | For an artifact decision `response.final` is **always** set: the proposal for an accept, the corrected version for an edit, empty for a reject. | So `edit_size` is 0 for an accept without a special case in the language (there is no `if`). |
| N7 | Cases written by the earlier engine need a migration (R10.2) | They are read as they are: a `decided` record is folded as an accept or a reject. | A decision can move from `trust` to a ladder and its cases still count. |
| N8 | `sample` on any rung | `sample` and `escalate` act at `autonomous` rungs. On other rungs a person is asked anyway; `weave check` warns that they do nothing. | Closed set of modes. |

## Not done

| # | Spec | State |
|---|---|---|
| U1 | The `trust` block is desugared into a ladder, one engine (R10.3, design §4.4) | **Not done.** The two forms are separate engines. A decision uses one or the other (a script with both on one decision is an error). The earlier engine and its tests are untouched, which is why G1 holds, but the claim that one engine serves both is not made. |
| U2 | `when the agent changes: test it on past cases` for a ladder (R7.2) | Not applied: a changed agent starts over, and `weave check` says so. |
| U3 | A ladder default on an `agent` (R6.2) | Not built. Only a decision binds a ladder. |
| U4 | `history.all`, `history.epoch(-1)` in expressions (R3.2) | Not exposed in the language. The engine can read every epoch; a ladder sees the current one. |
| U5 | Artifacts over a size limit held by reference in the journal (design §3.1) | Not built. An artifact is stored inline in the ledger; a measure over a pair of documents that would take too long to compare is *unknown* (`distance` is bounded). Retention (`keep records for`) removes the text. |
| U6 | The fourth rung mode, act after a delay unless vetoed | Out of scope in the spec; still not built. |
| U7 | `weave ladder test` over a fixture file (R11.3) | It reads the recorded history in a store (`--store`). |
| U8 | The editor extension (R11.4) | Grammar words and hover text are added. No snippet, and the extension's own tests were not run (no Node tooling in the build environment). |
| U9 | A hand-edited ledger that forges a promotion is detected (verification C8) | **Not detected.** The ledger is not signed or chained. Recorded as a finding: out of this work's threat model, which protects against the agent, tools, measures and case values, not against someone with write access to the store. |
| U10 | Answering from the phone channel for an artifact decision (verification I3) | Not tested. The question text is plain, but no channel test was written. |

## Found by the verification plan, fixed

- **Typed remembered values** (N5): a number given where a yes/no was wanted was accepted because `remember:` values had no type.
- **First sighting of a served model**: the model a provider names was not recorded at the first case, so a later change behind the same script was invisible (`LadderRunTest.aProviderThatSilentlyServesAnotherModelStartsANewEpoch`). The first reply now records it.
- **Two writers with identical content** both believed they had made a change: content equality is not identity, so each write carries a token (S8).
- **Clauses on one line** (`climb when a slip when b`): the first condition swallowed the rest.
- **Cost of a long record**: listing records of one kind sorted by position with a linear search (about 40 ms per call at 100,000 cases). It now keeps an index. See `LadderScaleTest`.
