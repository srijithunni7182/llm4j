# Phase 17 design: honest results (no wrong result that looks right)

Status: **draft for review**. Layer: Loom runtime (`ai-agent4j-loom`), `ai-agent4j` (budget, Google provider), eval4j (one contract), the `weave` CLI.
Part of the [field findings program](../FIELD_FINDINGS.md); read its §2 (evidence labels) and §5 (decisions D1 to D4, answered 2026-10-29: D1 b, D2 c, D3 c, D4 a) first. Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md).
Findings owned: **F01 to F09**.

## 1. Problem

A developer finished a build believing three things that were false: that the safety rules in `shared_prompt` were in force, that the articles were complete, and that the evaluation had judged real
text. In each case a tool said something that sounded fine (a warning dismissed as a false positive, "Workflow completed successfully", a judge verdict) and the truth was only found by a person who
suspected it and built an experiment. The experiments were good; the product should have made them unnecessary.

The common shape: **a limit or a rule applied quietly, or failed quietly, and the run still ended in success.** This phase makes each of those loud, in the place the developer is already looking.

## 2. Goals and non-goals

Goals:

- If `shared_prompt` is declared, **every agent's request contains it**, or the script is refused. The developer can see what an agent will be told without sending anything (R1).
- A model call is never **quietly shortened**. When an answer is cut at an output limit, the run says so, naming the agents (R2).
- A grounded Gemini call with `recency:` works (R3), and sources reach the workflow or the run says why not (R7).
- A run that finished by using fallbacks says so, in its status and its summary (R4).
- The output of a workflow is **one defined thing**, shown by `weave run` and judged by `weave eval`; an empty output is never sent to a judge (R5).
- Usage is labelled **measured** or **estimated** wherever a cost is shown (R6).

Non-goals:

- Not a redesign of budgets, `on_failure`, grounding or `weave eval`; each keeps its model and syntax. Only what each does *silently* changes.
- Not the per-agent report, judge sampling or the evaluation skill (Phase 19), nor the skill's preflight (Phase 20), though R1 and R2 provide checks the preflight will use.
- Not a fix for bare-word variable replacement (F50, Phase 22; it needs decision D3).

## 3. Requirements

Ids are stable; tests and acceptance criteria point at them.

### 3.1 `shared_prompt` really applies, or the script does not run (F01, F02)

**R1.1 Reproduce before design.** F01 is marked **X**: the code reads correctly (`AgentPrompts.resolveSystemPrompt` prepends the shared text, byte for byte, to every agent's own prompt, and `SharedPromptTest`
shows it reaching a model request in a harness), yet a real run ignored a canary. [VERIFICATION §0](VERIFICATION.md) reproduces it end to end through the real CLI against a fake provider that records each
request. The design below holds for every outcome; what changes is whether R1.3 has work to do.

**R1.2 One composition point.** The system-level text an agent sends is built in **one** method. No code path (parallel branches, `broadcast`, `handoff`, loops, grounded or `expecting` calls, tasks that call
agents, retries, fallbacks, resumed runs) composes its own. A test enumerates the paths that send a model request and asserts each goes through that method (§4 of the TEST_STRATEGY).

**R1.3 Provider adapters carry it.** For each provider adapter (Gemini `systemInstruction`, Anthropic `system`, OpenAI-compatible and Responses `system`/`developer`, Ollama, Sarvam), the text composed in R1.2 appears in
the request body in the field that adapter uses for system instructions. A recorded-request contract test per adapter checks it. Leading hypotheses to rule out, in this order: (H1) one execution path
(grounding, `expecting`, parallel, or a `prompt:`-file agent) builds its system text without the shared part; (H2) an adapter drops or reorders a leading system block (for example, merging it with a later
block or ignoring it when the agent's own prompt is long); (H3) the jar in the session predated the feature; (H4) an instruction placed first is out-weighed by later instructions (a model behaviour, not a bug, but
then the documentation must say where the text goes and the canary in the preflight must test the real ordering).

**R1.4 (dropped by decision D4, 2026-10-29).** The owner chose to keep today's behaviour: a `shared_prompt` that cannot be resolved (missing file, no catalog) logs a warning and agents run without it. This is the opposite of the project's rule that a missing safety setting denies, and the owner knowingly accepted it. To keep the failure from being quiet, R1.6 is extended: `weave explain` and `weave check` state, per agent, whether the shared prompt is **applied** or **not applied (and why)**, and `weave run` prints the warning in the run summary, not only in the log. The decision can be revisited at 7.0.
refuses to start. Today `AgentPrompts.sharedPrompt()` logs a warning and "agents run without it". That line is the opposite of the project's own rule that a missing safety setting denies, and is removed.

**R1.5 The unused-prompt warning is right (F02).** The `prompt files` check counts a prompt named by `shared_prompt: prompt "id"` (and by `id@vN`) as used. It reproduces today on the minimal script in
[VERIFICATION §0](VERIFICATION.md) even with the documented syntax, so its logic (`ScriptValidator.checkPromptFolder`, which already adds the shared reference to its `used` set) must be traced to the cause. A warning that
tests can show to be wrong is removed or fixed, never left as noise, because the session shows how a wrong warning teaches people to ignore the right one.

**R1.6 See what an agent is told.** `weave explain` gains a section **"What each agent is told"**: for each agent, the ordered parts of its system text (shared prompt: file or inline, with its size; the
agent's prompt file or `system:`; skills; persona), without the text itself unless `--show-text` is given. The VS Code Review panel and the graph's agent node can show the same list. This is how a developer
confirms R1 without a canary.

**R1.7 The record is corrected.** `docs/RELEASE_NOTES_6_1_0.md` (known issues), the shared-prompt section of `LOOM_GUIDE.md`, the guide chapter and the skill are corrected to what R1.1 finds, in the same pull
request as the fix, with the dates. If R1.1 shows the code was never wrong (H4), the note says what the documentation failed to say.

### 3.2 No silent output limit (F03, F08)

**R2.1 The limit is explicit.** The effective output limit of every model call is decided in one place and is one of: the agent's `per_call`; the model's known maximum output (from the model catalogue, with a
documented default when the model is unknown); or what the active budget still affords, whichever is smallest. **A fixed 1,024 is no longer a default** (decision D1, answered **b**). `BudgetSet.reserve` takes the model's maximum as its `wanted` when the agent gave none.

**R2.2 `weave check` and `weave review` say what the limit is.** For an agent in a script with an active budget, one note names the effective limit and where it comes from ("output limit 8,192 tokens: the
model's maximum; the run budget would allow more"). A limit that is smaller than the model's maximum because of the budget is reported as such.

**R2.3 A cut answer is reported.** When a provider reports that an answer stopped at the limit (`finish_reason` `length`, Gemini `MAX_TOKENS`, Anthropic `max_tokens`), the model-call event carries
`truncated: true`; the run summary prints **"N answers were cut at the output limit: Writer (2), Editor (1)"**; the trace shows it; `weave test` can assert on it. A workflow can test it
(`if {writer_truncated}` is **not** specified here; see §6).

**R2.4 A budget-cut run is still exit code 3.** Nothing about R2.1 changes what happens when the budget itself is exhausted ([Runs.java](../../../src/loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/cli/Runs.java): code 3).

**R2.5 F08 is re-read after R2.1.** Completion counts clustered at about 1,020 are explained by the limit in F03 (the answers really were that long, because they were cut). The separate message "some usage was
estimated" is R6.

### 3.3 Grounding that works with `recency:` (F04)

**R3.1** Times sent to a provider have **whole-second precision**, in the form that provider documents. `GoogleGrounding` builds `timeRangeFilter` from `Instant.now()`, whose string form carries fractional digits
(its class comment says a recency request "was accepted", which was true on a clock with millisecond precision and is not on a platform whose clock has microseconds or nanoseconds).
**R3.2** Every provider adapter that sends a timestamp or a date (grounding recency, any `since`/`before` filter) is audited, and each is covered by a test with a clock that returns nanoseconds.
**R3.3** A fixture recorded from the **real** Gemini API with a recency request is added (the recording tool exists: `ProviderSmokeIntegrationTest`, opt-in, under a spend cap) and replaces the claim in the class comment.
**R3.4** A 400 from a provider names the field and the value Loom sent, in the error message the developer sees (today the cause is only in the provider's text).

### 3.4 A degraded run says so (F05)

**R4.1 Definition.** A run is **degraded** if it completed (reached its end, or `halt` with a saved effect as the script chose) and at least one step ran its `on_failure` handler, or any `delegate` returned
via a fallback. The set of such steps is recorded in the journal.
**R4.2 Reporting.** The run summary prints `Degraded: Researcher, StyleScout, Editor took their fallback (3 of 8 model steps)`. The end-of-run event, the journal's final record, the Fabric run page and `weave why` carry
the list. A run in which **every** model step used a fallback says "all model steps failed; the result is entirely from fallbacks" and is a **failure** (exit code 1), not a degraded success: a result with no model
contribution is not a completion. (Whether that threshold should be configurable is an open question, §6.)
**R4.3 Exit code (D2).** By default the exit code of a degraded run stays **0**, so no existing script changes meaning. An option `--exit-degraded` (and a script-level setting) makes it **7**. `weave gate` and
`weave eval` treat degraded as a warning by default and as a failure with the same option. Code 7 is added to the exit-code table in `weave man run` and the guide, and to `Runs.Result`'s documentation.
**R4.4 Testable.** `weave test` can assert `degraded: true` and `fallbacks: [Researcher]`.
**R4.5 Readable by the script (optional, subject to review).** A read-only `_fallbacks` (the count) is available to a workflow's final `note`, so a script can say what the saved artefact is made of. If this is
not added, scripts keep using their own flags as `article-flow` did.

### 3.5 One defined output, and `weave eval` never judges nothing (F06, F07)

**R5.1 Reproduce before design.** F06 has no known cause. The leading hypothesis (**I**): `weave eval` takes a workflow's *returned value* as the output; `article-flow` ended with `note` and `halt` and returned
nothing, so the judge was handed an empty string; `weave run` showing only `note` lines (F07) is the same mismatch seen from the other side. [VERIFICATION §0](VERIFICATION.md) tests this with a fake
provider before anything is designed.
**R5.2 The contract.** A workflow's **output** is defined once, in one method used by `weave run`, `weave eval`, `weave test`, `LoomHost` and the Java twin: the value of its `return` if it has one; otherwise
the text of its **last `note`**; otherwise empty. It is documented in the guide next to `return` and `note`.
**R5.3 `weave run` prints it.** After the run summary, `weave run` prints the output under a heading (`Result:`), unless `--quiet` or the output is empty (then it says "the workflow returned nothing").
**R5.4 Empty is an error for a judge.** `weave eval` (and the eval4j bridge it uses) never sends an empty output to a judge. A case whose output is empty is reported as **ERROR: the workflow produced no output**
with the reason (for example "ended with `halt` and no `return`"), the evaluation exits non-zero, and no judge tokens are spent on it.
**R5.5 Outputs are kept.** The output that was judged is stored with the case in the report (detail in Phase 19, R25).

### 3.6 Usage is measured or labelled (F08)

**R6.1** Where a provider's response carries usage (Gemini `usageMetadata`, Anthropic `usage`, OpenAI `usage`), it is read and used. A recorded-response contract test per provider proves it.
**R6.2** Where it does not, the figure is an **estimate** and every place that shows a cost or a token count says so: the run summary ("Cost $0.016 (estimated for Researcher, StyleScout)"), the events (`usage_source: measured | estimated`), the spend table.
**R6.3** An estimate is never used to *clear* a budget silently: when usage is estimated, the budget check uses the larger of the estimate and the reserved limit.

### 3.7 Citations reach the workflow, or the run says why not (F09)

**R7.1 Reproduce before design.** [VERIFICATION §0](VERIFICATION.md): a recorded real Gemini grounded response (with the 2026-10-22 fixture, and a new one taken without `recency:`) is run through (a) a plain `delegate`, (b) the same
`delegate` inside `in parallel`, (c) with `expecting`. The variable is `{<name>_citations}` ([LOOM_GUIDE](../../../src/loom/ai-agent4j-loom/LOOM_GUIDE.md), "grounding"). Hypotheses: (H1) the variable is not
exported from a parallel branch; (H2) the Gemini adapter drops supports whose offsets do not fit the answer (it documents dropping a segment that does not fit); (H3) the model answered without searching
(`searches: 0`); (H4) the variable's name was not what the script used.
**R7.2** Whatever the cause, a grounded agent that returns **no citations** produces a visible note in the run summary ("Researcher was grounded but returned no sources; searches: 0") and a trace field, so a script's fallback
for missing sources is never the only signal.
**R7.3** `weave check` warns when a script reads `{x_citations}` for an agent `x` that is not grounded or whose branch variable is not exported (if H1 holds).

## 4. Compatibility and opt-in

| Change | Existing scripts |
|---|---|
| R1.4 (dropped, D4) | None. An unresolved shared prompt still only warns; the warning now also appears in the run summary and in `weave explain`. |
| R2.1 (no 1,024 default) | Answers that were clipped become whole; token use rises to what the agent really writes, still inside the budget. Scripts that relied on the clipping to save cost must set `per_call`. Listed in the release notes. |
| R3 | Pure fix. |
| R4 | Summary lines and a new optional exit code. No exit code changes unless asked. |
| R5.3 | `weave run` prints more. Scripts that parse its output are warned in the release notes; `--quiet` restores it. |
| R6 | Labels only; a budget check may become more conservative when usage is estimated. |

`docs/API_COMPATIBILITY.md` gets a row for each. New public Java API (a `RunSummary.degraded()`, `ModelCallEvent.truncated()`) follows the rule "when you add a component to a record, keep its old constructor".

## 5. Security notes

- R1 is a security property: **a rule meant to constrain the model must not silently not apply.** It joins the fail-closed list in `docs/security/secure-by-construction.md`. A guard test fails if the guard is removed (a canary through a fake provider).
- R1.6 prints sizes, not text, unless asked, because prompts can hold business rules.
- R5.4 and R2.3 add no content to telemetry: counts and names only, as the observability design requires.

## 6. Open questions

1. R4.2's "every model step used a fallback is a failure": should the threshold (all) be configurable, for example "more than half"?
2. R2.3: should a script be able to branch on `truncated` (`if {writer} is truncated`)? Proposed: not in this phase; a language change deserves its own design.
3. R4.5: is `_fallbacks` worth a new built-in, or is the run summary enough?
4. R1.6: should the text be shown at all (`--show-text`), given the secrets-in-prompts risk? Proposed: yes, off by default, never in the trace.

## 7. Documentation affected

Release notes (the 6.2.0 section; D9: no 6.1.1), `LOOM_GUIDE.md` (`shared_prompt`, budgets and `per_call`, `return`/`note` and the output contract, exit codes, grounding and `_citations`), `weave man run`/`eval`/`explain`
(generated), `docs/API_COMPATIBILITY.md`, `docs/security/secure-by-construction.md`, the `llm4j-workflow-guide` skill (budgets, the preflight hook), `docs/guide/` chapters 6 and 9, `llms.txt`, the Loom wiki.
