# Phase 9: where every rule stands, and the plan for the ones not built

Status of each rule in `DESIGN.md` §6, how it will be built, what it waits for, and the test that closes it. Updated at the end of every sub-phase. "Data" means a YAML rule in the core pack over the fact model; "provider" means a new Java fact that data rules then use; "Java" means a rule class (R21, R73), used only where a condition over facts cannot say it.

Rules for which the design already says *heuristic, off by default* are marked **H**; they get the 0.9 precision gate only if they are promoted (R18).

## Built

| Rules | Mechanism | Since |
|---|---|---|
| LA01 to LA20 | Java (the security audit, adapted) | 9a |
| LV00 to LV09 | Java (the validator, classified by message; LV08 is the suppression rule) | 9a, 9b |
| LR01, LR03, LD02, LY02, LM01, LG04 | data | 9a |
| LC01, LC02 | data | 9c |
| LD01, LD04, LY01, LY04, LY05, LY06, LO01, LO04, LC03, LP02 (H), LP05 (H), LC07 (H) | data (9f; facts listed in VERIFICATION §7 9f) | 9f |
| LR02, LR04, LR05, LR07, LR08, LR09 | data (new facts: `workflow.isScheduled`, `script.hasRateLimits`, `step.readsFreeText`, `step.writesSameVariableAsSibling`) | 9d |
| LP01, LP03, LP04, LP06, LP07 | data (new facts: `agent.systemInterpolates`, `promptTokens`, `promptHasSecret`, `nearDuplicateOf`); LP01 uses `escalate` for HIGH with tools; LP07 (new, found by the live cost check) reports a variable name that occurs inside prompt text, which Loom replaces everywhere (`step.payloadHitsVariable`). LC02 was reworded in 9d to be about the shared transcript (`agent.hasMemoryLimit`) | 9d |
| LC04, LC05, LC06 | Java (they need the cost estimate) | 9c |

## Plan, by sub-phase

### 9d: reliability and prompts, and the corpus (done)

| Rule | Mechanism | New facts | Note |
|---|---|---|---|
| LR02 | data | none | write or send tool, no `on_failure` |
| LR04 | data | `workflow.isScheduled` | `human_prompt` with no timeout on a scheduled workflow |
| LR05 | data | provider `step.readsFreeText` | a branch condition reads a field of a variable bound by an agent with no `output_schema` |
| LR07 | data | none | effectful agent in a loop or `for each` with no approval. The spec pairs it with LA17 for de-duplication, but LA17 is "a tool that acts as the person running the workflow", a different cause, so no `Overlap` is registered; the first real pair is looked for in 9h |
| LR08 | data | `script.hasRateLimits`, `agent.providerName` | scheduled script, provider without `rate_limits` |
| LR09 | data | provider `step.writesSameVariableAsSibling` | parallel branches binding one variable name |
| LP01 | data | `agent.systemInterpolates` | a `{variable}` in a system prompt; HIGH with tools |
| LP03 | data | provider `agent.promptTokens`; default `data.prompt.max-tokens = 2000` | prompt larger than the limit |
| LP04 | data | provider `agent.promptHasSecret` | key-shaped strings in prompts (shared pattern list with the audit) |
| LP06 | data | provider `agent.nearDuplicateOf` | prompts that are near-copies (word-shingle similarity) |
| corpus | test infrastructure | | labelled corpus, per-rule precision and recall, noise on the repo's own examples, recorded-run cost accuracy (A4, A7, R30) |

Deferred out of 9d, with the reason:

| Rule | Why not now | Goes to |
|---|---|---|
| LR06 (alt over an enum does not cover every value) | needs the schema's enum values and the values compared in each branch: a provider that parses conditions against `output_schema` | 9f, with the condition-parsing provider LR05 starts |
| LR10 (no checkpoint before an expensive span) | INFO and a judgement about "expensive"; needs the cost estimate per span, so it is a Java rule over the estimate | 9g |
| LP02 (no output-format instruction before a typed branch) | needs prompt-text analysis; **H** | 9f |
| LP05 (no "when unsure" instruction) | text heuristic; **H** | 9f |

### 9e: handling findings (done; no new rules)

Fixes now exist for LR01, LR03, LR08, LY02, LM01 (safe) and LR02 (needs judgement). Options (the developer chooses and supplies values, R75 to R78) exist for LR01, LR02, LR03, LY02 and LM01; next to get options: LC01, LC02, LG04, LR08 and the LP rules (see `FIX_CAUTIONS.md`). Other rules gain a `fix:` template as they get an anchor that is safe to guess; LC02 (needs a memory or context block with a size) and LG04 (which version to pin) stay prose.

### 9f: observability, durability, privacy, and the declared-facts machinery (done, except as listed)

| Rule | Mechanism | Needs |
|---|---|---|
| LO01 to LO04 | data | facts from the `.inspect` file, price table and window catalog (`project.hasInspectRules`, `model.hasPrice`, `model.hasWindow`); LO03 needs `workflow.endsInDifferentOutcomes` |
| LD01, LD03, LD04 | data | `journal` setting (declared), `step.hasEffectUnjournaled`, rewind facts; LD02 already built |
| LY01, LY03, LY04, LY05, LY06 | data | `data.personal`, `data.regions`; `agent.sendsPersonalData` provider; prompt-file scan for LY06 |
| LP02, LP05 **H**, LR06 | data over a condition-parsing provider | the provider LR05 starts |
| LC03 | data | `step.fanOutModels` (a per-member view of broadcast and parallel) with `data.top-models` |
| LC07 **H** | data | `agent.promptSaysDeterministic` (word list) |
| lens selection and inert-rule notes | already built (9a); finish the report section "not evaluated" |

### 9g: time, effects, testability

| Rule | Mechanism | Needs |
|---|---|---|
| time estimate (R35), effects estimate (R36) | estimator extension | the same walk as cost; step latency assumptions (`assume.latency.*`), human waits shown apart |
| LC08 ✅ 9g | data over a Java fact | data-independence analysis over the graph (neither step reads what the other binds, no effect ordering) and the time estimate for the saving |
| LC09, LC10 ✅ 9g | Java over the time estimate | `workflow.timeWorstSeconds`, `workflow.interactive` (declared) |
| LR10 ✅ 9g | data over two Java facts | `loopBodyHasEffects`, `checkpointBefore` (the cost estimate is not needed) |
| LT01, LT02, LT04, LT05, LT06, LT07 ✅ 9g; LT03 open (O14) | Java source reading test files (not data: it reads files beside the script) | `ReviewInput` reads test files and the coverage report; staleness note (R34) |

### 9h: the four optional lenses

| Rule | Mechanism | Needs |
|---|---|---|
| LG01 ✅, LG02 ✅, LG05 ✅ (9h); LG03 not buildable (D6) | data | a model catalog fact (`model.known`, `model.deprecated`, `model.nearest`); LG04 built |
| LM02 ✅, LM03 ✅, LM05 ✅ (9h) | data | `agent.inlinePromptLines` (exists), `workflow.stepCount`/`maxDepth` (exist), `script.repeatedThreshold` provider; LM01 built |
| LM04 ✅ (agents only, 9h) | data | `agent.nearDuplicateOf` (from LP06) and a workflow-body equality provider |
| LH01 ✅, LH02 ✅, LH04 ✅ (data), LH03 ✅ (Java) (9h) | data / Java | `step.followedByEffect`, `tool.reachNotWider`, `workflow.worstQuestions` (from the effects walk) |
| LB01 ✅ (data), LB02 ✅, LB03 ✅ (Java) (9h) | data / Java | `step.effectCap`, effects estimate, schedule frequency; LB03 is Java (arithmetic across entities) |
| cross-lens de-duplication (R40) | engine `Overlap` pairs | one pair per rule that repeats an LA finding |

### 9i: docs and `weave rules diff`

Pages generated from the packs; the guide for writing a rule; `rules diff` and the pack versioning policy (R74).

## Rules of the road for the plan

1. A rule moves forward only with its two example scripts, an `--explain` page and a corpus entry; `weave rules test core` must stay green.
2. A fact is added only when a rule needs it, with `since` set, and is listed by `weave rules facts`.
3. A rule that cannot reach precision 0.9 on the corpus is demoted to heuristic (off by default), never shipped noisy.
4. Anything that needs a decision about meaning (what is "expensive", what is "personal") is read from a declared setting, never guessed.
5. This file is updated in the same commit that changes a rule's status.

## Moved to 9g from 9f

| Rule | Why it did not fit 9f | Needs |
|---|---|---|
| LR06 (alt over an enum does not cover every value) | The schema's enum values and the values compared in each branch | A condition-parsing provider over `output_schema` |
| LO02 (model without a price or window) | The price table and window file are inputs of `weave run`, not of the script | `ReviewInput` reads `--prices` and the windows file (R34) |
| LO03 (branches end in different outcomes with nothing saying which ran) | An analysis of how each branch ends | The graph's end nodes per branch |
| LD03 (rewind span with a changed effect) | The span between a `checkpoint` and its `rewind` | The statements between two graph nodes |
| LY03 (provider outside `data.regions`) | A catalog from provider to region, which the script does not carry | `data.provider-regions` declared, or a bundled table |
