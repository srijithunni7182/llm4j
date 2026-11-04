# Phase 27 verification plan: approval at the boundary

Status: **27a to 27c done (2026-11-03, 2026-11-04); 27d and 27e not started**. Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **27a** every tool has an effect (R0; phase 26 waits for it); **27b** `use` (R1) and `approve` on `use` and `run` (R2); **27c** the review changes and the lens default (R3 to R7, R9), and LR11 with its teaching (R10); **27e** dynamic routing within limits (R11); **27d** starter 07, the guide, the skills and the tutorial (R8). Results in §7.
Evidence: E14 to E19 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E14 | `weave review --explain LA02`. | the only advice is the agent's `approve:` list | ⬜ |
| P2 | E15 | An agent with `approve: [Send]`, `max_iterations: 20`, in a loop of 2; `weave review --lens +oversight`. | no LH03, although up to 40 approvals are possible | ⬜ |
| P3 | E16 | `weave review` with no lens option on any script with a `human_prompt` compared to `"yes"`. | no LH04 (the lens is off) | ⬜ |
| P4 | E17 | Try `use Notify(text = "x")` and `call Notify(...)` in a script. | neither calls a tool; starter 07 asks twice | ✅ 2026-11-04, build 6.2.0 before 27b: `use Notify(...)` was a parse error ("Expected statement"); starter 07 asks through `human_prompt` and again through `approve:` |
| P5 | E18 | Search the language for a script-level approval on `run`. | only `requiresApproval` in Java | ✅ read from `TaskRunner` before 27b: the only approval of a `run` was `task.requiresApproval(args)` |
| P7 | E19 | `weave audit --format json` on a script with an `http` GET tool, an `http` POST tool used for search, an `openapi` tool whose operations are all GETs, an MCP tool and a `class` tool. | effect decided out of sight; the POST search counts as changing with no way to say otherwise; the OpenAPI tool and the MCP tool count as changing everything | ⬜ |
| P6 | R6 | Inventory how a project marks a workflow as scheduled, triggered or run by the daemon. | the sources that settle open question 2 | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A0 | Every tool has an effect: declared by the developer and authoritative, per operation for OpenAPI and MCP, MCP hints used, otherwise assumed and shown; one resolver for the audit, runtime and review | the R0 tests, P7 re-run |
| A1 | `use Tool(...)` calls a tool with no model, through exactly the same controls, journal and effect protocol as an agent's call | `UseToolCallParityTest`, `UseNetPolicyRefusalsTest`, `UseEffectProtocolNoRepeatAfterCrashTest` |
| A2 | `approve` on `use` and `run` asks once, shows the exact arguments with secrets named and PII masked, never runs on a no or a timeout, takes `on_declined` on a no when the step has one, and never asks twice after a resume | R2 tests |
| A3 | LA02 offers the boundary first; LH02 covers steps; LH03 counts every question and names its sources | R3 to R5 fixtures |
| A4 | LH05 fires only for an approval mid-loop on an outward or changing tool in an unattended workflow | LH05 fixtures |
| A5 | The Human oversight lens is on by default and can be turned off | `OversightLensOnByDefaultTest`, `LensesMinusOversightRestoresTest` |
| A6 | Starter 07 asks once, at the edge, with no model carrying out the send; a no keeps the draft and a failed send says to check the channel; it passes `weave check` (no `retry` on the webhook); its tests and diagram pass | `StartersTest` |
| A7 | The guide, skills and tutorial teach the three shapes and keep `approve:` for interactive assistants; earned autonomy is presented as optional | §5 ticked, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh` |
| A8 | Every existing script parses and runs as before | `RepositoryScriptsTest`, existing tests unmodified |
| A9 | Every guard is noticed when removed | PIT score not lower; by-hand removals in §7 |
| A10 | Findings are grouped, new ones shown first, a baseline suggested once after the upgrade; LH01 to LH05 meet the precision bar and the noise gate; LH05 and undeclared effects offer fixes; the simple path stays simple | the R9 and D34 tests, `SimplePathStaysSimpleTest` |
| A11 | LR11 warns on an agent's unvalidated prose driving a step that changes something, stays quiet when any validating step stands between, offers its two fixes, and meets the precision bar and the noise gate; the guide, skills and tutorial teach guarding a hand-off | the R10 tests, §5 |
| A12 | A model can route only to agents in an allow-list (inline or enum), cannot overwrite a variable with a dynamic name, and the audit counts every agent a route can reach (LA22); an unbounded route reaching a changing tool or private data fails `weave check`; starter 06 and the examples use allow-lists and the map form | the R11 tests |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R0 | the R0 tests of the strategy | ✅ (hints at connect time and the editor fix: see §7) |
| R1 | `UseParsesTest`, `UseKeywordIsContextualTest`, `UseArgumentsCheckedAgainstToolParametersTest`, `UseToolCallParityTest`, `UseNetPolicyRefusalsTest`, `UseEffectProtocolNoRepeatAfterCrashTest`, `UseRetryRefusedOnNonIdempotentChangingToolTest`, `UseScriptedInWeaveTestTest`, `UseMockedInEvalMockTest`, `UseGraphNodeTest`, `GraphRenderKindsInSyncTest`, `UsesToolAssertionTest`, `UseIsASinkTest` (with phase 26) | ⬜ |
| R2 | the R2 tests of the strategy | ⬜ |
| R3 | LA02 rule-page golden text and fixture | ⬜ |
| R4 | LH03 fixtures | ⬜ |
| R5 | LH02 fixtures | ⬜ |
| R6 | LH05 fixtures | ⬜ |
| R7 | `OversightLensOnByDefaultTest`, `LensesMinusOversightRestoresTest` | ⬜ |
| R8 | `StartersTest`, `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh`, CTK `use_tool`, `approve_step` | ⬜ |
| R9 | the R9 tests of the strategy | ⬜ |
| R10 | the R10 tests of the strategy | ⬜ |
| R11 | the R11 tests of the strategy | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `ai-agent4j-tools` (its `NetPolicy` and `Redactor` gates unchanged), `eval4j`, `loom-inspect*` and `loom-fabric` (after installing the Loom jar).
- [ ] PIT: the Loom `mutation` profile with `UseRunner`, approval on a step and LH03's counting; score not lower.
- [ ] `RepositoryScriptsTest` green; CTK scenarios added.
- [ ] Loom syntax everywhere it belongs (guide, prompt, `docs/guide/06`, tutorial, starters, workflow-guide skill, VS Code grammar and snippets, CTK).
- [ ] `bash scripts/sync-graph-render.sh` and `bash scripts/check-graph-render-sync.sh` (`use` kind).
- [ ] `bash scripts/make-starter-diagrams.sh`; `StartersTest` green.
- [ ] `bash scripts/make-tutorial-html.sh`; `GuideTutorialPageTest` green.
- [ ] `weave rules docs core`; `weave rules test core` green.
- [ ] `SecurityDocTest` green; `secure-by-construction.md` updated.
- [ ] `bash scripts/verify-skill-package.sh` green.
- [ ] `make format-check`, `make release-check` green. No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Starter 07 against a real webhook receiver you control (no key in the repository): one question on the console, then on Telegram; the message posted is exactly what was approved | ⬜ |
| M2 | Kill the run after the yes and before the post; resume; the post happens once | ⬜ |
| M3 | `weave review` on the owner's own scheduled workflow with the new defaults: read every new LH finding and decide whether each is right | ⬜ |
| M4 | Read tutorial chapters 6 and 8 as a newcomer | ⬜ |

## 5. Documentation owed

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/06` ⬜, `09-go-live` ⬜, `13-earned-autonomy` ⬜ · tutorial ch. 4 ⬜, 6 ⬜, 8 ⬜, 9 ⬜, 11 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · starter 07 and starters README ⬜ · `docs/review/rules/` ⬜ · phase 9 DESIGN R33 note ⬜ · `secure-by-construction.md` ⬜ · skills (workflow guide ⬜, harness design ⬜, deployer ⬜) · VS Code grammar, snippets, CHANGELOG ⬜ · graph-render ⬜ · eval4j README ⬜ · `API_COMPATIBILITY.md` ⬜ · `llms.txt` ⬜ · release notes ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] M1 to M4 done and recorded in §7
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

### 27a: every tool has an effect (2026-11-03)

**Built.** `effect: none | reads | changes` on any tool; `effects: { op: … }` on an `openapi` tool and inside an `mcp` block; `ToolDef.getEffect()` and `getOperationEffects()`; `Capabilities.resolve` now carries the effect in the task's three words with who decided it (`DECLARED` or `ASSUMED`) and a reason that points at `effect:`; the developer's word wins; `reach:` on a class tool and in a `.loot` file map to an effect and count as declared; `effect:` with `reach:` is a load error; `weave explain` prints each tool's effect and source; the review fact model carries `effect`, `effectSource`, `effectReason` (so `weave review --format json` shows them); `McpEffects` decides an MCP tool's effect from the developer's `effects:` first, then the server's annotations.

**Re-run of P7** after the change, on the same script with the declarations added: unapproved effects shrink from Search, Shop, Mine to Shop only (Shop keeps it because operations not listed are assumed to change, as designed).

**Tests** (`effects/*Test`, `ToolEffectFactsTest`): written after the parser and resolver, not before; I wrote the code first this time and the tests second, so the red step was done by removing each guard by hand instead. By hand: ignoring a declared effect fails `DeclaredEffectWinsTest` (3 of 3); dropping the `effect:`+`reach:` check fails `EffectAndReachTogetherIsAnErrorTest`. Both restored.

**Gates.** `mvn verify` green on `ai-agent4j-loom` (4,342 tests, JaCoCo rules met) and, after installing the Loom jar, on `loom-inspect-core`, `loom-fabric` and `loom-inspect`. Tutorial page regenerated (`make-tutorial-html.sh`). Not run: PIT (no mutation profile covers this code yet), VS Code `npm test`, `make format-check` (Loom is outside Spotless).

**Deviations, what discipline is lost, what covers it.**
- *MCP annotations are not read when the audit or `weave check` runs.* Asked: hints read at connect time or from a cached `weave mcp list`. Built: the resolver (`McpEffects`) and the `effects:` declaration, but nothing connects to a server or caches a list. Lost: a read-only MCP tool is still counted as changing until the developer declares it under `effects:`. Covered by: the safe default (it is treated as changing, never as harmless), the declaration, and the explanation naming `effects:`. Owed: connect-time/cached hints.
- *An `openapi` tool with some operations declared is still treated as changing as a whole.* Asked: per-operation effect. Built: `Capabilities.operationEffect` answers per operation, but the audit and approval lists work per tool, and the spec file is not read. Lost: declaring `listOrders: reads` does not yet remove the tool from the unapproved list. Covered by: unlisted operations fall back to changing (never weaker); a tool-wide `effect:` is honoured. Owed: per-operation approval, with 27b.
- *`declare-effect` review option and the editor quick fix (D34) are not built.* No review rule exists yet to hang the option on; LA02/LH02/LH05 arrive in 27c. Lost: no one-click declaration. Covered by: the explanation and review JSON name the assumed value and `effect:`. Built with 27c.
- *The graph and the editor hover do not show the effect.* Lost: the effect is visible in `explain` and review JSON only. Covered by: those two. Owed to 27d with the graph `use` kind.
- Spotless was run on the Loom module by mistake; unrelated reformatting was reverted, only wrapping changes inside files I had touched remain.

### 27b: `use` and `approve` on a step (2026-11-04)

**Built.** `use Tool(name = value) [approve ["heading"]] [-> var] [retry N] [backoff T] [timeout T] [on_declined { }] [on_failure { }]`; `approve`/`on_declined` on `run` too; arguments resolved like a `run`'s and matched to the tool's declared parameters at the time the step runs; the same tool object as an agent's call (`toolNamed`); the result journaled (a resumed run does not call twice); one question to a person through the existing approval gate, answered once per step and arguments, with personal data masked; a no (or anything but a yes) runs `on_declined` or fails the step, and is journaled so a resume takes the same road; `weave check` errors for an unknown tool and for `retry` on a changing tool not marked idempotent; `use` is an exit under a policy (26); graph kind `use` (renderer and schema synced); trace field `by: use`; eval4j `usesTool`; `weave test` scripts a `use` tool like any tool; CTK scenarios `use_tool` and `approve_step` with a conformance test; guide, prompt, tutorial chapter 6, `llms.txt`, release notes, API compatibility, VS Code changelog and grammar. Tests: `UseStatementTest` (16), `UseCtkConformanceTest`, `ChecksPolicyAssertionTest.usesToolCountsOnlyCallsMadeByAUseStep`.

**Not built (owed), with what is lost and what covers it.**
- *Argument names are checked when the step runs, not by `weave check`.* Asked: a `weave check` error. Lost: a typo in an argument name is found at the run, not the check. Covered by: the run fails that step before anything is sent, naming the parameters the tool takes. The tool's parameters are only known once the tool object exists, which needs its credentials.
- *`weave eval --mock` answers a `use` of a real tool as that tool would, not `[mock tool result]`.* Lost: a free mock run may call a real tool. Covered by: a changing tool in a mock run is described, not run, as for an agent's call (the tool is wrapped the same way); not separately tested here.
- *"A timeout (when the workflow sets one for questions) is a no"* is the existing human interface's behaviour; not tested for a step.
- *Snippets for `use` in the VS Code extension* (the README lists every snippet prefix and a test types them): not added.
- *The review changes (R3 to R7, R9), LR11, routing (R11), starter 07 and the skills (R8) are 27c to 27e.*

**Gates.** See the commit for the full-module run.

### 27c: the review changes (2026-11-04)

**Built.** The Human oversight lens is a default lens (R7; `lenses = -oversight` restores the old default; `Lens.OVERSIGHT` is no longer optional); **LH05** (R6) for an agent whose `approve:` covers a changing tool in a script with a `schedule`; **LH06**, the step form of LH02 (`use ... approve` on a tool whose effect is none or reads); **LR11** (R10) from `HandoffFacts`, a structural value-flow check of free prose into an acting `delegate` or `use`; LA02's advice offers the boundary first (R3); rule pages regenerated; corpus cases for LH05, LH06, LR11 and the examples for each; guide sections "Where to ask a person" and "Never pipe an agent straight into an action". Tests: `ReviewCorpusTest` (precision and recall at 0.9 on the labelled corpus), the rule examples, `FactBuilderTest`, `OptionalLensesTest` (now: on by default, off with `-oversight`), `ReviewSettingsTest`, `ReviewEngineTest`.

**Deviations, with what is lost and what covers it.**
- *LH02 for steps is a separate rule, LH06.* Asked: LH02 also fires for steps. The rule engine allows one entity kind per rule id. Lost: the id differs from the spec. Covered by: identical advice, the same lens, and both pages cross-refer.
- *LH02/LH06 for `run ... approve`* is not built: a task's effect is known only to the host's registry, not to the static review. Covered by: nothing statically; the runtime asks once as designed.
- *LH03 does not count steps' approvals* (R4). Lost: the question count in the review ignores `use` and `run` approvals. Covered by: they are one question per execution; `human_prompt` and `decide` are still counted.
- *R9 grouped findings, new-first summary and the baseline suggestion are not built.* Lost: with the lens on, the review is longer and not grouped. Covered by: `--baseline write` exists today; the release note says to use it.
- *LH05 reads "nobody watching" from a `schedule` only* (not a trigger file or the daemon configuration).
- *No `to-boundary`, `insert-extract` or `approve-step` fix options, and no `declare-effect` option or editor quick fix (D34).* Covered by: each finding's message names the fix in words.
- *LR11 follows `delegate` and `use`, not `run` of a `CHANGES` task* (its effect is not known statically).
- *The precision bar and noise gate (D35) for LH01 to LH05 were measured only on the labelled corpus and the existing example set; no separate benchmark was run.*

