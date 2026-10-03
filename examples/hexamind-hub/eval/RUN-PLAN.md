# Plan: one real evaluation of Hexamind Hub, all layers, one report

Status: **Draft for your approval** · Spend: about **$6 expected, hard cap $10 (about ₹880)** · Builds on [`SPEC.md`](SPEC.md), [`COST.md`](COST.md), [`golden/`](golden), [`hexamind.loom`](hexamind.loom)

## What you get

One run that exercises all three layers on the real models and produces one dashboard report (`index.html`, plus the static edition and `summary.md`):

| Layer | What runs | Real models | Approx. cost |
|---|---|---|---|
| **Agent reasoning** | 48 persona scenarios, one agent each, recorded search fixtures | Gemini 3.5 Flash agents, Claude Sonnet 5.5 judge | $2.40 |
| **Prompt tests** | 12 scenarios on the current prompt and a candidate, with A/B | same | $0.80 |
| **Trajectory** | workflow logic (branches, rounds, budget stop, refinement) with a scripted model; then **3 real debates** (standard, fabricated premise, user feedback) on the Loom workflow | same | $0 + about $2.40 |
| **Calibration** | 30 judged cases, 3 judge samples each, to measure judge noise | Claude Sonnet 5.5 | $0.10 |

The report is a first baseline of **what Hexamind does today**: dimensions with goals, judge noise, cost per layer, agent traces, and the debate's path against the expected path. It is not a before/after comparison (that needs a second run, which you can do later for about $6).

## Before anything runs (you, about 20 minutes)

1. **Create or confirm the keys:** a Google AI Studio key (`GEMINI_API_KEY`) on a billing-enabled project, and an Anthropic key (`ANTHROPIC_API_KEY`). Keep them out of the repo; export them in your shell only.
2. **Set provider-side spending limits**, which are the real safety net: **$10 on Google, $5 on Anthropic** (if a provider offers only prepaid credit, load that amount instead). This is independent of anything in our code.
3. **Load credits:** about $8 on Google and the smallest Anthropic top-up (about $5). Total about ₹1,100 before tax.
4. **Confirm the Gemini model id** in the Google AI Studio model list (`gemini-3.5-flash` is what Hexamind uses). The smoke test below also checks it.
5. **Answer the three open questions** (or accept the defaults), because they change what the report says:
   - *Round 5 input*: default = keep the current behaviour for the baseline and **report the quirk** (it sees round 2 arguments, not rebuttals); fix it after.
   - *Agreement score*: default = report that it is a constant (0.8) and measure agreement with a judge instead.
   - *Search*: default = **recorded fixtures only** (deterministic, free, same text the grounding judge sees).

## Build (me, offline, $0): about 2 working sessions

Everything here is verified without any API key before a single paid call is made.

| # | Deliverable | Verified how (free) |
|---|---|---|
| B1 | `GoldenDatasetTest`: all 70 scenarios load, unique ids, known dimensions, tools, non-empty rubrics | runs in the normal build |
| B2 | `EvalSupport`: Gemini agents from `AgentConfiguration`, Claude judge (`AnthropicProvider` at effort `low`), judge cache, `EvalRun.declare*`, the dataset and agent declarations | unit test with a fake client |
| B3 | `FixtureSearchTool` (a `WebSearch` tool that returns each scenario's recorded snippets) | unit test |
| B4 | `ScriptedModel` (canned answers per agent and round, with injectable failures) and `TrajectoryPathTest` | runs in the normal build: debunk branch, five rounds, budget stop, refinement |
| B5 | `ReplayCache` (record and replay agent outputs) so a failed or repeated run never pays twice for the same scenario | unit test |
| B6 | `AgentReasoningEvalTest`, `PromptEvalTest`, `TrajectoryEvalTest`, `CalibrationEvalTest`, all behind `-Peval` | each runs end to end against the **scripted model and a fake judge**, producing a complete sample report |
| B7 | A **spend guard**: counts real tokens as they are used, prices them with `prices.properties`, and **stops the whole run** at $10 (`-Deval4j.judge.budgetUsd` for the judge, Loom's `budget` for debates, plus a global guard for the agents) | unit test that trips the guard |
| B8 | `eval/run-all.sh`: the one command, with a preflight and a stage-by-stage gate | dry run with `--fake` |
| B9 | `eval4j-report.yaml` for Hexamind (goals, priorities from `SPEC.md` section 3) and `prices.properties` | rendered in the dry-run report |
| B10 | the debate wiring for tests: a small `LoomDebateRunner` that runs `hexamind.loom` through `HarnessExecutor` with `LoomTrace`; search served by a local fixture endpoint through a Loom `http` tool | scripted-model run |

**Exit criterion for the build:** `eval/run-all.sh --fake` produces a full report from fake models, with every dimension populated, in under a minute and for $0. Only then do we spend.

## The paid run (you run it, about 30 minutes; each stage has a gate)

`eval/run-all.sh` runs the stages in order, prints the spend so far, and **stops at the first failed gate**. You can also run one stage at a time.

| Stage | What | Cost | Gate to continue |
|---|---|---|---|
| 0 | **Preflight**: keys present, model ids answer, limits set, spend guard armed | $0 | all green |
| 1 | **Smoke test**: one scenario (`alex-02`, the fabricated premise) end to end | about $0.01 | the agent searched, the judge returned a rating, the report renders; **measured Gemini thinking tokens per call within 2x of the 400 I assumed**. If not, re-run `cost_model.py` and re-confirm the budget before going on. |
| 2 | **Agent reasoning**, 48 scenarios | about $2.40 | under 5% of runs errored for harness reasons (not model behaviour); spend within 1.5x of estimate |
| 3 | **Prompt tests**, 12 scenarios x 2 versions | about $0.80 | same |
| 4 | **Trajectory, scripted**: workflow logic | $0 | all path assertions pass (these test the workflow, not the model) |
| 5 | **Trajectory, real**: 3 debates, one at a time | about $2.40 | the first debate stays under 2x its estimate (about $1.12) before the other two run |
| 6 | **Calibration**: 30 cases x 3 samples | about $0.10 | judge noise band measured |
| 7 | **Report**: render, open, export | $0 | report opens; all dimensions have data or an explained "No results" |

**Stop conditions (automatic):** total spend reaches $10; any single call exceeds 20,000 output tokens; a provider returns repeated 429 or billing errors; a debate loops past its Loom budget.

**If it fails midway:** nothing is lost. Every agent output and judge verdict is cached, so re-running continues where it stopped and pays only for what is missing.

## Reading the report (what to look at first)

1. **Overview**: which dimensions are under goal. Expect `fact-checking` and `safety` to be the interesting ones.
2. **Judges and models**: self-consistency and failure rate; this tells you how far to trust everything else.
3. **Traces**: the agent step traces (did Rahul search? did anyone repeat a call?) and the debate's path against the expected path.
4. **Cost and evidence**: real tokens and spend by layer; compare with this plan.
5. **Case drawers** for any failed fabricated-premise or injection scenario: those are the findings that matter most.

## Risks I already know about

| Risk | Mitigation |
|---|---|
| The Loom debate runs the agents in parallel, so it is not identical to the shuffled sequential Java debate | the first report evaluates the **Loom** engine; legacy parity is a later, optional run |
| Serving fixtures to a Loom agent needs the `http` tool or a Java tool registered with the runtime; I have not run that path | spike in B10 before any paid run; fallback: DuckDuckGo live search for the 3 real debates only (noisier, free) |
| Gemini free-tier or rate limits stall the run | the run resumes from the cache; stages are sequential |
| Token use is higher than modelled | stage gates compare real spend with the estimate before the next stage |
| A rubric is badly worded and the judge misreads it | the calibration stage and the case drawers show the judge's reason for every verdict; rubrics are YAML you can edit and re-judge from cache for pennies |

## What I need from you

1. Approve this plan, or change the cap.
2. Answer the three questions above (or say "defaults").
3. After the build: export the two keys, set the provider limits, and run `eval/run-all.sh`. I can walk through the output with you.
