# The nine quality dimensions

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(part 4). Targets below are the guide's typical starting points, to be set by the domain expert per product, never adopted as a standard.*

Each dimension is specified like any non-functional requirement: a definition, why it matters, a metric, how it is measured, and how the metric misleads. **Not every dimension applies to every system. Select deliberately and record which you excluded, and why** (E2 in `EVAL_DESIGN.md`: a reason for every one).

In llm4j a case names its dimensions (`dimensions: [safety]`, or a rubric line `{ text: ..., dimension: tone }`), the dataset's `dataset.yaml` declares what each means, and the report has a *By dimension* section.

## 1. Relevance

*Is what the system retrieves, requests or produces pertinent to the task?* Applies to RAG, agents that gather data, recommendation and search. Irrelevant retrieval consumes the context window, dilutes the model's attention, raises cost and invites wrong reasoning; it is also the first dimension to degrade during prompt iteration.

Needs items in the dataset labelled by tier: **required**, **useful**, **irrelevant**.

| Metric | Formula | Typical target |
|---|---|---|
| Precision | (required + useful) retrieved ÷ total retrieved | 0.75 |
| Recall | required retrieved ÷ required available | 0.85 |
| Waste ratio | irrelevant retrieved ÷ total retrieved | at most 0.15 |
| Redundancy rate | items requested that were already available | 0% |

Worked example: the system requests 8 items; 5 were required, 1 useful, 2 irrelevant; 6 required items were available. Precision 6 ÷ 8 = 0.75, recall 5 ÷ 6 = 0.83, waste 2 ÷ 8 = 0.25. Measurement: extract the request list from the trace, classify each against the tiers, aggregate. **Misleads:** precision and recall trade against each other and neither means anything alone (recall is maximised by retrieving everything, precision by retrieving nothing); report both and watch waste as the leading indicator. Requesting something already available is a defect on first occurrence.

## 2. Groundedness

*The proportion of factual claims supported by the supplied material.* The direct counter-measure to hallucination, and largely deterministic, which makes it one of the best-behaved dimensions.

| Metric | Definition | Target |
|---|---|---|
| Unresolvable citation rate | citations resolving to no source item | **0%** |
| Claim support rate | substantive claims traceable to a source ÷ substantive claims | about 0.90 |
| Contradiction rate | claims contradicting the supplied source | **0%** |
| Fabricated specificity rate | runs containing invented identifiers, paths, values | 0% |

Measurement: parse every citation; resolve each against the retrieved set; for each resolved citation check the passage supports the claim (string or semantic matching handles most, sample the rest by hand); decompose the output into atomic claims and check each has support. The step that catches what automation most often misses: **a real source cited for a value it does not contain**. Spot-check two or three citations per output. **Misleads:** claim support can be inflated by attaching citations indiscriminately; meaningful only alongside a sampled check that cited sources contain what is claimed. In Loom, a grounded agent's citations are `{agent_citations}` (see the grounding chapter in `weave guide`).

## 3. Correctness

*The proportion of outputs falling within the accepted answer set.* Metrics: accuracy by exact match; accuracy by accepted equivalents (typically the headline number); premature-conclusion rate (about 5% or less); **appropriate refusal rate on unanswerable cases (about 85% or more)**. Measurement is equivalence-class matching against ground truth (`oracles.md`, type 2). **Misleads:** an aggregate hides the difficulty distribution (always slice), and accuracy measured only on answerable cases says nothing about behaviour when the system cannot find an answer, which is where the worst failures live.

## 4. Completeness

*Whether the output addresses everything the request required.* Often overlooked because incomplete output is not wrong, it is partial, and partial output passes correctness checks. Measurement: the case lists required elements; check presence (largely a Layer 1 assertion). Watch for the pattern where a system asked for three things reliably delivers two: it scores well on every dimension except this one. In llm4j, `expected_output_contains` and `expected_min_words` express required elements.

## 5. Calibration

*The correspondence between stated confidence and observed accuracy.* Applies to any system that expresses confidence, and critically any where a confidence threshold triggers an action (auto-approve above 90%, escalate below 60%): miscalibration converts directly into wrong actions at the worst moment. **Prerequisite: confidence must be a structured field, not a phrase in prose.** If the system only says "high confidence", calibration is not measurable: raise it as a design gap (in Loom, an `intentclassify` question with `confidence at least` gives a field).

Measure by bucketing every run by stated confidence and computing actual accuracy per bucket:

| Stated | Runs | Correct | Observed | Gap |
|---|---|---|---|---|
| 81 to 100% | 68 | 53 | 78% | **12** |
| 61 to 80% | 41 | 30 | 73% | +2 |
| 41 to 60% | 19 | 11 | 58% | +8 |
| below 40% | 12 | 4 | 33% | +3 |

Expected Calibration Error (ECE) is the run-weighted mean of the absolute gaps (about 0.08 here, which looks acceptable), but the top bucket is 12 points overconfident, and that is the bucket that gates action. **Report the table, not just the number, and gate on the high-confidence bucket separately.** Aggregate ECE can look fine while the decision-relevant bucket is badly wrong, because low-confidence buckets carry few runs and offset it.

## 6. Instruction following

*Adherence to explicit constraints in the request or system prompt: format, length, scope, prohibitions.* Adherence degrades as context grows and conversations lengthen: a system that follows format instructions perfectly at turn 1 may ignore them at turn 15. Mostly Layer 1 assertions. **Test at depth, not just at turn one.**

## 7. Coherence over a conversation

*Whether the system stays internally consistent across turns.* Failure modes: contradicting an earlier statement, forgetting a stated constraint, re-asking something already answered, losing the thread after a topic shift. Measurement: long multi-turn cases with planted consistency probes (ask the same question in different words at turn 3 and turn 15 and compare), and assert no re-request of information already provided. Context-window eviction shows up here as a symptom: if coherence degrades sharply past a certain conversation length, look at the eviction policy before the prompt.

## 8. Safety

*Whether outputs are free of harmful content and, for systems that act, whether actions are bounded, reversible and correctly scoped.* Much broader than content filtering for an agent:

| Question | Test |
|---|---|
| Can it generate a destructive action? | static scan of generated actions against a prohibited-operation list |
| Is scope proportionate to evidence? | narrow input must not produce broad-scope output without recorded justification |
| Are irreversible actions flagged? | assertion, plus a separate acknowledgement in the approval flow |
| Is the action idempotent? | execute twice; the second execution is harmless |
| Does it check preconditions? | execute against a system not in the expected state; it must abort |

**The prohibited-operation list is enforced by a static check on the output, not by an instruction in the prompt.** Instructions are advisory; a check is a control. The list needs an owner with operational authority, not the test team and not the feature team. In Loom the checks are code: tasks, `alt` guards, approvals and `weave audit`; see `security-testing.md`.

## 9. Efficiency

*Resource cost per outcome, and boundedness of that cost.* A performance dimension with an unfamiliar unit: token spend is a variable cost per transaction.

| Metric | Note |
|---|---|
| Median cost per task | baseline it in the first release; bound regression thereafter |
| **Cost per *successful* task** | the number that matters: a system that abandons hard cases cheaply optimises the naive metric and degrades this one |
| p95 cost | tail behaviour; a few runaway sessions dominate the bill |
| Runaway rate | sessions hitting a hard bound |
| Graceful termination rate | bound hits that preserved work |

**Bounded execution is a functional requirement.** An agent loop terminates on a model's judgement, so it needs external bounds, independent of one another because any one can be the binding constraint depending on the failure mode: step or turn count (warn, then terminate with a partial-results summary), wall clock (warn, then suspend and notify), token or cost budget (warn at 75%, terminate at 100%), retry or refinement count (escalate with the failure history). Termination must be graceful: state preserved, partial findings summarised, session resumable; a terminated task that discards its work is worse than an expensive one. In Loom these are `loop ... max`, `budget`, `retry` and `timeout`, with `on_exhausted` and `on_failure` deciding the outcome.

**Test technique: loop induction.** Deliberately construct cases that cause cycles (ambiguous input, contradictory evidence, an environment returning identical unhelpful responses, a failure retrying cannot fix), then verify each bound fires independently and that termination preserves work. **Misleads:** cost and quality trade against each other; reported on separate dashboards, each gets optimised against the other unnoticed. Put them on the same scorecard.
