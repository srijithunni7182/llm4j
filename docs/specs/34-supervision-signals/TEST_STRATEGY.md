# Phase 34 test strategy: closing the gaps, progress, signals and `wait any`

Status: **draft for review.** Companion to [DESIGN.md](DESIGN.md); the decisions behind it are D15 and parts of D2, D12 and D14 in [DECISIONS.md](DECISIONS.md), all still open. Nothing is built, so every test below is to be written, and **the guard tests are written before the code they guard**.
It continues [phase 33's strategy](../33-async-workflows/TEST_STRATEGY.md): the same rules (hermetic, behaviour not structure, a guard is tested by breaking it, the tiers stay honest) and the guard numbering carries on from G15.

## 1. What phase 34 adds to the test tools

- **`signals:`** in a `weave test` case scripts a signal's arrival on the virtual clock (`signals: { work_a: [ { at: 2m, name: stuck, text: "no sources" } ] }`); `expect:` gains the signals a run sent and received.
- **The stubbed child (F5):** `children: { Research: [ { state: working, progress: {...}, after: 1m }, { signal: stuck, text: "..." }, { state: completed, result: "..." } ] }`, run by the controlled scheduler without the child's script (the style of `remotes:`).
- **A controlled wake:** `ControlledChildScheduler` is extended so a woken child is queued and re-run deterministically, one at a time, when the test advances.
- **The injected clock** reaches the `wait` timeout (F3), so a wait that times out is a virtual-time test.
- **A kill-point harness** that already kills before every journal write of the tree is extended to the new writes (`#sig.*`, `#progress.*`, the `wait any` entry).

## 2. Layers

| Layer | What | Where |
|---|---|---|
| **Parse and load** | `progress { }`, `report { }`, `notify`, `wait signal`, `wait any`, `when signal`, `assert progress ... changes within`; each load error (an undeclared or mistyped progress field, a `report` with no declaration, `notify` to a handle this run did not start, a `wait any` with no timeout, more handles than `max_children`); **the words that stay ordinary** (`-> report`, `set progress = ...`, a variable named `signal` or `parent`); `RepositoryScriptsTest` loads every script. | core tier |
| **Check** | A declared progress never reported; `notify parent` in the entry workflow; a `start` and `supervise` pairing and the reservation it implies; the warnings of F9. | core tier |
| **Engine, scripted** | Progress end to end (a child reports, the parent reads the latest; a rewind shows the value at the checkpoint; the bounds drop and record); signals both ways with a woken child; `wait any` with every event kind; supervise `when signal`; the closure items (F1 to F9). | core tier |
| **Replay and kill-point** | G16, G17, G18. | `slow` tag |
| **Concurrency** | G19: a `notify` racing a child that is about to suspend again; two `notify`s while a woken child runs; the single-flight rule. A real-thread variant tagged `slow` only if the controlled scheduler cannot reach the interleaving. | core tier and `slow` |
| **Persistence** | The same signal and progress tests over `Persistence.file`, `jdbc` and the CLI sibling layout; `fork` copies the mailbox entries with the journal. | Loom module |
| **Security** | LA30; canary cases for a signal's payload in both directions (the oracle); a signal cannot become a steer's text; a child cannot signal anyone but its parent; a parent only handles it started. | core tier and the oracle |
| **Ecosystem** | Graph snapshots and the renderer sync; `explain` text; VS Code grammar and the parity baseline; CTK scenarios including a woken child across a restart; starter tests; the tutorial examples; `docs-check`; Fabric (additive attributes, the Children section, **an old console against a new process**, F10). | each module |

## 3. The guards and how each is broken on purpose

| # | Guard | The test | Break it by |
|---|---|---|---|
| G16 | **A signal is received exactly once across a crash** (D8, principle 16) | Kill before every journal write of a signal's send, delivery and consumption; resume; check the receiver saw it once. | Writing the consumption cursor after acting on the signal. |
| G17 | **A signal sent before the wait is not lost** (D8) | `notify` first, then `wait signal`: it returns at once with the payload. | Delivering only to a waiter that is already waiting. |
| G18 | **A `wait any` winner is journaled; each signal is consumed once** (D11) | Run, replay: the same event; two `wait any` in a row never return the same signal; ordering when several are ready is the rule of D11. | Not journaling the winner, or not advancing the cursor. |
| G19 | **One run of a child at a time** (D12) | A `notify` while the child is running only writes its entry; a `notify` racing a child about to suspend again neither loses the signal nor starts a second run. | Removing the single-flight check. |
| G20 | **A `notify` to a settled child is a recorded no-op; an old generation's signal is not delivered to a new one** (D13) | Both cases. | Delivering to a settled child; ignoring the generation. |
| G21 | **Progress is validated and bounded; the value is `unchecked`** (D6) | A mismatched field fails the step; the size and count bounds drop and record; provenance is `unchecked` at the parent. | Accepting any field; resetting the tag. |
| G22 | **A signal's payload keeps its provenance in both directions** (D14) | The oracle's canary cases. | Resetting the tag when the entry is written or read. |
| G23 | **A signal never becomes a steer's text** (D14) | A script that routes a received payload into a steer is a load error. | Allowing a placeholder in a steer from a signal. |
| G24 | **Limits fail loudly** (D13) | The 17th signal of a name, the 65th in all, a long name, a payload over 2000 characters: each fails naming its limit. | Dropping silently. |
| G25 | **`cancel ... rewind to` re-runs from a declared checkpoint, at most N times** (F1) | The child's generation increases; the old generation stays as history; the N+1th is refused. | Allowing an undeclared checkpoint or no limit. |
| G26 | **Reservation includes the paired judge budget** (D3) | A `start` that cannot cover child plus judge is refused with a message; an unpaired `supervise` fails when it runs. | Reserving only the child. |
| G27 | **An unfinished child's `-> r` fails closed** (F2) | Reading `{r}` after a `wait` on a cancelled, failed or timed-out child raises the status error. | Binding an empty value. |
| G28 | **A wait times out on the injected clock** (F3) | A `wait` with a child still running returns on virtual time. | Using wall time. |
| G29 | **A handle never reaches a prompt, even if the static check is bypassed** (F7) | A runtime assembly path given a handle fails closed. | Removing the guard. |
| G31 | **A sticky signal is read without being consumed; a message is consumed once, FIFO per name** (D8) | Two waits for one sticky signal both return; two waits for one message name consume two messages in order; a replay reads the same. | Consuming a sticky signal; consuming out of order. |
| G32 | **Two same-name waits that can be active at once are refused** (D8) | A script with the same `wait signal` name in two branches of one `parallel` fails `weave check`; the same name in sequence or in a loop is accepted. | Dropping the check. |
| G30 | **The new words stay ordinary words elsewhere** (D17) | `-> report`, `set progress = ...`, a variable named `signal`, `parent` or `any` all still parse and run. | Reserving a word. |

G7 and G13 of phase 33, which were not broken on purpose, are broken on purpose in 34a (F14).

## 4. Mutation and coverage

- **`mutation-children` is extended** to the new deciding classes (the mailbox and its cursor, the wake path and its state machine, the `wait any` ordering, the progress validator and bounds, the reservation pairing) at **85% or more**; surviving mutants are killed with better assertions, never excluded. `KillPointTest` stays out of the profile for its run time, as in phase 33.
- New classes meet the per-class JaCoCo gates of the Loom module; no limit is lowered and no exclusion added.

## 5. The tiers and the loop

As phase 33: `-Dtest=` while working; `make test-core` after each sub-phase (and `make docs-check` after docs, a command, a starter or a review rule); `make test-fast` on every module touched after the phase; `make test-oracle` once at the end; never `make test-full` or `make test-live`. CI runs the full tier on every pull request (it was green on JDK 17 and 21 for phase 33).

## 6. What is not tested here

Real-thread behaviour under production load (the controlled scheduler is deliberate; the exceptions are named); a real model's judgement; waking a *sleeping* child by a timer (out of scope, D18); and the by-hand run of the demo with a real key (E4, the owner's).
