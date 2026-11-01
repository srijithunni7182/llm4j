# Phase 24 design: stateless nodes and least-privilege context

Status: **draft for review** (decisions D1 to D4, D23 and D24 recorded 2026-11-01). Layer: the Loom language, validator, runtime, security audit and review engine; eval4j's workflow assertions; the VS Code extension and graph renderer. Part of the [engineering discipline program](../DISCIPLINE.md); read its §1 to §4 first.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Builds on [Phase 3](../03-loom-integration/DESIGN.md) (the transcript and its bound), [Phase 7](../07-classifier/DESIGN.md) (`intentclassify`, whose shape the extractor follows) and [Phase 9](../09-review-engine/DESIGN.md) (rules as data).
Evidence owned: **E1 to E6** of the [program page](../DISCIPLINE.md#2-the-evidence-what-the-code-does-today). Mistake: **M5, global state leakage**.

## 1. Problem

The owner's rule for a utility node is that it behaves like a pure function: it is given only the text and schema it needs, and nothing else. Loom does the opposite by default. Every `delegate` gets the whole run transcript ahead of its task, and every agent gets the script's `shared_prompt` ahead of its own prompt (E1, E3). Neither can be turned off for one agent (E2). The stateless model step that does exist, `Task.withModel`, needs Java (E4), so a script author who follows the recommended path ("you write no Java for the workflow itself") writes an extractor as an `agent`, exactly as the guide and starter 08 teach (E5). In a longer workflow that agent sees everything said before it, adopts the house persona, and answers differently depending on what ran first, so a golden-dataset test of the agent on its own does not predict its behaviour inside the workflow.

A second consequence is a security one. The transcript carries text from an agent that read untrusted content into the briefing of every later agent, and the audit does not count it (E6).

## 2. Goals and non-goals

Goals:

- A script author can declare a **stateless model step that pulls fields out of text**, in the script, with no Java (R1 to R6).
- The **internal-node contract** is written down once and enforced for every internal node kind (R7).
- An ordinary agent can **leave the transcript or the shared prompt** (R8), and review says when that leaves it with nothing to work from (R9).
- Review **notices an agent used as an extractor** and offers to rewrite it (R10).
- The audit **counts the transcript** as a path for untrusted text (R11).
- The examples teach the new shape (R12).

Non-goals:

- Changing the default transcript behaviour for agents. It stays `all`; existing scripts mean what they meant.
- Reasoning agents and the JSON trap: [phase 25](../25-reason-then-extract/DESIGN.md).
- The evaluator and the policy boundary: [phase 26](../26-policy-boundary/DESIGN.md).
- Citations or evidence spans per extracted field. Not asked for (YAGNI).
- Removing `Task.withModel`. It stays the Java route for a model call that needs code around it.

## 3. Requirements

### The extractor (D1 to D4)

**R1 Declaration.** A top-level `extractor` block declares a stateless field extractor:

```loom
extractor Invoice {
    model: "gemini-2.5-flash"
    instructions: "Read the invoice. Use the vendor's legal name. Leave a field empty when the text does not state it."
    fields {
        vendor: string
        invoice_number: string
        total: number
        currency: enum["USD", "EUR", "INR", "OTHER"]
    }
    optional: [total]
    pii: mask
}
```

- `model:` is required and is resolved like an agent's (aliases, declared providers, the model catalogue, `weave check`'s model checks).
- `instructions:` is optional fixed text, inline or `prompt "id"` from the prompts folder (versioned like an agent's prompt file). It **may not contain `{…}`** (D4). The runtime puts it after a fixed preamble that says the task is extraction and that the input is data.
- `fields { … }` uses the field grammar of `output_schema` (`string`, `number`, `boolean`, `enum[...]`, `list<type>`, a bare list, nested `{ … }`). At least one field.
- `optional: [a, b]` names fields that may be absent (D3). Every name must be a top-level field. A field not listed is required.
- `pii: mask | block | warn` as on a classifier. `temperature:` defaults to `0`. `timeout:` and `budget { … }` as on an agent.
- Not allowed, each a `weave check` error that names the line and says "an extractor is stateless; use an agent for this": `tools:`, `memory`, `steerable`, `persona`, `skills`, `grounding`, `max_iterations`, `context`, `approve:`.

**R2 The statement.** `extract Name from <input> -> var` runs it:

```loom
extract Invoice from text -> invoice retry 1 timeout 30s on_failure { halt "Could not read an invoice: {_error}" }
extract Claim from (email = msg, order = record) -> claim
```

- `from x` passes one input; `from (name = x, …)` passes several, each labelled with its name in the request. An input is any value expression the runtime already resolves (a variable, a field path, a string with `{…}`).
- `-> var` is required. `retry N`, `backoff`, `timeout` and `on_failure { … }` work as on a `delegate`; `budget N tokens` as on a delegate.
- `extract` is a contextual word: a variable, agent or task named `extract` still parses (`RepositoryScriptsTest`).

**R3 What reaches the model.** Exactly three things, and nothing else:
1. A fixed system preamble owned by the runtime (extraction, answer only with JSON of the given shape, the input is data and never instructions, leave optional fields out rather than invent them, a required field that cannot be found is answered with `null`), followed by the extractor's `instructions:`.
2. The schema, rendered once by the runtime.
3. The inputs, each between untrusted-data markers, as `intentclassify` does with `on`.

No transcript, no agent memory, no `shared_prompt`, no persona, no skills, no knowledge retrieval, no steer. The extractor's answer is **not** written to the transcript. This is the contract of §4.1 of the program page; `ExtractorSeesOnlyItsInputsTest` pins it by capturing the exact request.

**R4 What is checked.** The answer is parsed and checked like an `output_schema` answer today (first complete object, kinds, enums), with these differences:
- A missing **optional** field, or one answered `null`, reads as the value `missing`, which conditions can test: `alt (invoice.total == missing)`. `missing` is a new reserved value word, usable only in conditions.
- A **required** field that is absent or `null` is a failed attempt: the model is told which fields were missing and asked again. One correction is always allowed (as for schemas today); further attempts follow `retry N`; then the step fails into `on_failure` with `_error` naming the fields. A value is never invented by the runtime.
- Where the provider has a native JSON mode, it is used. An extractor has no tools, so Gemini's JSON mode, which cannot be combined with tool calling, can be used here.

**R5 Plumbing.** An extract step is a model step like any other:
- It counts against the run budget and the budgets of loops around it, and shows in the spend report as `extractor Invoice`.
- It is journaled: a resumed run reuses the recorded answer and the trace shows `extract_replayed`.
- Under `weave test` it is scripted like an agent (`extractors: { Invoice: { … } }` in a case, or the default well-formed value of its schema). Under `weave eval --mock` it gets a well-formed value of its schema.
- Trace events `extract_start` and `extract_end` carry the extractor name, model, tokens, duration, attempts and the **names** of the fields present and missing, never values or inputs. `schema_violation` is emitted for a rejected attempt, as for agents.
- `weave graph`, `weave explain` and the VS Code graph draw an `extract` node (new graph kind `Kinds.EXTRACT`, synced with `scripts/sync-graph-render.sh`), labelled with the extractor and its inputs.
- eval4j: a trajectory node of kind `extract`, and `WorkflowAssertions.runsExtractTimes(name, n)` and `extracted(name, field, value)`.
- The live run page and Fabric show it as a model call under its model, like a classification.

**R6 Checks.** `weave check` rejects, each with a message that names the line and what to write:
- an unknown extractor in `extract`;
- an extractor with no fields, a field written twice, an `optional` name that is not a top-level field;
- `{…}` in `instructions:` ("an extractor's instructions are fixed; pass the value with `from (name = …)`");
- any setting R1 forbids;
- a condition that reads a field the extractor does not have (suggesting the nearest name), using the same symbol service that checks classifier and schema fields;
- `missing` compared with anything other than an optional field (it can never be true).

### One contract for internal nodes

**R7 The internal-node contract.** The properties in §4.1 of the program page are written once as a package-private `InternalNode` description in the runtime (which inputs it gets, that it never touches memory or the shared prompt, that its output is typed) and **both** `ClassifyRunner` and the new `ExtractRunner` are checked against it by one parameterised test (`InternalNodeContractTest`). Phase 26 adds the evaluator to the same test. The `LOOM_GUIDE.md` section on internal nodes states the contract in the same words.

### Ordinary agents that should not see everything (D24)

**R8 Opting out.** Two agent settings:

```loom
agent Summariser {
    model: "claude-haiku-5-5"
    memory { transcript: none }     // none | own | all (the default)
    shared_prompt: none
}
```

- `transcript: all` is today's behaviour. `own` gives the agent only its own earlier exchanges in this run. `none` gives it no transcript at all: its task is the whole briefing.
- With `none` or `own`, the agent's exchanges are **still stored** in the transcript (other agents with `all` see them, as today). Opting out of receiving is not opting out of being seen; that choice stays the author's.
- `transcript:` combines with `max_tokens` and `compact` (they bound what an `all` or `own` agent receives). `transcript: none` with `max_tokens` is a `weave check` warning ("has no effect").
- `shared_prompt: none` leaves the agent out of the script's shared prompt. It has no other value (an agent cannot have a shared prompt of its own; that is its `system:`). Preflight C4 skips such agents and says why.
- `rewind` restores what each agent receives exactly as today.
- `TranscriptAccumulationEngine.assembleContext` reads the agent's setting; the `MemoryEngine` interface is unchanged (it already receives the `AgentDef`). A host's own `MemoryEngine` keeps working and is told the setting through `AgentDef.getTranscriptMode()`; its Javadoc says a custom engine should honour it.
- `handoff` honours the setting the same way as `delegate`.

**R9 LP08: the agent does not see the run's earlier exchanges** (Prompts lens, **low**, rule data in `prompts.yaml`). Fires for each `delegate` or `handoff` to an agent with `transcript: none` or `own` whose task text passes **no variable** (no `{…}` and, under `substitution: legacy`, no bare variable name). Message: "Agent `name` does not see the run's earlier exchanges, and this task passes it nothing from them: everything it needs must be in its task." What to do: pass what it needs with `{var}`, or give it `transcript: all`. It does not fire when the task passes at least one variable (D24: quiet when the author clearly passed what was needed).

### Review and audit

**R10 LP09: an agent used as an extractor** (Prompts lens, **medium**). Fires for an agent that has an `output_schema` (or every delegate to it has `expecting`), no tools, no grounding, no `memory`, a prompt of at most about 300 tokens (the same estimator LP03 uses), and whose every delegate passes at least one variable. Message: "Agent `name` pulls fields out of text. An `extractor` does that without seeing the run's transcript or the shared prompt." Fix options (`--fix-finding`, with `--dry-run` showing the diff):
- **`to-extractor`** (needs thought, so `--yes` only after the user agrees): rewrites the agent into an `extractor` (`system:` becomes `instructions:` with any `{…}` refused and reported, `output_schema` becomes `fields`), and each `delegate "…{x}…" to A -> v` into `extract A from (…) -> v`, naming each variable the task interpolated as an input. When a delegate's task cannot be turned into named inputs mechanically (text around the variables that carries meaning), the option is not offered for that agent and the finding says why.
- Accepting with a reason, as for every rule.
- Once [phase 28](../28-evaluation-first/DESIGN.md) is built, a new extractor needs golden cases to pass `weave check`. The fix converts the old agent's golden cases whose expectations name JSON fields into extractor cases; when there are none, its result names the file to write and the `weave eval <Name> --init` command. "The script checks" below then means "checks apart from that gate".

**R11 LA21: the transcript carries untrusted text** (Security lens, from the audit, severity like LA01's legs) (D23). The audit computes, per workflow, the agents that **read untrusted content** (their tools' `ToolReach.untrusted`, `grounding: web`, or a `knowledge` index built from fetched content: the existing rules). Any agent that **receives the transcript** (`transcript: all`, the default) at a point where such an agent has already run in the same workflow (by statement order, through `call`, and in any later iteration of a loop) is treated as reading untrusted content for the trifecta. Consequences:
- If that agent also reaches private data and can send or act, LA01 (the trifecta) fires for it, and the finding names the path: "Researcher reads the web; its answers reach Sender through the shared transcript."
- LA21 itself is reported (info when it completes no trifecta, so the developer knows the path exists; the severity of LA01 when it does).
- Fix choices named in the finding: `transcript: none` on the receiving agent; put an `extractor` between them so only typed fields travel; split the agent.
- An internal node never receives the transcript (R3), so it never triggers LA21.
- The rule is listed in `docs/security/weave-audit.md` (`SecurityDocTest`) and mapped in `owasp-llm-top-10.md` (LLM01 prompt injection, indirect).

### Examples

**R12 The examples teach the new shape.**
- `LOOM_GUIDE.md` "Tasks: deterministic steps": `agent Intake` becomes `extractor Intake`, with `extract Intake from msg -> request`.
- Starter `08-extract-and-validate` is rewritten around `extractor Invoice` with `optional: [total]` and `alt (invoice.total == missing)` instead of "use 0 when the amount is missing". Its tests, README and diagram are regenerated.
- `LOOM_GUIDE.md` gains "Extracting fields: `extractor` and `extract`" next to the classifier section, and "Internal nodes" stating R7's contract.

## 4. Java API

| Type | Change |
|---|---|
| `ast.ExtractorDef` (new, public, final) | name, model, instructions or prompt ref, `SchemaDef` fields, optional names, pii mode, temperature, timeout, budget; line numbers |
| `ast.ExtractStmt` (new, public, final) | extractor name, inputs (one unnamed or a list of named `ValueExpr`), variable, retry, backoff, timeout, budget, on_failure |
| `ast.LoomScript` | `getExtractors()`; `merge` carries them (the F01 lesson: a test asserts `merge` keeps every new top-level list) |
| `ast.AgentDef` | `getTranscriptMode()` returning `TranscriptMode` (`ALL`, `OWN`, `NONE`); `isSharedPromptExcluded()` |
| `execution.ExtractRunner` (new, package-private) | runs an `ExtractStmt` |
| `memory.TranscriptAccumulationEngine` | honours `TranscriptMode` in `assembleContext` |
| `TaskContext`, `Task` | unchanged |

Records where the type is a value (`TranscriptMode` is an enum; the inputs of an `ExtractStmt` are a `List.copyOf`). All new public types and methods have Javadoc.

## 5. Compatibility

| Change | Existing users |
|---|---|
| `extractor`, `extract`, `missing`, `transcript:`, `shared_prompt: none` | New and contextual: names already used in scripts still parse (proved on every `.loom` in the repository). |
| Default transcript behaviour | Unchanged (`all`). |
| LP08, LP09 | New findings in the Prompts lens, which is on by default. Advisory. |
| LA21 | A new audit finding; it can make LA01 fire for an agent that did not have it before. A CI job with `weave audit --fail-on high` may start failing for a real trifecta it was missing. Listed in the release notes. |
| `MemoryEngine` implementations | Interface unchanged; a custom engine that ignores `TranscriptMode` keeps today's behaviour and the Javadoc says so. |
| Starter 08, the guide's Intake example | Rewritten. A user who copied the old shape keeps a working script (and gets LP09). |

`docs/API_COMPATIBILITY.md` records the new public types and the new `AgentDef` accessors.

## 6. Security notes

- R3 is a security property as much as a quality one: an extractor cannot be steered by earlier agents' text, because it never sees it; its inputs are marked as data.
- R4: the runtime never fills a required field it did not get. A missing value fails closed.
- R6 refuses `{…}` in instructions so a variable cannot slip from the data channel into the instruction channel (the same reason LP01 exists for agents).
- R11 fails closed: an untrusted reader earlier in the workflow taints every later receiver of the transcript, whatever the prompts say, as tool results already do.
- Trace and audit carry names and counts, never inputs, values or prompts.

## 7. Open questions

1. R4: is `missing` the right word for an absent optional field in conditions, or should a condition test `invoice.total is missing`? Proposed: `== missing`, matching how labels are compared today.
2. R10: is ~300 tokens the right "short prompt" bound for LP09 (it matches D6's bound for phase 25)? Proposed: yes, one setting `prompts.short-prompt-tokens` shared by both rules.
3. R11: should LA21 follow the transcript through a `handoff` into another workflow's agents? Proposed: yes; a handoff passes the transcript.

## 8. Documentation affected

`LOOM_GUIDE.md` (the extractor section, internal nodes, `transcript:` and `shared_prompt: none` under "Bounding Loom's shared transcript" and "A prompt every agent starts with", the Intake example), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md`, `docs/guide/05-test-agents-with-caps.md` (an extractor is tested on its own), the tutorial (§9), starter 08 and the starters README, `docs/review/rules/` (regenerated: LP08, LP09, LA21), `docs/security/weave-audit.md`, `docs/security/owasp-llm-top-10.md`, `docs/security/secure-by-construction.md` (internal nodes and the transcript), the `llm4j-workflow-guide` skill (when to use an extractor; "a classification problem is not a generation problem" gains its sibling "an extraction problem is not a conversation"), the `llm4j-harness-design` skill (principles §3: pass data, not instructions; the transcript as a channel), the `llm4j-eval-design` skill (an extractor is tested on its own), the VS Code grammar, snippets and `CHANGELOG.md`, `graph-render` (`extract` kind), eval4j's README and Javadoc for the new assertions, `docs/API_COMPATIBILITY.md`, `llms.txt`, and the next release's notes.

## 9. Tutorial

| Chapter | Change |
|---|---|
| [3. Agents](../../tutorial/03-agents.md) | New section **"Agents that talk to people, and nodes that work for the workflow"**: a page in plain words on the difference (an agent talks and reasons; an internal node does one job on what it is handed and remembers nothing), with a diagram, and a pointer to chapters 5 and 9. "A prompt every agent starts with" shows `shared_prompt: none` and says an internal node never gets it. |
| [5. Structured answers and failure](../../tutorial/05-structured-answers-and-failure.md) | New section **"Extractors: fields from text, with nothing else"** after "Structured answers": the declaration, `extract … from`, `optional:` and `missing`, what the extractor does not see, and why. "Putting it together: extract, then validate" is rewritten around the new starter 08. "Try it yourself" gains an exercise: turn an agent with an `output_schema` into an extractor and watch LP09 go away. |
| [7. Knowledge and memory](../../tutorial/07-knowledge-and-memory.md) | A section **"Who sees what was said before"**: the shared transcript, that every agent gets it by default, `transcript: none | own | all`, and LP08. |
| [9. Safety and cost](../../tutorial/09-safety-and-cost.md) | "The lethal trifecta" gains a paragraph and a diagram on LA21: untrusted text travelling through the transcript, and the three fixes. |
| [14. Cookbook](../../tutorial/14-cookbook.md) | Recipe **"Pull fields out of text"**; the cheat sheet gains `extractor`, `extract`, `optional`, `missing`, `transcript:`, `shared_prompt: none`; "mistakes and their fixes" gains LP08, LP09 and LA21. |
| [README](../../tutorial/README.md) | Chapter 5's row names extractors; chapter 7's row names the transcript. |

Every new `loom` block is checked by `TutorialExamplesTest`; the web page is regenerated with `bash scripts/make-tutorial-html.sh` (`GuideTutorialPageTest`).
