# Phase 9 test strategy: the review engine

Status: **draft for review**. Requirements `R1`…`R32` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Tests first** for each sub-phase (9a to 9f), failing for the right reason.
- **No model, no network, no clock.** Review is static; every test runs offline in milliseconds. Rules are pure (R20), so most tests build a `ReviewInput` from a string.
- **The corpus is the oracle for quality.** Unit tests prove a rule fires on a minimal example and stays quiet on a near-miss. The corpus (R30) proves precision and recall across realistic scripts and is a CI gate.
- **Old behaviour is pinned.** `weave check` and `weave audit` outputs and exit codes on the existing test scripts are compared byte-for-byte before and after (R3, R7).
- **A fix is a claim.** Every fix is tested three ways: it applies, the result passes `check`, and the finding it addressed is gone and no new HIGH appears.
- **Hostile text is data.** Prompt files and scripts containing markup, huge lines, CRLF, tabs, BOMs and right-to-left text must not break reports or edits.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Model** | Record invariants, JSON round trip, version field, ranking order, de-duplication | `FindingModelTest`, `RankingTest`, `DedupTest` | R1, R2, R5, R22, R23 |
| **Adapters** | Each old shape maps to the new one with nothing lost; old commands unchanged | `AdapterTest`, `AuditUnchangedTest`, `CheckUnchangedTest` | R3, R7 |
| **Rules** | Per rule: fires on the minimal example (with the right line, node id, severity), quiet on a near-miss, quiet when suppressed, severity override works | `Lr03LoopMaxTest`, `Lc01ClassificationModelTest`, … (one class per rule) | R1, R18, R31 |
| **Validity classification** | Every message template in `ScriptValidator` maps to a rule (a reflection-free table test over a list of all templates the validator can emit); `LV00` count is zero | `LvClassificationTest` | R3 |
| **Cost model** | Hand-computed expected numbers for small graphs: sequence, `alt`, bounded loop, unbounded loop, `for each`, `broadcast`, retry, rewind, sub-workflow, budget cap, unpriced model, tool rounds | `CostModelTest`, `CostBudgetCapTest`, `CostUnboundedTest`, `CostUnpricedTest` | R10 to R15 |
| **Cost vs runtime** | The estimator's token count for a prompt equals the runtime's pre-call estimate for the same text (same function) | `EstimatorMatchesRuntimeTest` | R10 |
| **Calibration** | Against recorded runs in the corpus: ratio computed, worst never below actual, typical within 2x on 80 % | `CalibrationTest`, `CorpusCostAccuracyTest` | R16, R17 |
| **Fixes** | Apply, verify, idempotence, atomicity (inject a failing edit: nothing is written), overlapping edits, comments and CRLF preserved, multi-file fix, `--dry-run` diff | `FixApplierTest`, `FixAtomicityTest`, `FixIdempotentTest`, `FixPreservesFormattingTest` | R4, R24 to R29 |
| **Reports** | Text, md, json golden files; SARIF validates against the 2.1.0 schema; fixes map to SARIF `fixes` | `ReportGoldenTest`, `SarifSchemaTest` | R5, R8, R9 |
| **CLI** | Exit codes, `--lens`, `--min-severity`, `--fail-on`, `--rules` errors (R32), `--explain` for every rule, `--assume` | `ReviewCommandTest`, `ExplainEveryRuleTest` | R6, R19, R32 |
| **Corpus** | Precision ≥ 0.9 per default-on rule; recall reported; runtime budget | `ReviewCorpusTest` | R18, R30 |
| **Performance** | Warm review of a 300-line script under 1 s on CI hardware class; 2,000-line script under 3 s | `ReviewPerformanceTest` (tagged, not on every build) | goals |
| **Plugin** | A `ServiceLoader` rule in a test jar is loaded only with `--rules-jar`, attributed in the report; a throwing rule becomes a note, not a crash | `RulePluginTest`, `ThrowingRuleTest` | R21 |
| **Safety** | Hostile content in scripts/prompts does not break text/md/json/sarif; no network or process is touched (a security-manager style test) | `ReviewHostileInputTest`, `ReviewIsStaticTest` | goals |

## 3. Fixtures

- `src/test/resources/review/rules/<RULE>/{fires.loom, near-miss.loom, expected.json}` per rule: the smallest script that fires, one that must not.
- `src/test/resources/review/corpus/` (R30): repo examples/samples, flawed scripts with `// expect:` labels, 8+ recorded runs with usage for calibration.
- `src/test/resources/review/golden/` report files, regenerated only with an explicit flag and reviewed in the diff.

## 4. Fault injection

- A rule that throws, loops (time-boxed by the engine at 2 s), or returns a `Fix` whose edits overlap or fall outside the file.
- A `review.properties` with unknown keys, a suppression without a reason, a non-numeric assumption.
- A price file with a missing model, a negative price, a malformed line.
- A script that imports itself, a missing import, a prompt file that is a directory.
- A fix applied to a file changed on disk since it was read (must refuse and say so).

## 5. What is deliberately not tested

- The *wisdom* of a cost suggestion ("use a smaller model") against a live model: that is `weave eval`'s question. Review only tests that the suggestion fires on the labelled shape.
- Exact dollar figures against a provider's invoice. Tests pin the arithmetic against the price table, and calibration pins the ratio against recorded usage.
- Rendering of the report in a terminal emulator.
