# Phase 34: supervision, part two: closing the gaps, progress, signals and `wait any`

Status: **draft for the owner's decision.** Nothing here is built. Phase 33 built `start`, `wait`, `cancel`, `steer` and `supervise` (counted rules, assertions in code, judged assertions); this phase closes what that build left open and adds the three things D1 and D20 of phase 33 deferred: **declared progress**, **signals** and **`wait any`**.
The principles of [phase 33](../33-async-workflows/PRINCIPLES.md) all still hold and are not repeated; this file adds what is new. Every choice is in [DECISIONS.md](DECISIONS.md), open, with a recommendation.

## Where phase 33 stands (so this phase starts from facts)

Built in PR 118 and merged; CI green on JDK 17 and 21; **nothing at E4**. The build left fifteen gaps ([phase 33 VERIFICATION §3](../33-async-workflows/VERIFICATION.md)): several depart from decisions the owner accepted (`cancel ... rewind to`, the empty `{r}` after a `wait` on an unfinished child, the wait timeout on wall time, a stubbed child in `weave test`), the judge budget is charged but not reserved, and the judge's facts view was not taken from the evaluation dataset.
v3 (declared progress, signals, `wait any`) has no code at all: none of `notify`, `report` or `wait any` appears in the parser or the AST.

## What the code does today that shapes this phase (read, not assumed)

- `wait` **journals what it returned** on its first return and a replay applies the recorded outcome (`ChildLauncher.await`). That is the pattern a `wait any` winner and a consumed signal can follow.
- A child that sleeps or waits for a person throws `RunSuspended`, which `runChild` catches and records as `SLEEPING` or `WAITING`: **the child's thread ends.** Nothing in the code read wakes such a child in the same process; it is resumed when the parent replays and re-attaches. A signal sent to a waiting child therefore needs a new thing: waking it.
- A supervise tick writes what it did into the **parent's** journal (`<step>#fire.N`, `<step>#judge.<rule>.<n>`, with an intent entry before a steer, wrap-up or cancel), and a child's progress counters come from its own budget and journal (`signatureOf`).
- The statement parser recognises `wait` with `isWord("wait") && isNameToken(peekAt(1))`; **`report` is used as a variable name in 37 repository scripts** (`-> report`) and `progress` in two (starter 16), so the new words must be contextual in a stricter way than `start` and `wait`.

## The new principles

16. **A notification is a journal entry.** A signal is written to the receiver's journal before anyone acts on it; reading it is journaled too. So it is never lost when it arrives before the wait, and never delivered twice across a crash. (This is phase 33's principle 5, "signals, not locks", given its form.)
17. **Progress is data, not control.** What a child reports about itself is typed, bounded, journaled and `unchecked`. A script may show it or branch on it softly; a rule that cancels, an effect, or a payment never rests on it (phase 33's principle 10).
18. **One address, no discovery.** A parent signals a child through its handle; a child signals its starter through the fixed word `parent`. There is no name registry and no signalling between siblings in this phase.
19. **Bounded, as everything.** The number of pending signals, the size of a payload and the number of handles in a `wait any` have limits written down; a limit that is exceeded fails or drops with a record, never silently.
20. **Close what was promised before adding more.** A gap that departs from an accepted decision is fixed first (sub-phase 34a), and the owner reviews that before the new surface is built on it.
21. **One more verb at a time.** Each new construct earns its place against the same 60-second demo: a supervisor that also hears from its children (a child asks for help, a parent reacts) must fit on one screen.
