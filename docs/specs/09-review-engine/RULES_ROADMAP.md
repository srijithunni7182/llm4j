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
| LC04, LC05, LC06 | Java (they need the cost estimate) | 9c |

## Plan, by sub-phase

### 9d: reliability and prompts, and the corpus (this sub-phase)

| Rule | Mechanism | New facts | Note |
|---|---|---|---|
| LR02 | data | none | write or send tool, no `on_failure` |
| LR04 | data | `workflow.isScheduled` | `human_prompt` with no timeout on a scheduled workflow |
| LR05 | data | provider `step.readsFreeText` | a branch condition reads a field of a variable bound by an agent with no `output_schema` |
| LR07 | data | none | effectful agent in a loop or `for each` with no approval; absorbs LA17 (`Overlap`) |
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

### 9e: handling findings (no new rules)

Fixes for LR01, LR03, LC02, LY02 and the others that carry a `fix:` template; accept, baseline, fingerprints, the advisory notice.

### 9f: observability, durability, privacy, and the declared-facts machinery

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
| LC08 | Java | data-independence analysis over the graph (neither step reads what the other binds, no effect ordering) and the time estimate for the saving |
| LC09, LC10 | data over the time estimate | `workflow.timeWorstSeconds`, `workflow.interactive` (declared) |
| LR10 | Java | per-span cost from the estimator |
| LT01 to LT07 | data over new inputs | `ReviewInput` reads test files and the coverage report; staleness note (R34) |

### 9h: the four optional lenses

| Rule | Mechanism | Needs |
|---|---|---|
| LG01, LG02, LG03, LG05 | data | a model catalog fact (`model.known`, `model.deprecated`, `model.nearest`); LG04 built |
| LM02, LM03, LM05 | data | `agent.inlinePromptLines` (exists), `workflow.stepCount`/`maxDepth` (exist), `script.repeatedThreshold` provider; LM01 built |
| LM04 | data | `agent.nearDuplicateOf` (from LP06) and a workflow-body equality provider |
| LH01 to LH04 | data | `step.followedByEffect`, `tool.reachNotWider`, `workflow.worstQuestions` (from the effects walk) |
| LB01 to LB03 | data / Java | `step.effectCap`, effects estimate, schedule frequency; LB03 is Java (arithmetic across entities) |
| cross-lens de-duplication (R40) | engine `Overlap` pairs | one pair per rule that repeats an LA finding |

### 9i: docs and `weave rules diff`

Pages generated from the packs; the guide for writing a rule; `rules diff` and the pack versioning policy (R74).

## Rules of the road for the plan

1. A rule moves forward only with its two example scripts, an `--explain` page and a corpus entry; `weave rules test core` must stay green.
2. A fact is added only when a rule needs it, with `since` set, and is listed by `weave rules facts`.
3. A rule that cannot reach precision 0.9 on the corpus is demoted to heuristic (off by default), never shipped noisy.
4. Anything that needs a decision about meaning (what is "expensive", what is "personal") is read from a declared setting, never guessed.
5. This file is updated in the same commit that changes a rule's status.
