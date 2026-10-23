# Phase 12 verification plan: generate from a description

Status: **12a done**. Run top to bottom at the end of sub-phase 12e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | From one sentence, the tool produces a project (script, prompts, tests, README) and shows a plan first that I can edit | `PlanRoundTripTest`, `WriterLayoutTest`, `GenerateBenchmarkTest` |
| A2 | Nothing is shown as done until check, review (no HIGH after safe fixes), generated tests and coverage pass; otherwise I am told which gate failed and why | `GateG1Test`…`GateG5Test`, `ExitCodesTest` |
| A3 | The generated workflow carries the safe defaults: budget, bounded loops, retries/timeouts on tool steps, approvals on effects, typed branches, no secrets in text | `SafetyDefaultsTest`, `GenerateBenchmarkTest` |
| A4 | The loop terminates, never gets worse, and respects the spend cap | `RepairLoopTest`, `MonotoneLoopTest`, `SpendCapTest`, `OscillationTest` |
| A5 | Generation makes no real call to the generated agents and sends nothing | `NoLiveCallsTest`, `GenerateNetworkTest` |
| A6 | Instructions hidden in a description or context file cannot add a tool or effect that the plan does not show | `DescriptionInjectionTest`, `ContextFileInjectionTest` |
| A7 | Edit mode produces a reviewable diff with the review and cost delta, applies nothing without confirmation, and flags a change that makes things worse | `PatchSetTest`, `EditDeltaTest`, `EditSafetyTest` |
| A8 | The same pipeline is a quality bar for hand-written projects (`--gate-only`) | `GateOnlyExamplesTest` |
| A9 | It works from the editor and a coding agent follows the documented flow | `generateCommands.test.ts`, `SkillDriftTest` |
| A10 | The pipeline is deterministic and testable offline from recorded transcripts | `TranscriptRoundTripTest`, `GenerateBenchmarkTest` |
| A11 | Live quality on the 30-description benchmark meets the targets in DESIGN §9 (manual, recorded in §7) | `scripts/bench-generate.sh` results |
| A12 | Existing `init`, `check`, `next` behave as before | existing tests, unmodified |
| A13 | Documented | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `InterviewTest` | ⬜ 12b |
| R2 | `InterviewCategoriesTest` | ⬜ 12b |
| R3 | `PlanTest` (JSON round trip, fingerprint, Markdown) | ✅ 12a |
| R4 | `GenerateCommandTest` (plan only, edited plan read back, plan the checker rejects is still written) | ✅ 12a |
| R5 | `InteractivePlanTest` | ⬜ 12b |
| R6 | `PlanTest` (role, allowed model, built-in kind or stub, reach, effect approval, budget, tests, names) | ✅ 12a |
| R7 | `WriterTest` (temporary project, removed on close, target untouched until commit) | ✅ 12a |
| R8 | `WriterTest` (flat and Maven layouts, pom, .gitignore) | ✅ 12a |
| R9 | `WriterTest`, `GatesTest` (prompt files by stem) | 🟨 12a (the prompts' header comment is for 12b's prompt assembly) |
| R10 | `SafetyDefaultsTest`, `MissingBudgetFailsTest` | ⬜ 12b |
| R11 | `PromptAssemblyTest`, `ExamplePickerTest` | ⬜ 12b |
| R12 | `RepairLoopTest` | ⬜ 12b |
| R13 | `SpendCapTest` | ⬜ 12b |
| R14 | `MonotoneLoopTest`, `OscillationTest` | ⬜ 12b |
| R15 | `SafeFixByToolTest` | ⬜ 12b |
| R16 | `ExitCodesTest`, `SummaryTest` | ⬜ 12b |
| R17 | `NoLiveCallsTest` | ⬜ 12c |
| R18 | `SummaryTest` | ⬜ 12b |
| R19 | `SummaryTest` (not-checked statement) | ⬜ 12b |
| R20 | `PatchSetTest` | ⬜ 12d |
| R21 | `EditDeltaTest` | ⬜ 12d |
| R22 | `EditConfirmTest`, `AcceptWorseTest` | ⬜ 12d |
| R23 | `EditSafetyTest` | ⬜ 12d |
| R24 | `AmbiguousEditTest` | ⬜ 12d |
| R25 | `DescriptionInjectionTest`, `ContextFileInjectionTest`, `PlanFidelityTest` | ⬜ 12b |
| R26 | `TranscriptTest` (no request text, no key-shaped reply) | 🟨 12a (the prompt and plan being key-free is 12b) |
| R27 | `WriterTest.aFileThatIsTooBigOrHoldsAKeyIsRefused...` | 🟨 12a (files; prompts of the generator itself in 12b) |
| R28 | `WriterTest.everyFileSaysWhatMadeItAndFromWhichPlanAndNeverWhichModel` | ✅ 12a |
| R29 | `GenerateNetworkTest` | ⬜ 12b |
| R30 | `WriterTest` (empty or absent target; nothing replaced) | ✅ 12a |
| R31 | `TranscriptTest`, `GenerateCommandTest.recordingThenReplaying...` | ✅ 12a |
| R32 | `GateOnlyExamplesTest` (samples and every starter template), `GenerateCommandTest` | ✅ 12a (G1 and G5; G2 to G4 join as they are built) |
| R33 | `generateCommands.test.ts`, `ServeGenerateTest` | ⬜ 12e |
| R34 | `SkillDriftTest` | ⬜ 12e |
| R35 | `GenerateBenchmarkTest` (replay), `bench-generate.sh` (live) | ⬜ 12e |

## 3. Quality gates

- All existing tests pass unmodified.
- Line coverage ≥ 90 % in `generate/` excluding the thin CLI class; mutation target ≥ 70 % on `RepairLoop`, `Gates`, `PlanChecker`, `Patch`.
- No build test performs network I/O (a test-wide guard).
- `scripts/verify-generate.sh` (new): replay benchmark, gate-only over all examples, injection suite.
- No new runtime dependency.

## 4. Manual checks

1. Generate five workflows you did not write examples for; read each plan and each summary. Note anything surprising that a gate did not catch (these become new rules or gates).
2. Run the live benchmark with two different models; record numbers.
3. Ask for an edit that should make things worse; confirm the warning and the need for `--accept-worse`.
4. Try a malicious description; confirm the plan shows it or the gate stops it.
5. Use the editor flow end to end with only the keyboard.

## 5. Documentation owed

Wiki "Generating a workflow"; LOOM_GUIDE section; the workflow skill (preferred flow and gate meaning); `LOOM_PROMPT.md` references the short rule catalog; `weave init` docs point to `generate`; `llms.txt`; the extension README.

## 6. Exit checklist

- [ ] Every row in §2 ✅ and every A-criterion has evidence
- [ ] Live benchmark results in §7 meet the DESIGN §9 targets (or the targets are revised with reasons)
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §11 questions answered and recorded

## 7. Results

*(per sub-phase: replay benchmark table; live benchmark: first-attempt pass, final pass, attempts, cost, flagged outputs)*

### 12a (2026-10-23)

- Built: `generate/` with `Plan` (JSON, Markdown, fingerprint), `PlanChecker` (R6), `Planner` (asks the model for a plan; the description is given as data between markers), `Transcript` (record without request text and without key-shaped replies; replay that stops clearly when what is asked has changed), `Writer`/`Workspace` (checked paths and sizes, key-shaped text refused, header with generator, date and plan fingerprint and no model name, flat or Maven layout, a temporary project moved only on commit and never over existing files), G1 (`CheckGate`) and G5 (`FidelityGate`: the script is the plan in both directions; an effect the plan does not list, an effect the plan says is approved but the script lets an agent call without `approve:`, a person asked in one and not the other, no budget). `weave generate "..." --plan-only | --from-plan FILE | --gate-only DIR|SCRIPT [--record|--replay] [--format json]`, exit codes 0/1/2/3.
- Found building it: `weave check --no-env` reported a false error for a tool whose URL is read from an unset `env.NAME` (the stand-in is not a URL); it is now dropped like the other stand-in failures (this also fixes it in the editor); the hand-written `digest` sample hit it.
- Tests: Java module 3,1xx; `PlanTest` (14), `WriterTest` (9), `TranscriptTest` (5), `GatesTest` (9), `GenerateCommandTest` (11), `GateOnlyExamplesTest` (7).
- Not in 12a: writing the project with the model (`--from-plan` only validates the plan so far), the interview, the repair loop (12b).
