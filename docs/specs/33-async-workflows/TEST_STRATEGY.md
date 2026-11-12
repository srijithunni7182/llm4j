# Phase 33 test strategy: starting and supervising workflows

Status: **final for phase 33; the strategy was followed and what was built is recorded in [VERIFICATION.md](VERIFICATION.md) §1b.** Companion to [DESIGN.md](DESIGN.md); the decisions behind it are D17 and D30 in [DECISIONS.md](DECISIONS.md). Not everything below was built: a stubbed child (`children:` as a script of states) is not, and guards G7 and G13 were not broken on purpose (§3 of VERIFICATION lists the gaps).

## 1. Principles for these tests

1. **Hermetic.** No real model, no real clock, no network, no racing threads. Time is the injected clock; children run on a **controlled executor** driven by it; models and tools are scripted. A test that needs a real thread is tagged and justified.
2. **A guard is tested by breaking it.** For every guard below, break it on purpose once and check that its test fails (the project rule). The table in §4 says how.
3. **Behaviour, not structure.** Tests drive `weave test` cases, the public executor and host API, and the CLI, not private methods.
4. **The tiers stay honest.** The fast tier stays about two minutes: only the exhaustive kill-point test is `@Tag("slow")`, and the oracle runs once per session.

## 2. What is built to make testing possible (33e)

- A **`Clock`** injected into the executor (it exists: `ex.setClock`, `ex.setSleeper`); the supervisor tick and the judge cadence read it.
- A **controlled executor** for child runs and for the tick: tasks are queued and run when the test (or the virtual clock) advances, deterministically and one at a time; production uses real threads. It is an injected interface, not a flag in the engine.
- **`weave test` vocabulary:** `children:` (a stubbed child, one step per look, in the style of `remotes:`), `judges:` (a judge's verdicts one per look), and `expect:` keys `children:`, `fired:` (rule, virtual time), `starts:`, and steers `delivered` or `not delivered`; the existing `clock:` and `elapsed_virtual`.
- **Two modes for a child:** a **real child under scripted models** (the default, as `call` works) and a **stubbed child**.

## 3. Test layers

| Layer | What | Where |
|---|---|---|
| **Parse and load** | Each construct parses; contextual words stay ordinary words elsewhere (`start at watch`, `on_limit: wait`, a variable named `wait`); each load error (a `{h}` placeholder, a handle given to a tool or agent or returned, a placeholder in a steer, a start with no budget, an unbounded start in a loop, two `supervise` blocks for one handle, a wait with no timeout) fails with a message that says what to do; `RepositoryScriptsTest` loads every script. | Loom module, core tier |
| **Check** | Each warning and error of `weave check`: an unwaited handle, a result read without `is ok`, a `cost` rule with no price table, a name that is not steerable, an assertion that can never be violated, the sleep-versus-deadline warning, a start more than `max_children` allows. | core tier |
| **Engine, scripted** | `start`/`wait`/`cancel`/`steer` end to end with scripted children, on the controlled executor and the virtual clock: every status and its transition, `wait` on timeout, `wait all` recording every outcome, `h.partial`, a cancel of a sleeping or waiting child, effects finishing before a cancel lands. | core tier |
| **Supervise** | Every counter and comparison; `once`; fire-on-becoming-true; the tick; the one-steer-per-agent-per-tick rule; ordering (counted, code, judged); a `cancel` ending evaluation; the judge skipped after a cancel; judge cadence, floor, `max`, skip-if-unchanged; the judge budget running out; a judge's verdict journaled and replayed without a second model call. | core tier |
| **Budgets** | The child's cap, the smaller-of rule, the `BudgetSet` charging, reservation refusing a `start`, a child's budget running out (`failed`, budget category), the parent running out (children cancelled), the worst-case estimate. The `Budget` hold under **concurrency** (a stress test with the controlled executor and a real-thread variant tagged `slow` if needed). | core module and Loom |
| **Replay and kill-point** | See §4, G1. | `slow` tag |
| **Persistence** | The same child tests over `Persistence.file`, `Persistence.jdbc` (an in-memory database) and the CLI's sibling layout; the run record's parent link; `fork` copying children; an executor with a bare `RunJournal` refusing `start`. | Loom module |
| **Security** | The audit rule LA28; the trifecta union (a parent that looks safe but starts a child that can send is flagged); provenance across the boundary (a canary in a `start` argument and in a child's result is untrusted at the other end); a handle never reaching a prompt or tool; a steer refused for a non-`steerable` agent; a steer text that cannot carry a variable. | core tier and the oracle |
| **Ecosystem** | Graph snapshots (node kinds, edges, `sync-graph-render`), `explain` text, VS Code grammar and the parser-parity baseline, CTK scenarios, `StartersTest` for starter 18, the tutorial examples, `docs-check`, Fabric (the parent attribute, grouping, the Children section, the cost total) with the existing `loom-inspect` and `loom-fabric` tests. | each module |

## 4. The guards, and how each is broken on purpose

| # | Guard (design reference) | The test | Break it by |
|---|---|---|---|
| G1 | **Exactly one child, every effect once** across a crash (D4, §4.2) | Kill the parent at **every point** between `start` and `wait`, resume each time, and check there is one child and each effect happened once (an effect counter on a scripted tool). Exhaustive, so `@Tag("slow")`. | Deriving the child id from a random value or a counter; skipping the journal-first write. |
| G2 | **A rewind starts a fresh child; a resume re-attaches** (D4) | Rewind past a `start`: the old generation's child is cancelled first and a new one runs; a plain resume re-attaches. | Using the run id without the generation in the child id. |
| G3 | **A parent ending cancels its children** (D10) | Finish, `halt`, fail, cancel and rewind the parent with children running; each child ends `cancelled` with the reason; a child that does not settle within the grace period is recorded as such. | Removing the cancel-on-end. |
| G4 | **Reservation** (D11) | A `start` that would overrun the parent after the others' reservations is refused with a clear message; a concurrent test starts several at once. | Counting only what the parent has left now. |
| G5 | **Limits** (D14) | A start past `max_children` or `max_depth` fails; a script cannot raise past the host ceiling. | Ignoring the ceiling. |
| G6 | **Only `steerable` agents** (D12) | Naming or reaching a non-steerable agent is a check error and a runtime refusal; a placeholder in a steer is a load error. | Dropping the `steerable` test. |
| G7 | **A cancel never interrupts an effect** (D13) | A child in the middle of a scripted effect is cancelled: the effect completes once, is journaled, then the child stops. | Cancelling at once. |
| G8 | **A handle never reaches a model** (D2) | A `{h}` in a prompt, a handle as a tool argument, a handle in an agent's output path: each is refused. | Allowing the placeholder. |
| G9 | **Provenance across the boundary** (D15) | The oracle's canary cases: a canary in a `start` argument and in a child's result is `unchecked` on the other side. | Resetting the tag at the boundary. |
| G10 | **`never calls` is preventive** (D24) | The call is refused before it runs and an effect counter stays at zero; the block then runs. | Moving the check after the call. |
| G11 | **A judge sees facts only by default** (D25) | The judge's input contains no tool argument, result or agent output unless `seeing content`; the audit lists a rule that opts in. | Adding the content to the default view. |
| G12 | **A verdict is journaled and replayed** (D25) | Resume: the recorded verdict is returned and the judge's call count does not rise. | Calling the judge again on replay. |
| G13 | **The judge is bounded** (D26, D27) | The 30 s floor, a required `max`, skip-if-unchanged, the judge budget running out stops judged rules but not counted ones. | Removing the floor or the skip. |
| G14 | **A waiting parent keeps its children running; any other suspension pauses them** (D29, D8 amendment) | Time advances in a `wait` and the children progress; during a `human_prompt` their deadlines do not. | Pausing in `wait`. |
| G15 | **One steer per agent per tick** (D30) | Two rules steering one agent in one tick deliver one and record the other as skipped. | Delivering both. |

## 5. Mutation and coverage

- **A new profile `mutation-children`** in `ai-agent4j-loom` for the classes that decide things: the supervise rule evaluator and tick, the child-id derivation, the reservation, the limits, the ordering and the status transitions, at **85% or more**. Surviving mutants are killed with better assertions, never excluded. The core's `Budget` hold joins the existing core profile or a new one in `ai-agent4j`.
- New classes meet the per-class JaCoCo gates of the Loom module (typically 0.85 line and 0.75 branch or better); no limit is lowered and no exclusion added.

## 6. The tiers and the loop

| When | Run |
|---|---|
| While working | `-Dtest=` for the class |
| After each sub-phase (33a to 33h) | `make test-core`; and `make docs-check` after docs, a command, a starter or a review rule |
| After the whole phase | `make test-fast` on every module touched (Loom, core, `loom-inspect-core`, `loom-inspect`, `loom-fabric`, the extension build); `make format-check` where it applies |
| Once per session, at the end | `make test-oracle` |
| Not run in a session | `make test-full`; the other `slow` tests are left to CI |

## 7. What is not tested here

- The behaviour of real threads under load in production (the controlled executor is deliberate; one `slow` real-thread test of the `Budget` hold and of the kill-point loop is the exception).
- A real model's judgement: the judge is scripted; whether a real judge is good at "stays on topic" is an evaluation (`weave eval`), not a unit test, and is said so in the documentation.
- Children on other machines, and supervision across suspended runs (out of scope, D20).
