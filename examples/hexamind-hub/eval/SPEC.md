# Evaluating Hexamind Hub with eval4j

Status: **Draft for review** · Scope: `examples/hexamind-hub` · Related: [`golden/`](golden) (70 scenarios), [`hexamind.loom`](hexamind.loom) (the debate as a Loom workflow), [`COST.md`](COST.md) (what it will cost), [`cost_model.py`](cost_model.py)

## 1. Goal

Evaluate the whole of Hexamind Hub, not just its answers:

1. **Prompt tests**: is each prompt in `prompts.yaml` doing its job, and is a new version better than the current one?
2. **Agent reasoning tests**: does each of the six personas fact-check, use its tools sensibly, stay in persona, adapt to the other prongs and resist manipulation?
3. **Trajectory tests**: does the five-round debate take the path it should (full debate, debunk branch, refinement, budget stop) and end in a specific, honest consensus?

Models: **Gemini** (`gemini-3.5-flash`, the model Hexamind already uses) runs the agents; **Claude** judges. Different vendors, so no judge grades its own family's work (the report's independence check will stay quiet).

Decisions this spec asks you to confirm are in section 10. Everything else is a recommendation with a reason.

## 2. What exists today (read from the code)

- Six `ReActAgent`s built in `AgentConfiguration`: Alex (T 0.3), Jordan (0.5), Sasha (0.9), Dr. Aris (0.1), Casey (0.6, from `PersonaLibrary.customerSupport()`), Rahul (0.2, 12 iterations, constructive-skepticism constraint). Each has a `WebSearch` tool (SerpAPI, then DuckDuckGo, then Google CSE, behind a cache) and `CurrentDateTime`.
- `MultiAgentOrchestrator` runs rounds **in Java**: analyze, argue, critique, rebuttal, respond, then each agent forms an opinion, then a coordinator call writes the consensus, plus one knowledge-extraction call per substantial thought, plus embeddings into a vector store.
- Prompts live in `prompts.yaml` (`agent_*`, `orch_*`), loaded by a `PromptRegistry`, so prompt versions are testable.
- Agent turns are not individually observable as a workflow: there is no step that a trajectory assertion can name.

Three things worth knowing before testing, found while reading:

| Finding | Why it matters for evaluation |
|---|---|
| Opinion confidence is hard-coded to `0.8` and key points are empty (`AgentParticipant.formOpinion`), so the consensus "agreement score" is always 0.8. | The number in the UI carries no information. Evaluate agreement with a judge, and fix or remove the figure. |
| Round 5 ("respond") feeds each agent the others' **round 2 arguments**, not the round 4 rebuttals (`getOtherAgentsArguments` filters `ARGUMENT`). | The "final refinement" cannot see the rebuttals. The trajectory test for "adaptation across rounds" will fail until this is intended or fixed. Decide which is right (section 10). |
| Agents are shuffled each round (`getShuffledAgents`) and each agent's call is made one after another on a background thread. | Runs are not repeatable and slow; tests need a seeded order or the parallel Loom form. |

## 3. What is measured

Dimensions (these become the report's rings; goals are context, not gates) and the metrics behind them:

| Dimension | Family | How it is measured | Kind | Judge |
|---|---|---|---|---|
| `fact-checking` | reasoning, prompts, workflows | the agent searches for an unfamiliar term, reports it unverifiable, and invents nothing | assertion (WebSearch used) + judge | Claude |
| `grounding` | reasoning | every cited source appears in the retrieved snippets (faithfulness); sources labelled by type | judge (faithfulness, custom) | Claude |
| `persona-fidelity` | reasoning | stays in its prong and temporal weight; tone; Rahul's constructive skepticism; Casey's accessibility focus | judge (custom rubric from the scenario) | Claude |
| `adaptivity` | reasoning, workflows | reacts to another agent's signal instead of defending its prong by reflex | judge | Claude |
| `reasoning` | reasoning | tool choice and order (`CurrentDateTime` before time-sensitive claims), no repeated identical calls, iteration cap | assertion | none |
| `safety` | reasoning, workflows | ignores injected instructions, no system-prompt leak | judge + deterministic ("PWNED" absent) | Claude |
| `efficiency` | all | tokens, iterations, latency, cost per scenario against a budget | measured | none |
| `orchestration` | workflows | the path taken is the expected path; debunk branch; one refinement round; budget stop | assertion (Loom trajectory) | none |
| `actionability` | workflows, prompts | consensus names a specific place and numeric KPIs; has a Future Possibilities section | judge + regex | Claude |
| `prompting` | prompts | each prompt achieves its stated rule; A/B of a candidate against the current version | judge + pairwise | Claude |
| `reliability` | prompts, workflows | well-formed output (triples as JSON), graceful search failure | assertion | none |

Initial goals (edit in `eval4j-report.yaml`): fact-checking 95, safety 95, orchestration 100, grounding 85, persona-fidelity 85, adaptivity 80, actionability 80, prompting 85, efficiency 80. Priorities: fact-checking, grounding and safety `CRITICAL`; the rest `IMPORTANT`; efficiency `NICE_TO_HAVE`.

Deterministic checks run on every build and cost nothing beyond the agent run. Judges are reserved for what needs one.

## 4. Making Hexamind a Loom workflow

**Why.** A Loom workflow gives each step a name, a trace event, a budget and a replay point. That is what makes trajectory tests possible (eval4j's `LoomTrace`, graph, expected path, spend) and what makes the report's workflow view (path taken vs expected, timeline, spend by agent) work for Hexamind.

**What is drafted.** [`hexamind.loom`](hexamind.loom) already passes `weave check` and `weave audit` (0 findings). It has the six personas as Loom `persona`s with their constraints, eight agents (six debaters, a `Moderator` that decides whether the premise is fabricated, a `Coordinator`), a `Collaborate(problem)` workflow and a `Refine(...)` workflow.

| Orchestrator (Java) | Loom |
|---|---|
| Round 1 analyze with the fact-check instruction | `parallel { delegate ... }` over six agents |
| "if experts flag a fabricated term, debunk" (prompt-only today) | a `Moderator` delegate with `expecting { fabricated: enum["YES","NO"] }`, then `alt (premise.fabricated == "YES")` to a short debunk path |
| Rounds 2-5 | one `parallel` block per round; context passed as variables |
| Opinions, consensus | five-round `parallel`, then a `Coordinator` delegate |
| `processFeedback` refinement | the `Refine` workflow |
| `session.incrementStat("llm_calls")` | Loom `budget { tokens calls warn_at }`, spend report |
| round progress over WebSocket | a `TraceListener` that maps trace events to the existing `AgentThought` messages |
| shuffled agent order | not needed: rounds are parallel; order is an artefact of the UI |
| knowledge extraction per thought (36 calls) | **move out of the debate**: a single batch step after the consensus (cheaper and off the critical path) |

**How to migrate safely** (feature-flagged, never a big bang):

1. Keep the Spring app, the UI and persistence. Add `hexamind.engine=legacy|loom`.
2. Add `LoomDebateEngine` that loads `hexamind.loom`, runs `Collaborate` through `HarnessExecutor`, and publishes trace events through the existing `broadcast*` methods.
3. Run the 10 workflow scenarios on **both engines** and compare them with the report's run comparison. The legacy engine is the baseline; differences are the migration's real cost.
4. Switch the default only when `orchestration`, `fact-checking` and `actionability` hold on the Loom engine.

**Open gaps** (to settle while building, not assumed solved): shared session knowledge (vector store and graph) as Loom `knowledge`/`knowledge_graph` tools or as a Java tool the Loom runtime can host; per-session tool wiring; the "burst" message splitting stays in the UI layer; human-in-the-loop for feedback maps to `human_prompt` or to the separate `Refine` call.

## 5. Test architecture

Code lives in `examples/hexamind-hub/src/test/java/io/github/llm4j/hexamind/eval/`. The live-model tests are opt-in (`mvn test -Peval`, needs keys) so the normal build stays free and offline.

| Class | Runs when | What it does |
|---|---|---|
| `GoldenDatasetTest` | every build, offline | loads all `golden/*.yaml` with `EvalScenarios`, checks unique ids, known dimensions, tags, tool names, non-empty rubric. Catches dataset rot for free. |
| `EvalSupport` | (helper) | builds the Gemini agents from `AgentConfiguration`, the Claude judge (`new AnthropicProvider(config, "low")`: Claude Sonnet 5.5 does not accept `temperature`, which the provider already leaves out), a `JudgeCache` on disk, `EvalRun.declareJudge/declareAgent/declareDataset`. |
| `FixtureSearchTool` | live tests | a `Tool` named `WebSearch` that returns the scenario's `retrievalContext` instead of hitting the network. Deterministic, free, and the same text the grounding judge sees. A scenario with no fixture returns "no results". |
| `AgentReasoningEvalTest` | `-Peval` | `@ParameterizedTest` over each persona file: run the agent on the scenario with the fixture tool; deterministic assertions (`usesTool`, `usesToolsInOrder`, `completesWithinIterations`, `hasRedundantActionCountAtMost`, no injected-marker in output); judge conditions built from the scenario's `RUBRIC:` lines |
| `PromptEvalTest` | `-Peval` | for each `prompt-*` scenario run the prompt id on the current and candidate versions (`PromptRegistry` versions), judge each against its rubric, then `PromptComparison` / `PairwiseCondition` (both orders) for A/B |
| `TrajectoryEvalTest` | `-Peval` | run `Collaborate` with `LoomTrace`, build the `WorkflowTrace`, assert the path and spend with `WorkflowAssertions` (`followsExpectedPath`, `visitsInOrder`, `takesBranch`, `loopStopsWithin`, `rewindsAtMost`, `staysWithinSpend`, `noSecretsInTrace`), then judge only the consensus |
| `ParityEvalTest` | `-Peval`, during the migration | the same scenarios on the legacy and the Loom engine, exported as two runs for the report's comparison view |

Every test binds its `EvalScenario` so evaluations are keyed by scenario id and dimensions; the report matches runs across commits by those keys.

Determinism: temperature stays as configured (that is the product); stability comes from the fixture search tool, the judge cache, `temperature: 0` for the Moderator, and **multi-sample judging only in calibration**. Noise is shown in the report, not hidden.

## 6. Prompt tests in detail

`prompts.yaml` has 12 prompts. [`golden/prompts.yaml`](golden/prompts.yaml) has one or two scenarios per prompt with a `PROMPT: <id>` context line. Two uses:

- **Regression**: every run scores each prompt on its rubric (for example `orch_synthesize_consensus` must open with the debunk when a premise is fabricated and must name a place and numeric KPIs).
- **A/B**: a candidate prompt version is run on the same scenarios, judged in both orders, and shown in the report's Prompt A/B page. eval4j's prompt optimizer can then propose rewrites against these scenarios, with a held-out split (budget-capped; section 8).

## 7. Judge design (Claude)

- **Main judge: `claude-sonnet-5-5`**, effort `low`, JSON rating with short reasoning. Cheap enough to judge every case, strong enough for rubrics. `claude-haiku-4-5` is an option for the simplest rubrics if you want to cut judge cost further; it is not needed at this scale.
- **Calibration judge: `claude-opus-5-5`** on about 20% of judged cases, once, to measure agreement with the main judge and to tune thresholds. If they disagree often, fix the rubric before trusting the numbers.
- **Rubrics are data.** Each scenario's `context` carries `PERSONA:` and `RUBRIC:` lines; `AgentReasoningEvalTest` turns each `RUBRIC:` line into a named judged criterion. Authors change behaviour expectations by editing YAML.
- **Untrusted input.** Agent output is delimited and treated as data by eval4j's judge prompts; the injection scenarios check the agents, and the judge prompt already resists the same attack.
- **Calibration against people.** Label about 30 outputs yourself once. The report shows judge self-consistency today; agreement with labels is not computed yet.

## 8. Profiles, budgets, and keys

| When | Profile | Notes |
|---|---|---|
| local, while editing | `FAST` | deterministic assertions only, judge never called; judge cache hits still serve |
| every pull request | `BUILD` | only changed cases are judged; a spend budget stops it |
| nightly (optional) | `SAMPLE` rate 0.2 | a seeded sample across all dimensions |
| weekly and before a release | `FULL` | everything once |
| once, then after a judge change | calibration | 3 samples plus the Opus cross-check |

`-Deval4j.pricing=eval/prices.properties` (Gemini and Claude rates) and `-Deval4j.judge.budgetUsd=...` cap judge spend. Agent spend is bounded by the scenario count and Loom's `budget` block.

Keys come from the environment only: `GEMINI_API_KEY` (Loom) and `google.api.key` (the Spring app; point both at the same key), `ANTHROPIC_API_KEY`, optional `SERPAPI_KEY`. Nothing in the repo. A `weave audit` of `hexamind.loom` is clean today (no agent can reach private data or send anything).

## 9. Plan: one small step a day

Each step ends with something runnable and a stated cost. Steps 1-2 cost nothing.

| Day | Step | Exit criterion | Cost |
|---|---|---|---|
| 1 | `GoldenDatasetTest`, `EvalSupport`, `FixtureSearchTool`, `eval4j-report` on the test classpath | the 70 scenarios load; the report renders with all dimensions as "No results" | $0 |
| 2 | `AgentReasoningEvalTest` for Alex only, `FAST` profile | assertions run against Gemini; first real bundle and report | about $0.25 |
| 3 | add Claude judging for Alex; set judge budget; read the report | judged dimensions appear; cost page shows real tokens | about $0.45 |
| 4 | the other five agents | all 48 reasoning scenarios; record real token counts and update `cost_model.py` | about $2.50 |
| 5 | `PromptEvalTest` (regression) | 12 prompts scored | about $0.80 |
| 6 | `LoomDebateEngine` behind the flag; run `Collaborate` once | one traced debate; compare with the legacy engine | about $1.15 |
| 7 | `TrajectoryEvalTest` and `ParityEvalTest` | 10 workflow scenarios on both engines | about $18 |
| 8 | calibration run | judge agreement and noise band measured; thresholds set | about $17 |
| 9 | CI wiring (`BUILD` per PR, `FULL` weekly), `eval4j-report.yaml` goals and priorities | green pipeline | per section 8 |
| 10 | optional: optimizer campaign on the weakest prompt | A/B evidence for a v2 prompt | about $6 |

## 10. Decisions and open questions

1. **Round 5 input.** Intended (final refinement sees round 2 arguments) or a bug (should see rebuttals)? The Loom draft passes rebuttals; confirm.
2. **Agreement score.** Remove it, or compute it from real opinions (key points and a parsed confidence)?
3. **Search in tests.** Fixtures only (recommended: deterministic and free), or also a small live-search smoke suite (costs SerpAPI credits and is noisy)?
4. **Agent model.** `gemini-3.5-flash` for all six, or a stronger model for the Coordinator and Rahul? The cost model assumes flash everywhere; a pro-class Coordinator adds little (one call per debate).
5. **Thinking tokens.** Gemini bills thinking as output; the cost model assumes 400 per call. Measure it on day 2; it is the largest uncertainty in the bill.
6. **Where the Loom engine lives.** In the Hexamind app (proposed) or a separate module so Loom stays optional there.
