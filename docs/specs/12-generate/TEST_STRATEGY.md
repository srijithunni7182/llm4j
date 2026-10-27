# Phase 12 test strategy: generate from a description

Status: **draft for review**. Requirements `R1`…`R35` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **The model is the least testable part, so everything around it is made testable.** The pipeline (interview, plan checking, writing, gates, repair loop, patching) is deterministic code; the model is behind `LLMClient` and replaced by a **transcript replayer** (R31) in every build test. No build test calls a live model.
- **Gates are tested with deliberately bad drafts.** For each gate, a recorded transcript produces a draft that fails exactly that gate; the test asserts the feedback text sent back, the next attempt, and the final outcome. A good draft is a regression fixture for the happy path.
- **The loop must be proven to terminate and not to regress.** Adversarial transcripts (a model that oscillates, repeats the same fix, makes things worse, returns garbage, returns nothing, exceeds the spend cap) are first-class tests.
- **Safety first.** Prompt-injection descriptions and context files are a dedicated test group; "nothing unlisted in the plan reaches the script" is tested by construction.
- **Live evaluation is separate and manual.** `scripts/bench-generate.sh` runs the 30-description benchmark against a real model, records transcripts and writes the numbers into VERIFICATION §7. It is not part of the build.
- **Old behaviour is pinned.** `weave init`, `weave check`, `weave next` and the templates are unchanged (tests compare outputs).

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Interview** | At most five questions; categories fixed; defaults applied with `--no-ask`; answers recorded in the plan | `InterviewTest`, `InterviewCategoriesTest` | R1, R2 |
| **Plan** | Plan JSON/Markdown round trip; every check in R6 has a failing plan; `--from-plan` after an edit; plan hash stable | `PlanRoundTripTest`, `PlanCheckerTest`, `PlanHashTest` | R3 to R6 |
| **Writer** | Layouts (maven/flat), files, headers without model names, temp dir then move, refuses non-empty target, never overwrites without confirmation | `WriterLayoutTest`, `WriterTargetTest`, `HeaderNoModelNameTest` | R7 to R9, R28, R30 |
| **Defaults baked in** | A recorded good draft contains every safety default (R10); a draft missing one fails G2 or G5 with the right message | `SafetyDefaultsTest`, `MissingBudgetFailsTest` | R10 |
| **Prompt assembly** | The model input contains the language reference, the short rule catalog, examples chosen by plan shape, the plan; contains no developer files unless `--context`; contains no keys | `PromptAssemblyTest`, `NoSecretsInPromptTest`, `ExamplePickerTest` | R11, R26 |
| **Gates** | Each of G1 to G5 pass/fail with feedback golden files; G2 uses SAFE fixes by tool not model; G3 uses only scripted collaborators (no live call); G5 plan fidelity both directions | `GateG1Test` … `GateG5Test`, `SafeFixByToolTest`, `NoLiveCallsTest` | R12, R15, R17 |
| **Loop** | Attempt caps per gate and overall; monotonicity (a worse attempt is discarded); spend cap stops with draft kept; oscillation, garbage, empty, repeated-identical replies | `RepairLoopTest`, `MonotoneLoopTest`, `SpendCapTest`, `OscillationTest`, `GarbageReplyTest` | R12 to R14, R16 |
| **Summary / exit codes** | Card contents, the "not checked" statement, three next steps, exit codes 0..4, JSON output | `SummaryTest`, `ExitCodesTest` | R16, R18, R19 |
| **Edit mode** | Patch sets only touch named files; unified diff; delta (findings, cost); existing tests still pass or are listed; `--accept-worse`; ambiguous request asks one question; never touches `.env`/ignored secret files or outside the folder | `PatchSetTest`, `EditDeltaTest`, `EditSafetyTest`, `AmbiguousEditTest` | R20 to R24 |
| **Injection** | Descriptions and context files containing instructions to add a tool/URL/effect: the plan shows it or the gate fails; no unlisted tool/effect passes G5; secret-shaped strings stopped | `DescriptionInjectionTest`, `ContextFileInjectionTest`, `SecretShapedStringTest` | R25, R27 |
| **Network / process** | Only the configured provider endpoint is contacted; no URL from the description is fetched; no process is spawned | `GenerateNetworkTest` | R29 |
| **Gate-only** | `--gate-only` on every repo example and sample; same gate code | `GateOnlyExamplesTest` | R32 |
| **Transcript** | Record then replay yields identical files; transcript has no keys; replay fails clearly on a mismatch | `TranscriptRoundTripTest`, `TranscriptNoKeysTest` | R31 |
| **Editor** | Commands, plan view, progress events from `loom/generate`, result opens the Review panel (fake engine) | `generateCommands.test.ts`, `ServeGenerateTest` | R33 |
| **Skill** | Skill text matches the CLI flags (drift test) | `SkillDriftTest` | R34 |
| **Benchmark (replay)** | The 30 descriptions with recorded transcripts: structural expectations hold; deterministic | `GenerateBenchmarkTest` | R35 |

## 3. Fixtures

- `src/test/resources/generate/transcripts/` recorded model exchanges: `good-*.jsonl`, `fails-g1-*.jsonl` … `fails-g5-*.jsonl`, `oscillates.jsonl`, `garbage.jsonl`, `injection-*.jsonl`.
- `src/test/resources/generate/descriptions/` the 30 benchmark descriptions with `expect.json` (structural properties).
- `src/test/resources/generate/projects/` baseline projects for edit mode.

## 4. Fault injection

Model returns text with no files; returns the same files each attempt; returns a file that escapes the target (`../`); returns a 5 MB file; returns a prompt containing a key-shaped string; times out; rate-limits mid-loop; the spend cap hit mid-attempt; disk full on write; the target directory appears between plan and write; the plan edited to be invalid; the temp directory removed during the loop.

## 5. What is deliberately not tested in the build

- The quality of a live model's plans and prose (benchmark, manual, recorded numbers).
- Whether the generated prompts make a real agent behave well (`weave eval`).
- Terminal rendering of interactive prompts beyond their text.
