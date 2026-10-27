# Loom developer experience: the program

Status: **phases 9 to 16 built** (phase 12 is open on its pass rate, D13); the open decisions are in 09-review-engine/OPEN_ITEMS.md. Phases 9 to 16 of the [roadmap](README.md). Each phase has its own
`DESIGN.md`, `TEST_STRATEGY.md` and `VERIFICATION.md`; this page is the map: why they exist, how they fit, and in what order to build them.

## 1. The thesis

Loom removed the plumbing: retries, timeouts, budgets, human steps, crash-safe resume, schedules and rewind are keywords, not code a team writes.
What still slows a team down is not the plumbing. It is the **loop around the workflow**:

```
describe  ->  generate  ->  review  ->  test  ->  debug  ->  ship  ->  watch  ->  learn from production  ->  (describe)
```

Today every arrow is a separate thing the developer must know exists, run by hand, and read in a different format:
`weave check`, `weave audit`, `weave explain`, `weave graph`, `weave eval`, `weave replay`, a Fabric page. The pieces are individually good.
What is missing is that **feedback is fast, in one place, and tells the developer what to do next**.

The goal of this program is stated as measurable promises (§3), not as features.

## 2. The phases

| # | Phase | One line | Depends on |
|---|---|---|---|
| 9 | [Review engine](09-review-engine/DESIGN.md) | `weave review`: nine default lenses (security, validity, cost and performance, reliability, prompts, observability, durability, testability, privacy) and four optional (governance, maintainability, oversight, blast radius) in one ranked list, each finding with a fix; static cost, time and effects estimates; **the rules are data in versioned rule packs over a small fact model, so adding one is a file change, not a release** | existing `check`, `audit`, `graph`, price tables |
| 10 | [Editor experience](10-editor-experience/DESIGN.md) | A VS Code review panel beside the graph; quick fixes; cost lenses; a semantic layer (references, rename, typed completion) fed by one parser; a long-lived `weave serve` so feedback is instant | 9 |
| 11 | [Workflow tests](11-workflow-tests/DESIGN.md) | `weave test`: scripted models, tasks, tools and humans; assert the route taken; branch coverage; failure injection; no model, no keys | graph, existing eval mock |
| 12 | [Generate from a description](12-generate/DESIGN.md) | `weave generate`: a description becomes a script, prompts, tests and a review summary, and is never shown until it passes the gates | 9, 11 |
| 13 | [Time-travel debugging](13-time-travel/DESIGN.md) | `weave why`, a stepper with breakpoints, `weave whatif` (replay a run from a step with a change), run overlay on the graph, a debug adapter | travel (`fork`, `OverlayJournal`), journal |
| 14 | [The production loop](14-production-loop/DESIGN.md) | `weave capture` turns a real run into a test; `weave regress` replays real history against a candidate; a CI gate; one click from a Fabric incident | 11, 13 |
| 15 | [Embed and deploy](15-embed-and-deploy/DESIGN.md) | Typed Java bindings for workflows; `@LoomWorkflows` interfaces in the Spring starter; `weave deploy` produces a container and compose/k8s files with Fabric wired in | 9, Spring starter, `package` |
| 16 | [First run](16-first-run/DESIGN.md) | `weave try` (zero keys, zero setup), `weave doctor`, a one-line install, one guided path from nothing to a running, reviewed, tested workflow | 11 (demo models), 12 |

### How they connect

```
            describe in English
                   |
              12 generate ----------------------------+
                   |                                  |
   +---------------v------------+           +---------v---------+
   |  script + prompts + tests  |<--------->| 11 workflow tests |
   +---------------+------------+           +---------+---------+
                   |                                  |
            9 review engine  ----> 10 editor      13 time-travel
          (findings, fixes, cost)   (panel, LSP)     (why, step, whatif)
                   |                                  |
              15 embed / deploy                       |
                   |                                  |
             Fabric (watch) -------> 14 production loop (capture, regress, gate)
                                              |
                                  back into 11 (tests) and 9 (rules)
```

Three contracts are shared by several phases and are specified once, in the phase that owns them:

| Shared contract | Owned by | Used by |
|---|---|---|
| **Finding**: rule id, lens, severity, location, node id, message, optional machine-applicable fix | 9 | 10, 12, 15 |
| **Test case file** (`*.test.yaml`): input, scripted replies, expectations | 11 | 12, 14, 16 |
| **Run overlay** (which nodes ran, which did not, counts, cost per node) | 13 | 10, Fabric |
| **`weave serve`**: a long-lived process answering JSON requests over stdio | 10 | 12, 13 (debug adapter) |

## 3. The promises (what "better" means)

Each is checked by a test or a measured acceptance criterion in the phase that owns it. Numbers are targets to confirm against the benchmark
corpus (§5), not claims.

| # | Promise | Owner |
|---|---|---|
| P1 | From a finished script to "what is wrong with it, ranked, with fixes" is **one command or one click**, in under 3 s warm, under 10 s cold | 9, 10 |
| P2 | Every workflow shows **what a run costs before it runs**: a typical and a worst-case figure with its assumptions; an unbounded one says so | 9 |
| P3 | A developer can **test branching and failure handling with no model and no keys**, in milliseconds, in CI | 11 |
| P4 | A description becomes a workflow that **already passes check, review and its own tests**; failing that, the developer is told which gate failed and why | 12 |
| P5 | For any run, "**why did it go there?**" has an answer in one command, computed from the journal, with no re-run | 13 |
| P6 | "**What would have happened if...**" for a past run is one command, with side effects simulated | 13 |
| P7 | A production failure becomes **a regression test in one command**, and a prompt change is checked against **real history** before it ships | 14 |
| P8 | Calling a workflow from Java is **typed**: no strings for inputs, results or workflow names | 15 |
| P9 | From nothing installed but Java to a **running, traced workflow in under two minutes with no API key** | 16 |
| P10 | **No new surprise**: nothing here changes how an existing script runs. Every phase is opt-in, additive, and leaves `weave run` standard output, exit code and journal byte-for-byte (the one addition is a single review notice line on standard error, P11) | all |
| P11 | **Advisory, never blocking**: open review findings never stop a run, resume, package, serve or deploy from the CLI, the editor or the skills; the developer is told in one line and decides. Only commands chosen for enforcement (`weave review --fail-on`, `weave gate`, `weave deploy --require-clean`) exit non-zero on findings | 9, 10, 15 |

## 4. Principles for this program

These extend the [principles](README.md#principles) of the roadmap.

1. **Static first.** Anything that can be known by reading the script (the language is total; loops are bounded or flagged; effects are declared) is computed without a model.
   That is Loom's advantage over frameworks whose control flow is arbitrary code, and every phase should use it.
2. **One parser, one truth.** The Java parser/validator is the source of truth. The editor, the generator and the CI gate consume its output (JSON, via the CLI or `weave serve`);
   nothing re-implements the grammar. (The extension's TypeScript parser, `parseDocument.ts`, shrinks to what must work while the Java side is unreachable; see phase 10.)
3. **Every finding is actionable.** A message without a next step is a bug in the rule. A fix is a machine-applicable edit when it can be one, a sentence when it cannot.
4. **Honest estimates.** Cost and reliability numbers are labelled estimates with the assumptions shown, and a calibration path against real runs exists.
5. **Nothing spends or sends by surprise.** Debugging, replay, regress and try default to simulated effects and recorded or scripted models. A run that spends says so before it starts.
6. **Same engine everywhere.** The CLI, the editor, CI, the generator and Fabric call the same code and show the same rule ids.
7. **Fast enough to be used.** Feedback that takes a minute is not used. Speed is a requirement with a number, not a hope.
8. **Hostile text is data.** Prompts, run transcripts, captured cases and generated files are all treated as untrusted when displayed or re-used, as in earlier phases.
9. **Advise, never gate.** Review, the notice and the skills inform and offer fixes; they do not stand between the developer and running or shipping their workflow. Enforcement is a team's explicit choice made in a command or CI job, never a default and never a hidden setting.

## 5. Shared infrastructure the phases need

| Item | Why | Built in |
|---|---|---|
| A **benchmark corpus** of 40+ scripts (the repo's examples and samples, plus deliberately flawed ones with the flaw labelled) | Measures rule precision/recall (9), generator pass rate (12), estimator error (9) | 9a |
| A **price and window catalog** read the same way everywhere (`prices.properties`, windows from phase 5) | Cost estimates must equal what a run would be charged | 9 (reads existing) |
| **`weave serve`** (stdio JSON-RPC, one process, a cache keyed by file content hash) | JVM start per call is the reason editor feedback is slow | 10a |
| A **rule registry** with stable ids, docs and an "explain this rule" page | `weave review --explain LR03`, hover text, docs generation | 9a |

## 6. Order of work

Build in this order; each step is shippable and useful alone.

1. **9a, 9b** Finding model and `weave review` over today's check and audit. (Immediately useful from the CLI; unblocks 10 and 12.)
2. **11** Workflow tests. (Independent of 9; the most requested capability and the base of 12, 14 and 16.)
3. **10a to 10c** `weave serve`, the review panel with the graph, quick fixes.
4. **9c to 9e** Cost estimator, reliability rules, prompt rules, `--fix`.
5. **13a to 13c** `why`, `whatif`, the overlay; then the stepper.
6. **12** Generate (needs 9 and 11 to have teeth).
7. **14** Capture, regress, gate.
8. **15, 16** Bindings and deploy; first run (these land best when the rest exists to show off).

## 7. Out of scope for the whole program

- A visual workflow *editor* (drag-and-drop). The graph stays a view of the script, not a second source of truth.
- Hosting. `weave deploy` produces files; it does not operate infrastructure.
- Training or fine-tuning models from captured runs.
- A hosted service or telemetry. Nothing in this program phones home.
- New LLM providers or vendor integrations (the program is about the loop around the workflow, not model breadth).
