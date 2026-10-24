# Phase 14 test strategy: the production loop

Status: **draft for review**. Requirements `R1`…`R42` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Data is the hazard.** This phase reads real run content and writes it into a repository. The safety tests come first: no raw PII, no secret, no unmasked value reaches disk, a report, a log or a Fabric response. They are written before any capture code and run over a *seeded hostile journal* (§3).
- **A captured case must prove itself.** The central property: for every run in the corpus, `capture → weave test` passes against the script version that produced it (R1). It is a property test over scripted-run corpora and generated workflows (reusing phase 13's generator).
- **Read-only is enforced, not hoped for.** `StoreReader` has no write methods; every command is tested with a store hash before and after; instrumented collaborators fail the test on any model/tool/effect.
- **Statistics are tested like code.** The sampler is deterministic per seed, stratification is checked against known populations, and the report's wording never claims more than the sample supports (a test greps for percentages of "production" under non-random samples).
- **The gate is tested as a state machine** (each stage pass/fail/skip, `--require`, ordering, messages).
- **No live model in the build.** Live paths use a fake model with a spend counter; the cap is asserted against it.
- **Old behaviour is pinned.** `weave test`, `weave eval`, `weave replay`, Fabric routes unchanged.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Store access** | Read-only snapshot; tolerates concurrent writers; hash unchanged; both journal backends; missing/pruned journal reported | `StoreReaderTest`, `StoreReaderConcurrentWriteTest`, `RegressReadOnlyTest` | R22, R35, R41 |
| **Masking** | Format-preserving masks; stable placeholders per case; masks run before display and write; masks never change a route (R7) or the capture fails naming the field; secrets of many shapes never captured | `MaskerTest`, `MaskingNoRawOnDiskTest`, `MaskingKeepsRouteTest`, `SecretsNeverCapturedTest`, `SecretShapesTest` | R6 to R8 |
| **Capture** | Inputs/replies/tools/tasks/humans/failures taken from journal; valid test file; passes immediately; suspended run captured to its stop point; idempotent; no overwrite without confirmation; `--list`/`--select`; batch | `CaptureCaseValidTest`, `CapturePassesTest`, `CaptureSuspendedTest`, `CaptureIdempotentTest`, `CaptureNoOverwriteTest`, `CandidatesTest` | R1 to R5, R9 to R14 |
| **Consent / policy** | First-use notice; consent recorded; `capture.properties` allow/deny/max-age enforced | `ConsentTest`, `CapturePolicyTest` | R12 |
| **Approval** | States, who/when/why, cannot approve a failing case unchanged, retire, `--require-approved`, summary counts | `ApprovalStatesTest`, `ApproveFailingRefusedTest`, `RequireApprovedTest` | R3, R15 to R17 |
| **Golden form** | `--as golden` produces a valid eval entry, masked | `CaptureAsGoldenTest` | R5 |
| **Sampler** | Deterministic per seed; random/recent/stratified/failures-first; strata sizes; the report states population, sample, seed; no extrapolation wording | `SamplerTest`, `StratifiedAllocationTest`, `ReportWordingTest` | R20 |
| **Comparator** | Route, result, effects, branches, cost, latency, failure-handler differences; recorded-vs-live separation; needs-a-model counted and never guessed | `RunComparatorTest`, `NeedsModelCountedTest`, `RecordedVsLiveSeparatedTest` | R19, R25, R27 |
| **Unsafe flips** | New effect; removed human step; ladder climb; person → automatic; each marked and listed first; `--fail-on` behaviour | `UnsafeFlipTest`, `FailOnTest` | R18, R30 |
| **Approved always** | Approved cases included whatever the sample; flip fails gate | `ApprovedAlwaysIncludedTest` | R21 |
| **Baseline** | From recorded script hash; git recovery; unrecoverable counted and excluded | `BaselineFromHashTest`, `BaselineGitTest`, `BaselineMissingTest` | R26 |
| **Live / judges** | Fake model: cap honoured, partial report on stop, exit 4; judges require a cap; cost reported measured vs estimated | `RegressLiveCapTest`, `JudgeCostGateTest`, `CostLabelsTest` | R24, R25, R30 |
| **Reports** | text/md/json/junit golden; JSON schema v1; HTML is a single file with no remote resource and no HTML built from run text | `RegressReportGoldenTest`, `RegressJsonSchemaTest`, `RegressHtmlSafetyTest` | R23, R28, R29 |
| **Gate** | Each stage pass/fail/skip, `--require`, git refs, changed-file list, one-command repro text, md/junit/sarif, workflow templates parse as YAML and reference real commands | `GateStagesTest`, `GateSkipVisibleTest`, `GateGitRefsTest`, `GateWorkflowTemplateTest` | R31 to R35 |
| **Speed** | 200 runs recorded under 30 s (tagged) | `RegressSpeedTest` | R27 |
| **Fabric** | Capture/whatif routes, permission, audit entry, unreachable-journal fallback command, no write to a repo, hostile text in responses | `FabricCaptureRouteTest`, `FabricCapturePermissionTest`, `FabricCaptureAuditTest`, `fabric-capture.page.test.js` | R36 to R40 |
| **Architecture** | `loop/` depends on lower packages only (a dependency test) | `LoopDependencyDirectionTest` | R42 |

## 3. Fixtures

- **The hostile journal**: a generated journal seeded with emails, phone numbers, card-like numbers, API keys of ten shapes, bearer tokens, URLs with passwords, names in free text, injection strings, 1 MB text, RTL and control characters. Every output surface is grepped for every seeded value.
- Scripted-run corpora from phase 11/13 with journals for capture round trips.
- A synthetic production store of 2,000 runs with known route distributions for sampler/regress tests.
- Candidate-change pairs with known expected diffs (a condition flipped, a prompt changed, a model changed, an effect added).

## 4. Fault injection

Store modified during regress; a journal deleted after listing; a case file edited by hand to invalid; consent file corrupt; two captures at once; a run whose script hash is not in git; a candidate that does not check; a candidate that loops (the step budget stops it, reported as a regression, not a hang); `--max-cost` hit mid-run; Fabric asked to capture a run on another host; capture of a 50,000-step run (bounded output, clear message).

## 5. What is deliberately not tested

- Whether masking catches *every* form of personal data in the world: the masker is tested against the stack's existing PII definitions and a documented list, and the docs say it is a safeguard, not a guarantee.
- Statistical power of samples beyond correctness of the stated sampling method.
- Real CI systems (templates are parsed and checked for structure; running them is a manual check).
