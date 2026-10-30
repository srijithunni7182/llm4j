# Mapping the design onto llm4j

*The decisions come from the owner's guide* Quality Assurance for AI agentic systems*; this page says what the stack does about each today. `weave guide`, `weave man` and `weave <command> --help` are the truth about names and options: if this page and the tool disagree, the tool is right and this page is a bug.*

Read it with the project open. For each decision of the design, find the construct that carries it, the `weave guide` page that builds it, and **what the stack does not yet do**. A gap is never hidden: record it in the **Gaps and workarounds** table of `EVAL_DESIGN.md` with its register id, and use the workaround in the meantime. When a gap closes, its row here goes.

## What carries what

| Decision | Carried today by | Built in | Gap and workaround |
|---|---|---|---|
| **E0** one real answer under a cap, before anything is built around it | `weave preflight` (one capped real question per agent, with the key, the model and the answer's wholeness checked), then `weave run` with a cap; `weave next` shows where the project stands | guide 5, 9 | none |
| **E1** deterministic layer, tested without a model | `weave test`: scripted models, tools and tasks, injected faults, route and call assertions, `--coverage` and `--min-coverage`; your own unit tests for tasks | guide 3, 5, 8 | none |
| **E1** which parts are probabilistic | `weave explain` and `weave graph` read the workflow's steps and agents back | guide 6 | none |
| **E2** the nine dimensions | `dimensions:` on a case, `dataset.yaml` declaring what each means; a rubric line can name its dimension; the report's *By dimension* section | guide 2 | none for naming and reporting; most dimensions need their own checks (below) |
| **E3** deterministic assertion | `expected_output_contains`, `expected_output_not_contains`, `expected_output`, `expected_min_words`, `expected_tools` (no judge, a fixed check); `weave test` assertions on a scripted run | guide 2, 5 | none |
| **E3** equivalence class (a **set** of accepted answers, rejected answers, acceptable and unacceptable actions) | `expected_output_contains` for one required element; several cases for several acceptable phrasings | guide 2 | **F57**: no `exact / acceptable / rejected / unmatched` verdict, no accepted-equivalents list on a case. Workaround: a rubric line per acceptable outcome, `expected_output_not_contains` for the rejected ones, and read *unmatched* by hand |
| **E3** rubric | `rubric:` lines (each judged met or not, a line is met at 0.7 on the judge's scale) | guide 2, 3 | **F56**: a line is one criterion, not an anchored 1 to 5 scale. Workaround: keep the anchored rubric as a document in the dataset folder and write each rubric line from one anchor |
| **E3** metamorphic relations | none as a feature; any case can be written twice (a paraphrase, an added irrelevant fact) and compared by hand | guide 2 | **F58**: no derived cases and no relation assertion. Workaround: write the pair as two cases with the same `tags: ["relation:paraphrase", "pair:<id>"]` and compare their verdicts in the report |
| **E4** splits and a sealed held-out set | tags `split:development`, `split:validation`, `split:heldout`; held-out runs only with `weave eval --release-gate`; `--split <name>`; the generalisation gap in the report | guide 2, 9 | none (the seal is a guard, not encryption: the files are readable) |
| **E4** drafted cases reviewed by a person | tags `author:assistant` and `reviewed_by:<name>`; `weave eval --check` counts the unreviewed | guide 11 | none |
| **E4** the three missing case classes (at least about 10% each) | write them as cases with tags (`class:unanswerable`, `class:obvious-wrong`, `class:conservative`) and read the share from the tag counts in *By slice* | guide 2 | **F59**: the dataset does not require or count the classes. Workaround: the tag convention and a look at the slice table; a slice below the stated size is reported as an anecdote, not a rate |
| **E4** dataset version, identity, archive | `version:` in `dataset.yaml`, the dataset's hash in every report, version control for the files | guide 2 | none (**F62** is closed for identity and baselines) |
| **E4** labelling agreement (Cohen's kappa), the labelling guide | a labelling guide kept beside the dataset; kappa in a spreadsheet from two experts' labels of 20 to 30 cases | guide 2 | **F56**: no agreement figure in the tool |
| **E5** configuration fingerprint | the report's *Read this first* (run, judge, cost, fingerprint) and *How to reproduce* | guide 5 | none |
| **E5** trace capture | Loom journals every step; `weave observe`, `weave why`, `weave timeline` and the dashboard read it | deploy 4 to 7 | none |
| **E5** fixtures and when they are refreshed | `fixtures.yaml` (a tool's recorded answers); `weave capture` records real runs as cases | guide 2, 8 | none; the refresh is a calendar item in the run protocol |
| **E5** simulator with fault injection | `weave test` `fail:` (timeout, rate limit, server error, invalid output, tool error, refused, quota), scripted tool results | guide 5, 8 | none for the faults; a *slow* fault is the virtual clock (`clock:`) |
| **E6** repetitions, pass@1 and pass^N, consistency, resolution | `weave eval --repetitions N` (1 to 10), `--judge-samples N`; the report states its resolution and names unstable cases | guide 2, 9 | none |
| **E6** the run protocol (per change, nightly, release gate, weekly drift) | commands in CI: `weave eval --split validation --repetitions 3`, `weave eval --split validation --repetitions 5`, `weave eval --release-gate --repetitions 10`; a scheduled job for the drift check | guide 9 | **F64**: no single place states the protocol. Workaround: write it into `EVAL_DESIGN.md` §6 and the CI file |
| **E6** the budget circuit breaker | `weave eval --max-tokens`, `--max-cost` (with `--prices`), `--max-calls`; a real run says what it expects and asks first | guide 5, 9, 16 | none |
| **E7** static security of the workflow | `weave audit` (the lethal trifecta, tool reach, secrets), `weave review`; system-level controls are code (tasks, approvals, `NetPolicy`, `guard`) | guide 7 | none |
| **E7** the payload corpus and "controls held" | cases whose input or fixture holds the payload, run against sandboxed or scripted tools in `weave test` so an effect is *recorded, not done*; assert the effect did not happen | guide 5, 8 | **F63**: no versioned corpus runner and no automatic "controls held for every successful payload" check. Workaround: a folder of payload cases tagged `kind:injection`, an assertion per case that no forbidden effect was recorded, and a human read of each success |
| **E7** red teaming | a calendar item before a major gate; every success becomes a case | guide 8, 10 | none (a process, not a tool) |
| **E8** regression after a change | `weave regress` replays captured cases; `weave eval --baseline` compares with the current **and** the previous baseline | guide 8, 9 | none |
| **E8** baseline policy | `weave eval --save-baseline --approver <name> --why "<reason>"`; a baseline on another dataset is refused unless `--allow-version-change "<why>"` | guide 9 | none |
| **E9** gates | `weave gate` runs the checks the team chose (check, review, test, regress, eval) and prints one verdict for CI; `weave eval` exits 1 when something failed; `weave test --min-coverage` | guide 9 | a floor plus a regression band is two checks (`weave eval` and `weave eval --baseline`); the floor and the band are not yet one declared gate |
| **E9** a validated judge | `weave eval --judge <model>` (a different model from the one judged), the report says whether the judge was validated | guide 2, 9 | **F56**: no human-scored reference set, no kappa, no bias checks. Workaround: score about 40 outputs by three people in a spreadsheet, compute kappa there, and record the result in `EVAL_DESIGN.md` §9; until then the judge does not gate |
| **E9** efficiency: cost per successful task, p95 cost, runaway rate, graceful termination | the report's cost per passing case and its *Cost* section; bounds in the script (`loop ... max`, `budget`, `retry`, `timeout`) and `weave test` asserting they fire | guide 5, 9, 16 | **F61**: p95 cost, runaway rate and graceful-termination rate are not reported. Workaround: read them from the run bundle's per-case cost |
| **E9** trajectory and multi-agent probes (routing confusion matrix, handoff context loss, loop induction, eviction) | `weave test` route and call assertions, `fail:` to induce a loop, `humans:` for multi-turn, `weave graph` for the legal edges; `weave eval` scores a classifier's own scenarios | guide 8, 14 | **F65**: no confusion-matrix report, no handoff or eviction probe. Workaround: one scripted case per probe |
| **E9** calibration | an `intentclassify` question with a confidence rule gives a structured confidence; bucket by hand from the cases | guide 6 | no calibration table in the report |
| **E10** ownership | named in `EVAL_DESIGN.md` §10 | n/a | none (a decision, not a tool) |

## Sources of numbers to set per product

The guide's shares, sizes and thresholds (50% production, 30% expert, 20% synthetic; 10% per missing class; 40 to 60, 100 to 150, 200 to 400 cases; 60/25/15 split; kappa 0.7; precision 0.75, recall 0.85, claim support 0.90; floor and band) are **starting points**. Record in the design who set the real ones and when.

## What the stack will not do for you

- Decide what correct means. Only the domain expert can.
- Prove a judge trustworthy. The tool reports whether it was validated; the validation is a person's work (`judge-and-pipeline.md`).
- Make a mock run mean something. `weave eval --mock` and `weave test` prove wiring. Never quote them as quality.
- Run a corpus against a real sender, payer or writer. Use scripted or sandboxed tools.

## Tools you may meet that are outside the stack

DeepEval, RAGAS, Promptfoo, LangSmith, Langfuse, Arize Phoenix and Braintrust are Python or hosted tools that cover parts of this ground. They are not recommended here as replacements for `weave eval` and eval4j, and nothing in this skill depends on them. If the person already uses one, keep its results in the same design record under the matching stage, with its version pinned.
