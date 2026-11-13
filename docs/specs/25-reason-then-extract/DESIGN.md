# Phase 25 design: reason, then extract

Status: **draft for review** (decisions D5 to D8 recorded 2026-11-01). Layer: the review engine (a new rule, two reworded rules, a fix), the examples and their tests, the documentation and the tutorial. No new language construct: it uses the `extractor` of [phase 24](../24-stateless-nodes/DESIGN.md). Part of the [engineering discipline program](../DISCIPLINE.md).
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Depends on phase 24 (the extractor, LP09's fix machinery, the short-prompt setting).
Evidence owned: **E7 to E9, E22, E25**. Mistakes: **M2, the JSON trap**, **M8, stringly-typed state** (D42), and **M11, unbounded fan-out** (D44).

## 1. Problem

When an agent has an `output_schema`, the runtime appends "CRITICAL: You MUST respond in valid JSON format only, following this schema: …" to its task (E7). "JSON only" leaves no room for the agent to think in its answer. An agent with tools can reason between tool calls, but its final synthesis must come out as JSON at once; an agent without tools that judges, plans or decides must fit all its reasoning into the schema. Two review rules push developers into this (E8), and 17 agents in the repository's own scripts do it (E9).

Not every schema is the trap. A small, flat verdict (`status: enum[...]`, one or two fields) is cheap for a model and remains the right tool. The trap is a **large schema on an agent that has to reason**.

## 2. Goals and non-goals

Goals:

- Review notices the trap and says how to get out of it (R1).
- The rules that pushed developers in now offer the way out first (R2).
- A one-command fix splits a trapped agent into an agent and an extractor (R3).
- The repository's examples are reference implementations of the pattern (R4).
- The documentation and the tutorial teach "reason, then extract" (R5).
- A fan-out over a list a model wrote is bounded, and an over-long list is treated as a sign of trouble upstream (R7, D44).
- A long-running agent's progress is kept in the workflow, not in its conversation, taught as a recipe with a tested starter (R6, D42).

Non-goals:

- A shorthand such as `delegate … -> x via Extractor` (D5: it would hide a model call and its cost).
- Changing the instruction the runtime appends for a schema. It stays for the small, flat schemas that remain right.
- A "think first, then end with JSON" mode in one call (keeps thinking and formatting coupled).

## 3. Requirements

**R1 LP10: an agent that reasons must answer in a large schema** (Prompts lens, **medium**, D6 and D8; rule data in `prompts.yaml` over new facts). Fires for each agent that **reasons** and has a **large schema**:

- *Reasons* means any of: it has `tools:`; it has `grounding:`; it is the target of a `delegate` inside a `loop` or `for each` whose iterations feed its own previous answer back (it works over rounds); its prompt (system, persona, prompt file and skills together) is over `prompts.short-prompt-tokens` (default 300, shared with LP09).
- *Large schema* means its `output_schema`, or the `expecting` of any delegate to it, has any of: a nested object; a list of objects; more than `prompts.large-schema-fields` top-level fields (default 5).
- Message: "Agent `name` reasons (it has tools / grounding / works over rounds / a prompt of about N tokens) and must answer in a schema of N fields (with a nested object / a list of objects). Let it answer in prose, and pull the fields out with an extractor."
- What to do, in this order: let the agent answer in prose and add an `extractor` after it; or make the schema small and flat; or accept with a reason.
- The facts are new and objective (`AgentFacts.reasons`, `reasonsBecause`, `SchemaFacts.large`, `largeBecause`); both settings are in `review.properties` and documented on the rule page.

**R2 LR05 and LP02 offer the way out first.**
- **LR05** ("branches on a field of an answer that has no output_schema"). New "What to do": "If the branch is on a label, ask an `intentclassifier`. If it is on fields of what an agent worked out, add an `extractor` after the agent and branch on its fields. For a small, flat answer from an agent that does not reason, an `output_schema` with that field is enough." The rule's condition is unchanged.
- **LP02** ("decides a branch but its prompt does not say what shape the answer must have"). New "What to do": the same three options, then "or tell the agent to answer with exactly one of the allowed words".
- Neither rule may recommend a schema to an agent that LP10 would then flag: a test runs each rule's recommended fix through LP10.

**R3 The split fix.** LP10 offers `--fix-finding … --option split` (needs thought, so `--yes` only after the user agrees; `--dry-run` shows the diff):
- The agent keeps its prompt, tools and settings, loses its `output_schema` (or the step loses its `expecting`), and gains one sentence at the end of its system prompt: "Answer in plain prose; explain your reasoning, then state your conclusions." (wording fixed in the rule data, so it can be reviewed).
- A new `extractor <Agent>Fields` is added with the old schema as `fields`, every field listed in `optional:` unless the old schema was used in a condition that required it, the cheapest model in the script's catalogue for that provider (or the agent's model when no cheaper one is known), and `instructions: "Pull these fields out of the analysis."`.
- Each `delegate … to Agent -> v` becomes `delegate … to Agent -> v_analysis` followed by `extract <Agent>Fields from v_analysis -> v`, so every later use of `v` and `v.field` is unchanged.
- After applying: the script checks, LP10 is gone, no high finding appears, and `weave test` cases that scripted the agent's JSON are reported as needing an update (the fix never edits test files; it lists them).
- When the rewrite is not mechanical (the same agent's answer used both as prose and as fields; a `broadcast`), the option is not offered and the finding says why.
- Once [phase 28](../28-evaluation-first/DESIGN.md) is built, the new extractor needs golden cases to pass `weave check`. The fix moves the agent's cases that checked JSON fields into the extractor's file; when there are none, its result names the file and the `--init` command, and "the script checks" means "checks apart from that gate".

**R4 The examples are reference implementations** (D7). Every agent in the repository's `.loom` files that LP10 flags is migrated to the two-step shape in this phase. The candidates found on 2026-11-01 (an `output_schema` with tools or grounding):

| File | Agents |
|---|---|
| `src/examples/getviral/src/main/resources/getviral/getviral.loom` | Showrunner, Researcher, Strategist, XWriter, ReelDirector, YouTubeProducer, ArtDirector, VideoEditor |
| `src/examples/inspect-soak/soak.loom` | Editor, FactChecker, SEO |
| `src/examples/nirmaan-yantra/nirmaan-yantra-server/src/main/resources/agents/crew.loom` | Rishi, Vihaan |
| `src/loom/loom-inspect/src/main/resources/io/github/llm4j/loom/inspect/sim/sim.loom` | Editor, FactChecker, SEO |
| `src/loom/ai-agent4j-loom/src/main/resources/review-rules/core/examples/lc01-quiet-tools.loom` | Router |

- The list is re-derived at build time by running LP10 over every `.loom` file (`ExamplesAreCleanOfLp10Test`), since some candidates may have a small schema and not trip the rule. An agent the rule does not flag is left alone, and §7 of the verification records which.
- `lc01-quiet-tools.loom` is a **rule fixture** that exists to make LC01 fire. It is not a reference implementation; it gets an `LP10` acceptance with the reason "fixture for LC01" in its own `review.properties` rather than a rewrite, unless the rewrite keeps LC01 firing (open question 1).
- Each migrated example keeps its tests green: scripted cases that returned the agent's JSON now script the agent's prose and the extractor's fields. Golden datasets aimed at a migrated agent keep their cases; cases that checked JSON fields move to the extractor's file (`<extractor>.yaml`, phase 28's layout).
- The `loom-inspect` simulator's expected traces and Fabric fixtures that count model calls are updated for the extra extract step.

**R5 Teaching.**
- `LOOM_GUIDE.md` "Output Schema Enforcement" gains, first, **"When to use a schema, and when to extract"**: a schema on an agent is for a small, flat answer from an agent that does not reason; for anything larger, reason in prose and extract. Example shown with phase 24's syntax.
- The skills: `llm4j-workflow-guide` adds the pattern to "Build the workflow" (stage 6) with LP10 named; `llm4j-harness-design` adds one line to principles ("decouple thinking from formatting"); `llm4j-eval-design` notes that the agent's reasoning and the extractor's fields are evaluated separately (the agent by rubric on prose, the extractor by exact field checks).

**R6 Keep progress in the workflow, not the conversation** (M8, D42). The same pair, a worker that reasons and an extractor that records, answers M8. When one agent would otherwise work through many tool calls tracking "what's done and what's next" in its chat (which compaction may remove), the recipe lifts the progress into the script, which is already Loom's state machine:
- a bounded `loop until (status.finished == "true") max N`;
- a worker agent with `memory { transcript: none }` (phase 24) given the goal, what is done and the one step to do now;
- an `extractor Progress` with `fields { done: list<string>  next: string  finished: boolean }` that reads the goal, the previous progress and the worker's result, and writes the new progress into a journaled variable.

Progress is then typed, journaled (a resume restores it), bounded, visible in the graph and testable, and compaction cannot remove it. No new construct is added; a `state { … }` block on an agent is out of scope until field use shows the recipe is not enough.
- **Starter `16-step-by-step-agent`**: the recipe as a complete, tested reference (a worker with fake file and test tools, its `weave test` cases scripting the rounds, including one that stops at the bound and one resumed mid-way from the journal), with its README and diagram.
- **Teaching**: the `llm4j-harness-design` skill gains the principle "keep progress where the harness can see it, not in the conversation" and the recipe in `references/loom-mapping.md` (and names it among the failure modes: "the agent loses track of where it is"); `LOOM_GUIDE.md` "Context and Compaction" points to it; `docs/guide/14-context-and-compaction.md` gains the recipe; the `llm4j-workflow-guide` skill offers it when a user describes a long autonomous agent.

**R7 Bounded fan-out** (M11, D44).

```loom
parallel for each task in plan.tasks max 10 {
    delegate "{task.description}" to {task.owner} -> results[task.name]
} on_too_many {
    human_prompt "Planner proposed {_count} tasks; the most this step takes is 10. Continue with a smaller plan? (yes/no)" -> answer
}
```

- `for each … max N` and `parallel for each … max N`, the same bound `loop` already has. `on_too_many { … }` runs instead of the block when the list is longer than `N`, with `_count` (the list's length) and `_max` in scope; it may ask a person, `halt` with a message, ask the planner again, or (deliberately) narrow the list with `set` and a `call`.
- **Without `on_too_many`, the workflow fails before any item starts** (D44): "`plan.tasks` has 412 items, written by agent **Planner** (line 20); this step takes at most 10 (line 22). A list this far over its bound usually means a problem upstream: what Planner was given, what it was asked, or text in its input trying to steer it." Nothing is half-done. The `on_failure` of an enclosing step, if any, runs.
- **The error points upstream.** When the list was produced by an agent or an extractor, the message, the trace event `fanout_exceeded` (the count, the bound, the producing agent and line, never content) and the run's failure record name that agent.
- **It is an incident.** loom-inspect gains a default rule, *plan far over its bound*, shown on the live page and in Fabric, because a planner regression and an injection attempt both look like this.
- **It becomes a test.** `weave capture` turns such a run into a case; the skills say to add a golden case to the planner's dataset that asserts its list stays within the bound, so the next prompt or model change is checked against it.
- **LR13: unbounded fan-out** (Reliability, **medium**): a `for each` over a list produced by an agent or an extractor, with no `max`. What to do: add `max N` (and tell the planner the limit in its instructions). `weave review`'s cost and time estimate shows "worst case unknown: the list has no bound" for such a step instead of an assumed size. LR13 meets the precision bar and the noise gate (D35).
- **Teaching**: `LOOM_GUIDE.md` "for each" documents `max`, `on_too_many` and the upstream reading of the error; the skills (workflow guide stage 6, harness design's bounds) say the bound is the last line of defence and the planner's instructions and golden cases are the real fix. Starter 06 gains `max 4` (its planner is asked for 2 to 4 tasks); phase 27 adds its allow-list and map.

## 4. Java API

No public API changes. New facts in the review fact model (`AgentFacts`, a new `SchemaFacts`), a new fix template in the core pack, rule-data changes to `prompts.yaml` and `reliability.yaml`. `docs/API_COMPATIBILITY.md` records the review-rule changes under "rule packs".

## 5. Compatibility

| Change | Existing users |
|---|---|
| LP10 | A new medium finding in a lens on by default. Advisory. A CI job using `weave review --fail-on medium` may start failing; `LP10 = off` or an acceptance with a reason restores it. |
| LR05, LP02 | Wording only; when they fire is unchanged. |
| Examples | Behaviour of each example is the same to its user; it makes one more (cheap) model call per migrated step. The release notes say so for `getviral` and `nirmaan-yantra`. |

## 6. Security notes

- The split moves typed data, not prose, between steps, which narrows what an injected sentence in the agent's answer can do downstream (the extractor's inputs are marked as data, phase 24 R3).
- The fix never edits test files or golden data; it lists what needs updating.

## 7. Open questions

1. R4: should the LC01 fixture be rewritten (keeping LC01 firing) or accepted as a fixture? Proposed: accepted, with the reason recorded; it is test data for another rule.
2. R1: is "works over rounds" (the agent's own answer fed back in a loop) objective enough to detect from the script? Proposed: yes, by variable flow within the loop body (the same analysis `LC02` uses); if it proves noisy in §0, it is dropped from the definition and recorded in §7 of the verification.
3. R3: the default model for the generated extractor. Proposed: the cheapest model of the same provider in the seeded catalogue; the diff shows it so the user can change it before applying.

## 8. Documentation affected

`LOOM_GUIDE.md` ("When to use a schema, and when to extract"; "for each" with `max` and `on_too_many`; `docs/review/rules/` LR13; the loom-inspect rules page for the new incident; a pointer from "Context and Compaction" to R6's recipe), starter 16 and the starters README, `docs/guide/14-context-and-compaction.md`, `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md`, the tutorial (§9), `docs/review/rules/` (LP10, LR05, LP02 regenerated), the three skills named in R5, each migrated example's README (which agents now answer in prose and which extractor follows), `src/loom/loom-inspect` simulator docs where they describe the sim's steps, release notes, `docs/API_COMPATIBILITY.md` (rule packs), `llms.txt` (the pattern in one line).

## 9. Tutorial

| Chapter | Change |
|---|---|
| [5. Structured answers and failure](../../tutorial/05-structured-answers-and-failure.md) | New section **"Reason first, then extract"** after the extractor section of phase 24: why "JSON only" hurts an agent that must think, the two-step shape, a side-by-side of the trapped and the fixed script, and a short list of when a small `output_schema` on an agent is still right. "Try it yourself" gains: run `weave review` on the trapped version, read LP10, apply the split with `--dry-run`. |
| [14. Cookbook](../../tutorial/15-cookbook.md) | Recipe **"Let the reasoning agent think, then extract"**; "mistakes and their fixes" gains LP10 and the new wording of LR05 and LP02. |
| [4. Control flow](../../tutorial/04-control-flow.md) | `for each` gains **`max N` and `on_too_many`**: the bound, what happens when a planner returns far more than it should, and why that points upstream (R7). |
| [11. Advanced orchestration](../../tutorial/11-advanced-orchestration.md) | New section **"Keep progress in the workflow, not the conversation"** beside context and compaction: why a long agent loses track, the recipe, and starter 16 walked through. |
| [README](../../tutorial/README.md) | Chapter 5's row mentions "reason, then extract"; chapter 11's row mentions keeping progress in the workflow; the starter table gains starter 16. |

Every new `loom` block is checked by `TutorialExamplesTest`; the page is regenerated with `bash scripts/make-tutorial-html.sh`.
