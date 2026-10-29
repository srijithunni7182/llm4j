# Phase 23 verification plan: the evaluation design skill

Status: **not started** (specs in draft). Run §0 first, then top to bottom at the end of each sub-phase (proposed: **23a** the references and the record, R1, R2, R4, R10; **23b** the rules, review mode and the hand-overs, R3, R5 to R8; **23c** packaging, tests and the dry run, R9). Results in §7.
Finding: F27 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md). Source of the method: the owner's QA guide (tag **Q**).

## 0. Before writing anything

| # | Task | Done when | Result |
|---|---|---|---|
| P1 | **Phase 19's inventory** ([its VERIFICATION §0.2](../19-evaluation-that-works/VERIFICATION.md)) is recorded, because the mapping page must say truthfully what the stack can do today | each of I1 to I14 is marked present, partial or absent with evidence | ⬜ |
| P2 | **The owner answers D11 and D13** in the program page: how the guide may be used and credited, and that its examples are translated to Loom and eval4j | the program page records both | ⬜ |
| P3 | **The owner confirms the guide's licence** for distribution with the repository (Apache 2.0) and which external works may be quoted | recorded in §7 | ⬜ |
| P4 | **Read the harness skill's references** (`principles.md`, `failure-modes.md`, `loom-mapping.md`, `design-record.md`) and keep the same voice and shape | a one-paragraph note of what is reused | ⬜ |
| P5 | **Baseline the field session**: for each of F26, F29, F30, F31, F34, F36, write down what the assistant did and what the skill must make it do instead (this is the checklist for §4 M1) | the list is in §7 | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | The skill exists with the files of [DESIGN §3](DESIGN.md), a valid front matter that triggers on the evaluation questions, and no link to a repository path | `SkillStructureTest`, `SkillDescriptionTriggersTest`, `NoRepositoryPathsTest` |
| A2 | The skill runs the ten stages of DESIGN §4 R2 in order, shows the checklist after each, and writes `EVAL_DESIGN.md` from a template whose sections are those stages | `StageTableMatchesTemplateTest`, `EveryStageHasQuestionsAndARecordFieldTest`, `ChecklistAfterEveryStageIsStatedTest`, M1 |
| A3 | The skill asks and never invents ground truth; anything it drafts is marked for expert review; it asks for the three missing case classes | `SkillForbidsInventingGroundTruthTest`, `ProposedCasesAreMarkedInTheExampleTest`, M1 |
| A4 | Every decision is mapped to a construct that exists, or to a gap with a register id and a workaround; every command the page names exists | `EvalMappingNamesRealCommandsTest`, `GapRowsNameARegisterIdTest`, `NoPromiseWithoutAProofTest` |
| A5 | The nine rules of R5 are present verbatim and cannot be removed unnoticed | `RulesPresentTest`, §6 rule-removal checks |
| A6 | The skill tells the developer how to read results (pass@1 and pass^N, the gap, slices, unmatched rate, resolution, the five-step test, per-case stability) | `ReadingResultsPageHasTheFiveStepTestTest`, `ResolutionTableMatchesTheArithmeticTest` |
| A7 | Review mode finds each planted anti-pattern, ranks it, and says **not verified** where the files do not settle it | `ReviewModeFindsEveryAntiPatternTest`, `ReviewModeSaysNotVerifiedTest`, M2 |
| A8 | The hand-overs exist in all four skills and `weave next` mentions the design when it is missing | `HandOversArePresentTest`, `WeaveNextMentionsEvalDesignTest` |
| A9 | The skill installs, packages and is listed everywhere the others are | `InstallSkillInstallsFourTest`, `SkillPackageContainsEvalDesignTest`, `PublishingSkillDocMentionsItTest`, `ReadmeAndLlmsListItTest` |
| A10 | The guide is credited and its external works linked; nothing copied at length | `AttributionPresentTest`, review of the pages against the guide by the owner |
| A11 | Every sample parses with the real tool, and the worked example is a valid design record | `SamplesParseTest`, `WorkedExampleIsValidTest`, `TemplateAndExampleAgreeTest` |
| A12 | **A fresh coding agent following the skill would have prevented the field session's evaluation mistakes** (F26, F29, F30, F31, F34, F36) | the dry run of [TEST_STRATEGY §5](TEST_STRATEGY.md), recorded in §7 |
| A13 | **Nothing in the owner's guide is missing** from the skill, or its absence is recorded as a decision | the coverage matrix in §2.2, every row filled |

## 2. Traceability

### 2.1 Requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `SkillStructureTest`, `SkillDescriptionTriggersTest`, `NoRepositoryPathsTest` | ⬜ |
| R2 | `StageTableMatchesTemplateTest`, `EveryStageHasQuestionsAndARecordFieldTest`, `ChecklistAfterEveryStageIsStatedTest` | ⬜ |
| R3 | `SkillForbidsInventingGroundTruthTest`, `ProposedCasesAreMarkedInTheExampleTest`, M1 | ⬜ |
| R4 | `EvalMappingNamesRealCommandsTest`, `GapRowsNameARegisterIdTest`, `NoPromiseWithoutAProofTest` | ⬜ |
| R5 | `RulesPresentTest`, `ControlsAreOneHundredPercentWordingTest` | ⬜ |
| R6 | `ReadingResultsPageHasTheFiveStepTestTest`, `ResolutionTableMatchesTheArithmeticTest` | ⬜ |
| R7 | `ReviewModeFindsEveryAntiPatternTest`, `ReviewModeSaysNotVerifiedTest`, M2 | ⬜ |
| R8 | `HandOversArePresentTest`, `WeaveNextMentionsEvalDesignTest` | ⬜ |
| R9 | `InstallSkillInstallsFourTest`, `SkillPackageContainsEvalDesignTest`, `PublishingSkillDocMentionsItTest`, `ReadmeAndLlmsListItTest` | ⬜ |
| R10 | `AttributionPresentTest` | ⬜ |

### 2.2 Coverage matrix: the owner's guide → the skill (nothing missed)

Every part of the QA guide is listed. A row is ✅ when the named reference page covers it (checked by the owner in M3), ⬜ until then, and **declined** only with a reason recorded in §7.

| Guide part | Content | Lands in | Status |
|---|---|---|---|
| Introduction | What does not change (test planning, risk-based prioritisation, boundary analysis; 50 to 70% of the effort is conventional); the four properties (non-determinism, no expected result, behaviour by description, quality changes without a code change); the mindset-shift table | `principles.md` | ⬜ |
| Introduction | The new paradigm infographic; Do's and Don'ts (the source of R5) | `principles.md`, `SKILL.md` rules | ⬜ |
| Introduction | Compulsory reading (Husain on evals, Willison on the lethal trifecta, OWASP LLM Top 10) and the reading lists for evals, LLM-as-judge, security, tracing | `principles.md` (links) | ⬜ |
| 1 Key terminology | How a model works for a tester: LLM (no state between calls), token, context window (a hard limit with a soft failure), temperature (evaluate at production temperature), prompt and system prompt as the specification | `principles.md` ("how these systems work") | ⬜ |
| 1 Key terminology | Tool calling (the description is part of the specification; every tool is an attack and defect surface), RAG (two failure points tested separately), agent, trajectory, multi-agent (routing errors, handoff losses, loops, responsibility gaps, state conflicts), handoff, orchestration and state graph (derive tests from the graph) | `principles.md`, `trajectory-and-multi-turn.md` | ⬜ |
| 1 Key terminology | Hallucination (intrinsic, extrinsic), grounding, prompt injection (direct, indirect), jailbreak, guardrail (model-level vs system-level), excessive agency | `principles.md`, `security-testing.md` | ⬜ |
| 1 Key terminology | Evaluation terms: eval, golden dataset, ground truth, LLM-as-judge, drift; the glossary | `principles.md` (glossary) | ⬜ |
| 2 The oracle problem | The four oracle types in order of preference; the 40% rule (a judgement that is an assertion in disguise); the decision procedure and its periodic re-run | `oracles.md` | ⬜ |
| 2 The oracle problem | Metamorphic testing and its five relations (paraphrase, irrelevant addition, order, monotonicity, negation sensitivity) | `oracles.md` | ⬜ |
| 3 Golden dataset | Anatomy of a case (input and fixtures; available data in tiers; ground truth with accepted equivalents, rejected answers, acceptable and unacceptable actions, required citations; grading rules; metadata) | `dataset-and-labelling.md` | ⬜ |
| 3 Golden dataset | Where cases come from (50, 30, 20); coverage dimensions; the three case classes nobody builds; how many cases; cases per slice | `dataset-and-labelling.md` | ⬜ |
| 3 Golden dataset | The three-way split and the generalisation gap; the ownership rule; labelling quality (double-label, Cohen's kappa, disagreement as a specification finding, the labelling guide) | `dataset-and-labelling.md` | ⬜ |
| 3 Golden dataset | Governance (version control, pinned per release, refresh, archive, growth from failures, review cycle) and the anti-patterns table | `dataset-and-labelling.md`, `failure-modes.md` | ⬜ |
| 4 Quality dimensions | The nine: relevance, groundedness, correctness, completeness, calibration (ECE and the decision-relevant bucket), instruction following (test at depth), coherence over a conversation, safety (prohibited-operation list owned by someone with authority), efficiency (bounded execution, loop induction, cost per successful task) | `quality-dimensions.md` | ⬜ |
| 5 Security testing | The core problem; direct injection payload classes; indirect injection (every content source is an input channel; placements and goals); the method; jailbreaking | `security-testing.md` | ⬜ |
| 5 Security testing | Excessive agency and tool abuse; data leakage (session, tenant, artefact isolation, system prompt extraction, log leakage, retention and purge); denial of wallet; red teaming; interpreting results honestly (99% means one in a hundred) | `security-testing.md` | ⬜ |
| 6 Building the pipeline | The architecture (golden dataset, trace store, runner, assertions, judge, scorecard); the tool landscape and the default recommendation (translated to this stack) | `judge-and-pipeline.md`, `eval4j-loom-mapping.md` | ⬜ |
| 6 Building the pipeline | Repository layout and environment (separate judge key; pin everything); the configuration snapshot and fingerprint; prompt versioning; trace capture and event kinds | `judge-and-pipeline.md`, `eval4j-loom-mapping.md` | ⬜ |
| 6 Building the pipeline | The environment simulator and its faults; fixture recording and refresh | `judge-and-pipeline.md` | ⬜ |
| 6 Building the pipeline | Layer 1 hard assertions (zero-tolerance vs rate-based, kept separate); Layer 2 outcome matching (structured output preferred; embedding; LLM comparison last; the unmatched verdict and the 10% rule; relevance from the trace) | `judge-and-pipeline.md` | ⬜ |
| 6 Building the pipeline | Layer 3 the judge: rubric as a versioned file, the judge prompt (five deliberate elements), calling it (temperature 0, one dimension per call), validating it (inter-human agreement first, then judge-human), the reading table, the ongoing audit, the five biases | `judge-and-pipeline.md` | ⬜ |
| 6 Building the pipeline | Running the matrix: the run protocol table (per commit, nightly, release gate, weekly drift), the runner (parallelism and rate limits, an exception is a result, the budget circuit breaker), pytest integration patterns | `judge-and-pipeline.md` | ⬜ |
| 6 Building the pipeline | Avoiding flaky CI (a floor plus a regression band); cost control levers; track eval spend as its own metric | `judge-and-pipeline.md` | ⬜ |
| 6 Building the pipeline | Reading results: aggregation, what to report, pass^N next to pass@1, the generalisation gap, slicing, is this movement real (the five steps), resolution, per-case stability, baseline comparison and re-baselining governance | `judge-and-pipeline.md` | ⬜ |
| 6.1 Multi-turn and agentic | Outcome is not enough (the two-run table); the four trajectory properties (legality, efficiency, error handling as the highest-yield check, appropriateness); turn-level vs session-level metrics | `trajectory-and-multi-turn.md` | ⬜ |
| 6.1 Multi-turn and agentic | Building multi-turn cases (scripted, simulated user, recorded) and validating the simulator; multi-agent tests (routing confusion matrix, context loss at handoff, thrashing, responsibility gap, state conflict, privilege leakage); context accumulation and eviction tests | `trajectory-and-multi-turn.md` | ⬜ |
| 7 Appendix | Command cheatsheet (translated to `weave`); troubleshooting table; tool reference | `eval4j-loom-mapping.md`, `failure-modes.md` | ⬜ |
| 7 Appendix | Record formats: golden dataset record, rubric dimension, defect template, **test plan skeleton for an AI feature** (the ten sections) | `design-record.md` | ⬜ |
| 7 Appendix | Further reading (OWASP, NIST AI RMF, provider documentation, inter-rater literature) | `principles.md` (links) | ⬜ |

## 3. Quality gates

- [ ] The extended skill tests are green in the default build; `mvn verify` green on `ai-agent4j-loom` (where the skill tests live).
- [ ] `make skill-package` builds a package containing the skill; the package's check passes from an empty folder.
- [ ] `SecurityDocTest` and the docs tests are green; the repository's secret scan finds nothing in the skill.
- [ ] No new dependency.
- [ ] A reviewer other than the author has read every reference page against the guide.

## 4. Manual checks (need the owner and a fresh coding-agent session; no model spend beyond the session itself)

| # | Check | Pass |
|---|---|---|
| M1 | The **dry run** of [TEST_STRATEGY §5](TEST_STRATEGY.md) on the `article-flow`-shaped project: the agent asks, shows the checklist, asks for the three case classes, marks drafts, states the gaps and workarounds, refuses to let an unvalidated judge gate, and ends with a valid `EVAL_DESIGN.md`. The transcript is kept | ⬜ |
| M2 | Review mode on the `WeakEvalProject`: every planted anti-pattern found and ranked, unverifiable ones marked | ⬜ |
| M3 | The owner reads the **coverage matrix** (§2.2) against the guide, row by row, and marks each ✅ or records the decision to decline | ⬜ |
| M4 | The owner reads the mapping page and checks three random rows by running the command | ⬜ |
| M5 | A colleague who did not write the skill uses it to design the evaluation for a different small workflow; note where they were confused | ⬜ |

## 5. Documentation owed

The skill itself; `docs/PUBLISHING_SKILL.md`; the root README skills table; `llms.txt`; the workflow skill's description and evaluation stages (cross-links); `docs/guide/` pointers; the `weave guide` page; the extension's agent set-up text; `docs/specs/README.md`. Stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] §0 recorded (P1 to P5), including the owner's answers on D11 and D13 and the licence
- [ ] Every row in §2.1 ✅; every row in §2.2 ✅ or declined with a reason; every A-criterion has evidence
- [ ] M1 to M5 done; the dry-run transcript and what it would have prevented are in §7
- [ ] DESIGN §8 questions answered; decisions D11 and D13 recorded
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

*(empty until 23a)*
