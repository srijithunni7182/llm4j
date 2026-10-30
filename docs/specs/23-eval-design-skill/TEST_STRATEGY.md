# Phase 23 test strategy: the evaluation design skill

How each requirement of [DESIGN.md](DESIGN.md) is tested. A skill is mostly prose, so most of what can go wrong is not a failing function but a **wrong or drifting claim**, a **broken link**, a **sample that does not parse**, or a **process a coding agent cannot follow**. The strategy tests what can be tested by machine and
puts the rest in front of a person with a written protocol. The existing skill tests (`SkillKitTest`, `HarnessSkillTest`, `AgentInstructionsTest`) are the pattern; the rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md) apply (hermetic; nothing here needs a model).

## 1. Principle: a skill that promises must be a skill that is true

Three kinds of claim live in this skill, and each has a different check:

| Claim | How it is checked |
|---|---|
| "This command or option exists" | run against the real CLI: `weave <command> --help` and `weave man <command>` must contain every name the mapping page uses |
| "This sample works" | parsed or checked with the real tool: dataset YAML by `weave eval --check`, Loom by `weave check --no-env`, rubric files by the loader |
| "This is how to think about it" (the method) | not machine-checkable; covered by the **coverage matrix** to the owner's guide ([VERIFICATION §2.2](VERIFICATION.md)) and the manual protocol (§5) |

## 2. Test levels

| Level | What it covers |
|---|---|
| **Structure** | Front matter valid; `name` equals the folder; the description names the triggering questions of R1; every file in the design (the list in DESIGN §3) exists; every `references/…` link in `SKILL.md` resolves; no link to a repository path; no file outside the skill folder is referenced; the size of `SKILL.md` stays within the budget (so it is read whole) |
| **Install** | `weave guide --install-skill` installs four skills including this one, with all references, into a temporary project for each supported agent; the manifest lists them; `make skill-package` produces a package that contains them and passes the package's own check (complete, no repository paths) |
| **Mapping truth** | `EvalMappingNamesRealCommandsTest`: parse the mapping page for `weave …` invocations, options and dataset fields, and check each against the CLI's help and the dataset loader's accepted keys; a gap row must name a register id that exists in `FIELD_FINDINGS.md`; a row with no gap must have a test or command that proves it |
| **Samples** | Every YAML block in the references that claims to be a dataset case, a protocol file or a rubric is extracted and checked with the real loader; every Loom block with `weave check --no-env`; the worked `EVAL_DESIGN.md` example is parsed by a tiny structural validator (all ten sections present, a reason for every excluded dimension, an owner for every role) |
| **Record template** | `design-record.md`'s template and the worked example agree on headings and fields; the stage table of DESIGN §4 and the template's sections are the same set (a drift guard) |
| **Cross-links** | The workflow skill, the harness skill and the deployer skill link to this one where DESIGN §4 R8 says they do; the guide chapters point to it; `llms.txt` and the README list it |
| **Secrets and safety** | No real credential-looking string in any file (the repository's existing scan); no instruction to put a key in `.env.example`; the corpus guidance says sandboxed or fake tools |
| **Attribution** | Each distilled page contains the credit line and the link to external works named in R10 |

## 3. Fixtures

- **`SkillFolder`**: the real folder under `.claude/skills/llm4j-eval-design/`.
- **`CliHelpIndex`**: a map of every command, option and dataset key the real CLI and loader accept (built once per test run from `weave man` and the dataset schema).
- **`WorkedExample`**: the `article-flow`-shaped design record, doubling as a fixture for the structural validator and for the review-mode test.
- **`WeakEvalProject`**: a small project folder that commits **each anti-pattern on purpose** (a dataset with no unanswerable cases and no metadata, written by the same model family, a threshold equal to current performance, an unvalidated judge gating, a mock result quoted as quality), used to test review mode (§4, R7).

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `SkillStructureTest`, `SkillDescriptionTriggersTest` (the trigger phrases are present), `NoRepositoryPathsTest` |
| **R2** | `StageTableMatchesTemplateTest` (DESIGN's ten stages equal the template's sections), `EveryStageHasQuestionsAndARecordFieldTest` (a structural check on the pages), `ChecklistAfterEveryStageIsStatedTest` |
| **R3** | `SkillForbidsInventingGroundTruthTest` (the rule is present in `SKILL.md` and in `dataset-and-labelling.md`), `ProposedCasesAreMarkedInTheExampleTest`; a manual transcript review (§5) for behaviour |
| **R4** | `EvalMappingNamesRealCommandsTest`, `GapRowsNameARegisterIdTest`, `NoPromiseWithoutAProofTest` |
| **R5** | `RulesPresentTest` (the nine rules appear verbatim in `SKILL.md`), `ControlsAreOneHundredPercentWordingTest` |
| **R6** | `ReadingResultsPageHasTheFiveStepTestTest`, `ResolutionTableMatchesTheArithmeticTest` (the table's run counts agree with the standard formula within rounding) |
| **R7** | `ReviewModeFindsEveryAntiPatternTest` (a transcript-free check: the review checklist in the skill lists each anti-pattern and the evidence file that settles it); `ReviewModeSaysNotVerifiedTest` (the wording rule); manual run on `WeakEvalProject` (§5) |
| **R8** | `HandOversArePresentTest` (links in all four skills), `WeaveNextMentionsEvalDesignTest` (with [Phase 20](../20-guided-path-and-skills/DESIGN.md)) |
| **R9** | `InstallSkillInstallsFourTest`, `SkillPackageContainsEvalDesignTest`, `PublishingSkillDocMentionsItTest`, `ReadmeAndLlmsListItTest` |
| **R10** | `AttributionPresentTest` |
| **Samples and record** | `SamplesParseTest`, `WorkedExampleIsValidTest`, `TemplateAndExampleAgreeTest` |

## 5. The part a machine cannot check: a protocol with a person

A test cannot tell whether a coding agent following the skill behaves well. So the verification plan includes a **scripted dry run** with a fresh coding agent session and a **human playing the developer**, on a fixed scenario, recorded and reviewed against a checklist:

1. Start a clean session in the `article-flow`-shaped project, with only the skills installed. Ask: "How should I test this workflow?"
2. Check that the agent **asks** before it writes (system, worst case, who the expert is), shows the **stage checklist** after each stage, asks for the three missing case classes **instead of inventing them**, marks drafted cases for review, says what the stack cannot do and gives the workaround, refuses to let an unvalidated judge gate, and ends with a complete `EVAL_DESIGN.md` that passes the structural validator.
3. Ask: "Is my evaluation any good?" on the `WeakEvalProject`. Check that it finds each planted anti-pattern, ranks them, and says **not verified** for what it cannot read.
4. Compare the result with what the field session did. Record which of F26, F29, F30, F31, F34, F36 the skill would have prevented.

The transcript, the checklist and the outcome are kept in `VERIFICATION.md` §7. It is repeated whenever the skill's process changes.

## 6. Mutation-style checks for prose

Guards in prose can be removed too. For each of the nine rules (R5) and the three "ask, do not invent" rules (R3), a test removes the sentence from a copy of the page and confirms the presence test fails. The aim is that a later edit cannot quietly drop a rule.

## 7. Deliberately not tested

- That the guide's numeric targets are right for any product. They are starting points; the skill says so, and a test checks that it says so.
- That a model follows the skill every time. The protocol in §5 samples it; it cannot prove it.
- Third-party tools named as "tools you may meet" (they are outside the stack and outside the checks).
