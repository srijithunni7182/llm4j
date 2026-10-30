# The evaluation pipeline, the judge, and reading results

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(parts 6 to 8 and the appendix). External works the guide points to: Hamel Husain on validating an LLM judge by measuring its agreement with a human (https://hamel.dev/notes/llm/evals/); Zheng et al., "Judging LLM-as-a-Judge with MT-Bench and Chatbot Arena" (https://arxiv.org/abs/2306.05685), for the documented biases. Its Python tool landscape (DeepEval, RAGAS, Promptfoo, Langfuse, Phoenix, LangSmith, Braintrust) is outside the llm4j stack and is not a recommendation to replace it.*

You are building a whole evaluation architecture, not just a test suite: a golden dataset, a trace store, a runner that executes the matrix of case × configuration × repetitions, layered checks, a scorecard. Keep the evaluation suite beside the application with its own release cadence and its own owners.

## The layers, cheapest first

1. **Layer 1: hard assertions.** Cheap, fast, defensible. Build as many as you can before touching a judge. Zero-tolerance checks fail the build on a single occurrence (they are not rates); keep them in a separate step from rate-based checks so the distinction is visible in the output. Typical: citations resolve, tool errors are handled, only legal transitions, no prohibited action, within bounds, no cross-session leakage.
2. **Layer 2: outcome matching.** Compare the answer with the accepted set from the dataset: exact, acceptable, rejected, unmatched. How to match, in increasing cost and decreasing determinism: **structured output** (the system emits a category code, not prose; strongly preferred, ask for it at design time), embedding similarity above a threshold tuned on a calibration set, an LLM comparison (last resort; it adds non-determinism to your oracle). If you find yourself tuning a similarity threshold to make grading work, the ground truth is under-specified: add accepted equivalents rather than loosening the threshold (the equivalents are documentation, the threshold is a fudge factor). **The *unmatched* verdict is information:** an answer matching nothing in your lists is not automatically wrong, it may be a correct answer nobody anticipated. Route those to human review, add the accepted ones to the equivalents and bump the case version. **Track the unmatched rate: above about 10%, your ground truth is incomplete and every other number is suspect.**
3. **Layer 3: the judge.** A model scores outputs against a rubric, for what the first two layers cannot decide and at volume.
4. **Layer 4: human audit.** A sample, to keep the others honest.

## The judge is an instrument

A judge must be calibrated against human judgement **before** it gates anything, pinned to a fixed configuration, and audited.

1. **Write the rubric first, as a versioned file, one dimension per rubric.** Describe each level (anchors at 1, 3 and 5 with an example), end with scoring notes ("length is not quality; score this dimension only; an unsupported claim is unsupported even if true"). Keep it in the dataset folder under version control, not as a sentence inside a prompt.
2. **The judge prompt does five things deliberately:** tells the judge it is *not* evaluating whether it would have answered differently (it stops the judge substituting its own opinion for the rubric); includes the ground truth (otherwise a second unvalidated model is deciding correctness); includes the retrieved material (groundedness cannot be judged without seeing what was available); asks for **reasoning before the score** (a disputed score becomes auditable); and an `uncertain` flag (low-confidence judgements go to humans instead of silently entering the average).
3. **Run the judge at temperature 0.** The system under test runs at production temperature; the instrument measuring it should be as stable as possible. This is the one place where temperature 0 is right. **Score one dimension per call** where budget allows: combined scoring produces halo effects.
4. **Use a different model family from the agents** (a judge favours output from its own family).
5. **Validate it, before any gate uses it, and again on every judge change.** Take about 40 outputs spanning the quality range, each scored independently by **three humans** against the same rubric. **Check inter-human agreement first** (quadratic-weighted Cohen's kappa): if the humans do not agree with each other, no judge can be validated against them, and the problem is the rubric, not the model. Then measure judge-versus-human-consensus agreement.

| kappa | Meaning | Action |
|---|---|---|
| below 0.4 | poor | the rubric is broken: rewrite the anchors |
| 0.4 to 0.6 | moderate | not good enough for gating: improve the anchors |
| 0.6 to 0.8 | substantial | usable |
| above 0.8 | strong | good |

(Below 0.6 judge-versus-human: the judge is **not usable for gating on that dimension**; list the cases where it is off by 2 or more.) **Ongoing audit:** human-score 5% of judged runs each cycle, track judge-human agreement over time as a metric in its own right and report it beside the quality scores. **The signal to watch: judge scores rising while human agreement or expert acceptance falls.** That means the rubric has drifted away from what matters, and it invalidates every product conclusion drawn from it until fixed.

**Known judge biases** (published and measurable): *verbosity* (longer output scores higher: an explicit rubric note, and track length as a separate metric); *position* (first or last favoured in comparisons: randomise order); *self-preference* (favours its own family: use a different family); *sycophancy* (confident tone scores higher regardless of correctness: ground truth in the prompt, a separate calibration dimension); *anchoring* (the first dimension scored influences the rest: separate calls per dimension).

## The harness underneath

- **A configuration snapshot with a fingerprint.** Every run records what produced it: model and version, temperature, the prompt version (hash or tag; if prompts are inline strings in code, fix that first, you cannot version what you cannot locate), workflow version, tool versions, dataset version, judge model, judge prompt version, application commit. **The fingerprint is what you group results by; two runs with different fingerprints are not comparable, full stop.** In `weave eval` the report's *Read this first* section carries a fingerprint, and the dataset's identity.
- **Trace capture of everything**, retrofitting is expensive because every run before the fix is un-analysable. At minimum: messages, agent starts, handoffs, tool calls (name, arguments, result, latency, **error recorded distinctly from the result**, or silent continuation cannot be detected), retrieval (query, documents, scores), state snapshots at phase transitions, errors (where, what, whether handled). Loom journals every step and `weave observe`, `weave why` and the dashboard read it.
- **An environment simulator with fault injection**, the single highest-leverage component after the trace store: without it most negative testing is impossible. Faults to support: timeout and unavailable (retry and degradation), partial (does it notice it got half the data?), malformed (does bad input corrupt state?), permission denied (scope failures), repeat-unhelpful (loop induction), slow (timeout bounds, latency), adversarial (injection delivered in normal-looking content). In llm4j this is `weave test`: models, tools and tasks are scripted and `fail:` injects faults.
- **Fixtures recorded once against the real environment and replayed deterministically**, refreshed deliberately, on a schedule (quarterly, or whenever the real API changes): stale fixtures mean your evals test a world that no longer exists. In llm4j, `fixtures.yaml` serves tools their recorded answers.
- **Pin everything** (model versions, judge, libraries), and keep a separate API key for the judge so its spend is visible; judge spend is often a third of the total.

## Running the matrix

| Context | Cases | Runs each | Frequency |
|---|---|---|---|
| Per change | core subset (about 20) | 3 | every change touching prompts, agents, tools |
| Nightly | full validation set | 5 | scheduled |
| Release gate | validation + held-out | 10 | on demand |
| Drift check | validation set | 5 | weekly, fixed configuration, regardless of changes |

**The weekly drift check is the one people skip.** It is the only mechanism that catches provider-side changes, which arrive with no commit and no warning.

Rules for the runner: parallelise but respect rate limits (if you see 429s the harness is the problem, not the system: back off rather than recording failures); **an exception is a result, not a crash** (a run that throws is recorded as a failed run with an error trace; losing it means your denominator is wrong); **a budget circuit breaker is not optional** (a runaway agent multiplied by 5 repetitions and 120 cases produces a memorable bill; set it, and set it low the first time).

**Cost control.** A cheaper judge (validate it separately: it may not reach kappa 0.6 on harder dimensions); fail fast on Layer 1 (do not pay to judge structurally broken runs); judge a sample (all runs at gates, about 30% nightly); cache by content hash (identical output judged once); fixture replay (removes environment cost); structural replay in CI (zero model cost, catches orchestration defects). **Track evaluation spend as its own metric: if evaluation costs more than the feature it measures, the sampling strategy is wrong.**

## Gates

Two thresholds, doing different jobs: an **absolute floor** (never ship worse than this) and a **regression band** (do not get worse than the last release by more than a tolerance). Never a bare threshold at current performance.

- Fragile: `accuracy == 0.87` (fails on noise). Fragile: `accuracy >= 0.87`, a hard floor at your current level (fails half the time).
- Right: `accuracy >= 0.80` ("below absolute floor") **and** `accuracy >= baseline - 0.03` ("regression beyond tolerance").

Use tolerance bands, pin the judge model, keep a stable golden set, so that non-deterministic output does not break the pipeline on noise alone. Zero-tolerance checks (a citation that resolves to nothing, an illegal transition, a prohibited action, a cross-session leak) fail on a single occurrence. **An unvalidated judge does not gate.** The numbers are the domain expert's to set.

## Reading the results

- **Always report pass^N next to pass@1.** pass@1 is the fraction of runs that passed; pass^N is the fraction of cases that passed *every* one of N runs. A gap between 84% and 62% is the honest picture: users retrying will see a different answer more than a third of the time. `weave eval --repetitions N` reports both, and consistency.
- **Always report the generalisation gap** (validation minus held-out). About 7 points is acceptable; above 10 means you are fitting the dataset: refresh it.
- **Always slice** (by difficulty, domain, expected outcome); a flat aggregate hides improvement on easy cases masking degradation on hard ones. A cell with fewer than about 10 cases is noise.
- **Report the unmatched and rejected rates** (answers matching nothing, and answers matching a known-wrong option).
- **Cost per successful task** next to the median cost.

**Is this movement real?** Apply this before anyone investigates:

1. **Outside the historical variance band?** Mean ±2 standard deviations over the last 10 comparable runs. If not, it is noise: stop here.
2. **Statistically significant?** If p ≥ 0.05, you do not have the resolution to call it: say so.
3. **Reproduces on a re-run at the same configuration?** If not, flag the case as unstable; three non-reproductions goes to the dataset owner.
4. **Correlates with a configuration change in the window?** Attribute and investigate.
5. **Reproduces and correlates with nothing?** Suspect provider-side drift: check release notes and your version pinning.

**Know your resolution.** Detecting a change of 20 percentage points needs about 100 runs; 10 points about 400; 5 points about 1,500. At 120 cases × 5 repetitions = 600 runs you can reliably detect about a 10-point change: **anything smaller is below your instrument's resolution.** Saying so out loud is more useful than reporting it as a finding, and it prevents the most common wasted week in AI testing. (`weave eval` states its resolution in every report.)

**Per-case stability.** A case whose pass rate sits in the middle (between about 0.4 and 0.6) is neither passing nor failing reliably, which usually means the case is under-specified: an incomplete accepted-answers list, genuine ambiguity in the input, or two valid interpretations. A case at 2 runs in 5 for three consecutive nightlies is telling you about the case, not the product: send it to the dataset owner rather than raising a defect.

**Baselines.** Re-baselining needs a **named approver and a written reason**, and is never automated. Compare against the current baseline **and the previous one** (a single-baseline comparison hides a slow multi-release decline). A system that quietly accepts each new result as the new normal drifts arbitrarily far from where it started, and every individual step looks reasonable. `weave eval --save-baseline --approver <name> --why "<reason>"` and `weave eval --baseline` do this.

## What to report (a model)

```
Config: a3f9c21b48e0   Dataset: v1.7   Runs: 600 (120 cases x 5)
  pass@1        84.2%   (baseline 85.1%, -0.9pt, within tolerance)
  pass^5        61.7%   (baseline 63.3%, -1.6pt)
  consistency@5 78.3%
  unmatched      4.1%     rejected 2.8%   <- answers matching a known-wrong option
  by difficulty  trivial 96%  standard 88%  hard 71%  adversarial 62%
  by split       validation 84.2%  held-out 76.8%  gap 7.4pt
  cost per pass  $0.42     median $0.31     p95 $1.44
```
