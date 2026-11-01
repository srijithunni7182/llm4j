# Phase 28 verification plan: evaluation first

Status: **not started** (spec draft; needs phases 24 and 26 for R4). Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **28a** louder (R1 to R3); **28b** the gate (R4); **28c** `--requirements` (R5); **28d** `--changed`, the CI template and the teaching (R6 to R8); **28e** the end-of-program documentation (R9). R1 to R3, R5 and R6 do not depend on phases 24 and 26 and may be built first. Results in §7.
Evidence: E12, E13 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E12 | `weave review` on a starter with its tests folder removed. | no LT01 (the lens is off); `Lens.java` marks testability optional; Phase 9 R33 says why | ⬜ |
| P2 | E13 | `weave guide`. | stage 2 is "Build a golden dataset (optional)" | ⬜ |
| P3 | R1 | `weave review --lens +testability` with `heuristics = off` on a script with no dataset. | no LT06 (it is heuristic) | ⬜ |
| P4 | R2 | `weave run` and `weave deploy` on a script with no dataset. | nothing says that nothing measured the agents | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | The Testability lens is on by default; LT06 is exact, medium, and agrees with `weave eval` | R1 tests, reader agreement |
| A2 | A script with no cases says so on run, deploy, the live page and `weave next`, once, quietly removable | R2 tests |
| A3 | The guide and the skill call the dataset the requirements; skipping stays possible and recorded | R3 tests, §5 |
| A4 | `weave check` fails on an extractor with no case and on an evaluator missing a breaking or a clean case for any rule of any policy it checks; no flag skips it; a scaffold never satisfies it | R4 tests |
| A5 | `weave eval --requirements` renders a page a non-technical reviewer can read, shows the dataset's identity, lists what has no requirement, and records approvals of an exact identity | R5 tests |
| A6 | `weave eval --changed` runs only what changed (and its routes), says what changed, needs a cap, and fails closed when it cannot tell | R6 tests |
| A7 | The CI template runs the three costs safely by default | `CiTemplateTest` |
| A8 | The tutorial and guide teach "the dataset is the requirements", cases for extractors and evaluators, and the three costs; examples load | `TutorialExamplesTest`, `GuideTutorialPageTest`, §5 |
| A9 | The stack-level pages describe the program's constructs; the program is marked done | §5 R9 rows |
| A10 | Every guard is noticed when removed | PIT score not lower; by-hand removals in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `TestabilityLensOnByDefaultTest`, `LensesMinusTestabilityRestoresTest`, `Lt06UsesEvalLookupTest`, `Lt06IsMediumAndNotHeuristicTest`, `Lt06CoveredByWorkflowExpectTest` | ⬜ |
| R2 | the R2 tests of the strategy | ⬜ |
| R3 | `GuideStageTwoTitleTest`, skill text test | ⬜ |
| R4 | the R4 tests of the strategy | ⬜ |
| R5 | the R5 tests of the strategy | ⬜ |
| R6 | the R6 tests of the strategy | ⬜ |
| R7 | `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh` | ⬜ |
| R8 | `CiTemplateTest` | ⬜ |
| R9 | §5 | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `eval4j`, `eval4j-report`, `loom-inspect*` and `loom-fabric` (after installing the Loom jar); coverage gates held, none lowered.
- [ ] PIT: the gate, the fingerprint and `--changed` added to the mutation profiles; scores not lower.
- [ ] Every starter and example that uses an extractor or an evaluator passes `weave check` with real cases (`StartersTest`, `RepositoryScriptsTest`).
- [ ] `bash scripts/make-tutorial-html.sh`; `GuideTutorialPageTest` green.
- [ ] `weave rules docs core`; `weave rules test core` green.
- [ ] `bash scripts/verify-skill-package.sh` green for the workflow guide, eval design and harness design.
- [ ] `make format-check`, `make release-check` green. No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Run `weave eval --requirements` on starter 16 and give the page to someone who is not a developer: can they say what the workflow must and must never do? | ⬜ |
| M2 | Change one word of starter 16's policy, then of one agent's prompt; `weave eval --changed --max-tokens 30000 --mock` lists exactly the nodes expected; then a capped real run of the changed nodes only | ⬜ |
| M3 | Follow the workflow-guide skill from a blank folder to a workflow with an extractor and an evaluator: the gate is explained before it fires, and the cases are written with the user | ⬜ |
| M4 | Read tutorial chapter 10 as a newcomer | ⬜ |

## 5. Documentation owed

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/02` ⬜, `05-test-agents-with-caps` ⬜, `09-go-live` ⬜ · tutorial ch. 1 ⬜, 5 ⬜, 9 ⬜, 10 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · `docs/review/rules/` ⬜ · phase 9 DESIGN R33 note ⬜ · `docs/TESTING_STRATEGY.md` ⬜ · eval4j README ⬜ · command reference pages ⬜ · skills (workflow guide ⬜, eval design ⬜, harness design ⬜) · starters and starters README ⬜ · `API_COMPATIBILITY.md` ⬜ · `llms.txt` ⬜ · release notes ⬜ · **R9, once for the program:** root `README.md` ⬜, `wiki/Home.md` ⬜, `docs/README.md` ⬜, `docs/THE_STACK.md` ⬜, [DISCIPLINE.md](../DISCIPLINE.md) status ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] M1 to M4 done and recorded in §7
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated, including the stack-level pages; roadmap status updated; the program marked done

## 7. Results

*(empty until 28a)*
