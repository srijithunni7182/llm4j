# The golden dataset

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(part 3). The guide's shares, sizes and thresholds are starting points: the domain expert sets them for the product.*

The golden dataset is the single most valuable artefact a test team produces on an AI project. Models change, prompts change, frameworks get replaced; a well-built dataset survives all of it and remains the instrument by which every change is judged. It is also the thing most commonly done badly, usually by being **generated quickly rather than curated carefully**. It is test data and test oracle combined: a versioned collection of cases, each carrying its input, everything needed to run it, the correct answer, and the metadata needed to grade and slice results.

**You do not write the ground truth.** Ask the domain expert; show every proposed case; tag what you drafted `author:assistant` until a named person adds `reviewed_by:<name>`. `weave eval <script> --check` reports how many drafted cases no person has reviewed.

## Anatomy of a case

A case is a record in source control, reviewed like code. In llm4j a dataset is YAML files in `eval/golden/` (one file per agent, one for the whole workflow; `weave guide` has the chapter). The metadata the guide asks for rides on **tags** (`key:value`), which the report slices by.

```yaml
- id: SUP-REFUND-021
  name: Duplicate charge, one refund
  input: "I was charged twice for order A-88213 last week. Can you refund one?"
  expected_output_contains: "A-88213"
  expected_output_not_contains: ["card number", "cancel the order"]
  expected_tools: [LookupOrder]
  rubric:
    - { text: "States that one of the two charges is refunded, and the amount", dimension: correctness }
    - { text: "Cites the refund policy rather than asserting it", dimension: groundedness }
  tags:
    - "domain:billing"
    - "difficulty:standard"
    - "completeness:complete"
    - "expected:answer-with-action"
    - "source:production"
    - "split:validation"
    - "author:support-lead"
    - "reviewed_by:support-lead"
```

What a full case should carry, and where it goes today:

| The guide's field | What it holds | In llm4j |
|---|---|---|
| id, version, author, created, last reviewed | who wrote it and when | `id`; `author:` and `reviewed_by:` tags; version control history; the dataset's `version:` in `dataset.yaml` |
| input | the request and any state of the user's world | `input` (and `inputs` for a workflow's other parameters) |
| available data, tiered **required / useful / irrelevant** | what the environment returns if asked; the tiers let you score retrieval precision and recall | recorded answers in `fixtures.yaml` for tools; the tiers are a gap (`eval4j-loom-mapping.md`) |
| ground truth | correct conclusion, accepted equivalents, explicitly rejected answers, acceptable and unacceptable actions, required citations | `expected_output_contains`, `expected_output_not_contains`, `expected_tools`, `rubric`; a **set** of accepted answers with a verdict is a gap |
| grading | expected terminal state, turn bound, cost bound, things it must not do, required rubrics | `expect:` lines, budgets and `weave test` for the bounds |
| metadata | domain, difficulty, input completeness, expected outcome, action risk, source, split | tags |

## Where cases come from: mix three sources on purpose

- **Production-derived, about 50%.** Real cases with outcomes confirmed by whoever resolved them. Highest fidelity: real inputs are messier, more ambiguous and more incomplete than anything a tester invents, and this is where you find that the model handles clean cases well and real ones badly. It costs anonymisation review, expert time and a pipeline to extract them. **Start the pipeline early even if the volume is small:** it takes longer to establish than expected, and a dataset without production cases tests the failures you imagined rather than the ones you have. In llm4j, `weave capture` turns a real run into a test case with personal data and secrets masked, and `weave regress` replays them.
- **Expert-authored, about 30%.** Written by domain experts for the known-hard cases, the rare failures and what production has not yet produced. This is where deliberate coverage comes from: the adversarial cases, the safety cases, the cases where the obvious answer is wrong. Production data will not contain enough of them because production is mostly ordinary.
- **Synthetic, about 20%.** Generated variants of the same underlying case with different vocabulary, device context or added noise. Use them for **robustness probing**: a model that handles a case phrased one way and fails it phrased another is brittle, and variants expose that cheaply. **Do not establish a capability from synthetic cases alone.** They inherit the assumptions of whoever generated them, and a model-generated case inherits that model's blind spots, which may be the system's own. Tag them `source:synthetic` and name the generating model.

## Coverage: over the input and failure space, not code paths

Define your dimensions explicitly and report the gaps. A generic starting set, adapted per product:

| Dimension | Values | Why |
|---|---|---|
| Domain | the subject areas the system handles | prevents over-fitting to one area |
| Difficulty | trivial / standard / hard / adversarial | aggregate scores hide difficulty shifts |
| Input completeness | complete / partial / contradictory / corrupted | real inputs are rarely clean |
| Expected outcome | answer / answer with caveats / **escalation** / **refusal** | see below |
| Scope of impact | narrow / wide | blast-radius testing |
| Novelty | novel / near-repeat / exact repeat | for systems with memory or retrieval |

## The three case classes nobody builds, and everybody needs

Datasets assembled from real successful cases share three gaps. **Ask the expert for them**; do not invent them. Fill each deliberately, at **a minimum of about 10% of the cases each**.

| Class | Correct behaviour | What it catches |
|---|---|---|
| **Unanswerable** | State that the answer cannot be determined; escalate | A system that invents an answer whenever it cannot find one. The most common serious failure, and a dataset of solvable cases never reveals it |
| **Obvious answer is wrong** | Reach the non-obvious correct answer, or state uncertainty | Pattern-matching in place of reasoning |
| **Correct action is harmful if wrong** | Choose the conservative option, or escalate | Unsafe outputs where the intuitive fix is destructive |

Without the first class your accuracy metric measures a system that has never been asked to say "I don't know", and you discover in production that it cannot.

## How many cases

**Quality dominates quantity, decisively.** 120 well-labelled cases are worth more than 5,000 generated ones, because grading is only as good as the ground truth and ground truth is expensive.

| Stage | Target | Rationale |
|---|---|---|
| Initial viable set | 40 to 60 | enough to detect gross regressions; small enough to label properly and sanity-check by hand |
| Working set | 100 to 150 | enough to slice by two dimensions and keep statistical meaning per slice |
| Mature set | 200 to 400 | diminishing returns beyond this unless the domain is unusually broad |

Watch **cases per slice**, not just the total. If you report accuracy by difficulty and by domain and a cell has four cases, that cell's number is noise. **Ten cases per reported slice is a reasonable floor.**

## Splitting the dataset is not optional

| Split | Share | Access rule |
|---|---|---|
| Development | 60% | visible to whoever tunes prompts; iterate freely |
| Validation | 25% | used for gate decisions; contents visible, but no prompt may be tuned against a specific case |
| Held-out | 15% | **sealed**: not visible to anyone who tunes the system; executed at release gates only |

The characteristic failure of evaluation programmes: **prompts get tuned until the test set passes.** This is not misconduct; it is what iteration looks like when you can see the results. The detector is the **generalisation gap**: validation score minus held-out score. A small gap means the improvements are real; a widening gap means you are fitting the test set. Report it as a first-class metric. If the gap exceeds about 10 percentage points, refresh the dataset.

In llm4j: tag a case `split:development`, `split:validation` or `split:heldout`. Held-out cases never run unless `weave eval --release-gate` is given, and the report states that it was, and shows the gap.

**Ownership rule: the dataset owner must not also own the prompts.** The party being measured must not control the measuring instrument.

## Labelling quality

Ground truth is produced by humans and inherits human inconsistency. If two experts disagree about the correct answer, no model can be graded reliably against either. Establish this before labelling at scale:

- **Double-label a sample**: two experts independently label 20 to 30 cases and you measure agreement.
- **Measure it properly.** Raw percentage agreement overstates, because some agreement happens by chance. Cohen's kappa corrects for this. Read it as: below 0.4 poor, 0.4 to 0.6 moderate, 0.6 to 0.8 substantial, above 0.8 strong. **Aim for 0.7 or better** on ground-truth labelling. (The stack does not compute it yet: a spreadsheet does, `eval4j-loom-mapping.md`.)
- **Treat disagreement as a specification finding, not an annotator problem.** When two experts disagree on the correct root cause you have found either an ambiguous case (fix the case, or record both answers as acceptable) or an unclear definition of what the system is supposed to do (escalate to product). Neither is fixed by asking the annotators to try again.
- **Write a labelling guide**: two pages on what counts as the root cause versus a contributing factor, how specific an answer must be, when to record several acceptable answers, and when to mark a case unanswerable. Without it, labels drift as different people contribute over months. Keep it beside the dataset.

## Governance

| Practice | Detail |
|---|---|
| Version control | the dataset lives in source control; case changes are reviewed like code changes |
| Pinned per release | results are comparable only within a dataset version; record it in every run (the report names the dataset's identity; a changed case is a changed dataset) |
| Refresh cadence | rotate about 15% each quarter, favouring newly available production cases |
| Archive, don't delete | retired cases are archived so historical comparisons stay interpretable |
| Growth from failures | every production failure and every successful red-team attack becomes a candidate case; review monthly; aim for 30% of the dataset derived from real failures within a year |
| Review cycle | cases go stale (a correct answer in March may be wrong in September after a product change); re-review annually at minimum |

## Anti-patterns

Generating the dataset with the same model family being tested (it passes its own weaknesses); only cases the system currently passes (a certificate rather than a measurement); no unanswerable cases (confident fabrication goes undetected); ground truth written by the test team without domain review (it measures agreement with the team's assumptions); cases with no metadata (you cannot slice, so you cannot diagnose); a dataset owned by the prompt engineer; a dataset never refreshed. These are the checklist of review mode (`failure-modes.md`).
