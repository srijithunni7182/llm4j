# Phase 9 design: the review engine (`weave review`)

Status: **draft for review**. Layer: Loom (`ai-agent4j-loom`: new package `review`, CLI command `weave review`). Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md), [roadmap](../README.md).
Builds on `weave check` (`ScriptValidator`), `weave audit` (`SecurityAudit`, `Finding`, `Severity`, `Owasp`), `ContextAudit`, `weave graph` (`GraphBuilder`) and the price tables (`Runs.pricesFile`).

## 1. Problem

A developer describes a workflow and gets a `.loom` file and prompts. The file may be valid and still be wrong in ways that cost money, leak data or fail at 3 a.m.:
a loop with no bound, a classification step on the most expensive model, an agent that can send mail with no approval, a branch on free text, a prompt that pastes a customer's message into its instructions.

Loom already knows how to find several of these, but the knowledge is split across commands that report in different shapes:

| Command | Finds | Shape | Gaps for this problem |
|---|---|---|---|
| `weave check` | Parse and validation problems | `Problem(line, construct, message, severity ERROR/WARNING)`; JSON `diagnostics[]` | No rule id, no fix, no node id |
| `weave audit` | Security (LA01 to LA20, OWASP-mapped) | `Finding(rule, severity HIGH..INFO, owasp, where, line, title, risk, fix)`; fix is prose | Security only; no machine-applicable fix |
| `weave graph` | Structure | Nodes/edges with `SourceRef` | Not connected to findings |
| runtime | Token estimates before a call (about 4 chars/token + 10 %) | internal | **No static cost estimate for a workflow**; no cost advice |

The developer must know all of these exist, run them separately, and read three formats. Nothing covers cost, reliability or prompt quality statically, and nothing offers a fix to apply.

## 2. Goals and non-goals

Goals:

- **One command, one ranked list**: `weave review script.loom` runs every lens and prints findings most-severe first, with a summary card (counts, cost range, trifecta state).
- **Nine lenses on by default**: *security* (today's audit), *validity* (today's check), *cost and performance*, *reliability*, *prompts*, *observability*, *durability*, *testability*, *privacy and compliance*; and **four optional lenses**, off until asked: *model governance*, *maintainability*, *human oversight*, *blast radius* (§6.0, §6.15). Each rule has a stable id, an explanation page, and a precision target.
- **A static cost estimate**: typical and worst-case spend per workflow and per step, from the graph, the prices and stated assumptions; unbounded cost is reported as unbounded.
- **Machine-applicable fixes** where a fix is mechanical (`--fix`, or one click in the editor), with the rest explained in a sentence and a pointer.
- **One finding model** that the CLI, the editor ([phase 10](../10-editor-experience/DESIGN.md)), the generator ([phase 12](../12-generate/DESIGN.md)), CI and Fabric all use.
- **Fast**: a warm review of a 300-line script, no I/O beyond reading files, in under 1 s (the editor needs this).
- **No new behaviour at run time**: review reads scripts; it never runs a model, a tool or a task, and loads no user class (the audit's existing rule).

Non-goals:

- Not a replacement for `weave audit`/`weave check`: both keep working with unchanged output and exit codes. `review` is built over the same code, and `audit`'s findings keep their `LAnn` ids.
- Not a judge of prompt *quality* by calling a model. Prompt rules are static heuristics with a precision target. Judging prompt quality with a model is `weave eval`'s job.
- Not a proof of safety. The report says what it cannot know (§9).
- Not automatic edits without consent: `--fix` applies only fixes classed `safe`; everything else is shown, never applied silently.

## 3. The finding model (R1 to R5)

One immutable record replaces the three shapes at the edge (the existing types stay; adapters map them in).

```java
public record Finding(
        String rule,            // stable id: LA01.., LV01.., LC01.., LR01.., LP01..
        Lens lens,              // SECURITY | VALIDITY | COST | RELIABILITY | PROMPTS | OBSERVABILITY | DURABILITY | TESTABILITY | PRIVACY
                                // optional: GOVERNANCE | MAINTAINABILITY | OVERSIGHT | BLAST_RADIUS
        Severity severity,      // HIGH | MEDIUM | LOW | INFO  (the existing audit scale)
        Location where,         // file, line, column range, and the graph node id when there is one
        String title,           // one line: what is wrong
        String why,             // plain words: why it matters (may name numbers)
        String fixHint,         // plain words: what to change (always present)
        List<Owasp> owasp,      // as today; empty outside the security lens
        Optional<Fix> fix,      // machine-applicable change, when there is one
        Optional<CostImpact> costImpact) {}   // for cost findings: estimated saving or exposure

public record Location(Path file, int line, int column, int endLine, int endColumn, String nodeId) {}

public record Fix(
        FixSafety safety,        // SAFE: no behaviour beyond what the title says; REVIEW: needs a human choice
        String label,            // "Add retry 2 backoff 2s timeout 60s"
        List<TextEdit> edits) {} // ordered, non-overlapping, across one or more files

public record TextEdit(Path file, int startLine, int startColumn, int endLine, int endColumn, String newText) {}
```

- **R1** Every finding has a stable `rule`, a `lens`, a `severity`, a `title`, a `why` and a `fixHint`. A rule id never changes meaning; a retired id is never reused.
- **R2** `Location` carries the `nodeId` of the graph node the finding is about whenever the finding is about a statement (the same ids `weave graph --format json` emits), so the editor can link a finding to its node.
- **R3** The existing `Finding` (audit), `ScriptValidator.Problem` and `graph.Diagnostic` are adapted into the new record by adapters; **their types, output and exit codes do not change**. A validity problem without a rule gets `LV00` ("unclassified") until it is classified (§6.2).
- **R4** A `Fix` is a list of edits against the script text as read. Applying it must produce a script that still passes `weave check`; the engine verifies this before offering it (§8.2).
- **R5** The model is serialised as JSON (`weave review --format json`, version 1), one object, documented and stable: `{version, file, summary, findings[], cost, assumptions[], suppressed[], notes[]}`. Adding fields is allowed; removing or renaming is a new version.

## 4. The command

```
weave review <script.loom> [options]
  --format text|md|json|sarif     text (default, on a terminal), md (a report), json (tools), sarif (code scanning)
  --lens security,cost,...        run exactly these lenses; `all` adds the optional ones; `+oversight` adds one to the defaults (default: the nine default lenses)
  --min-severity high|medium|low|info   show at least this (default: info)
  --fail-on high|medium|low|info|none   exit 1 when a finding is at least this severe (default: none: review is advisory, R49; CI sets it)
  --fix                           apply every SAFE fix, then review again and print what changed
  --fix --dry-run                 print the diff, change nothing
  --explain <RULE>                the rule's page: what it checks, an example that fires, one that does not, the fix, known false positives
  --rules <file>                  rule settings and suppressions (default: review.properties beside the script)
  --prices <file>, --windows <file>   as in weave run
  --loot <file>, --no-env         as in weave check / audit
  --assume key=value              override a cost assumption (repeatable)
  --calibrate <run-dir>           compare the cost estimate with what a journaled run really spent (§5.5)
```

- **R6** Exit codes: `0` (always, unless `--fail-on` or a project `fail-on =` is set) or nothing at or above `--fail-on`; `1` a finding at or above it; `2` the script could not be read or parsed (the existing convention). `--fix` exits by what remains after fixing.
- **R7** `weave audit` and `weave check` are unchanged. `weave review` runs both internally, so `review` is a superset: no finding they produce is missing from `review` (a test pins this).
- **R8** SARIF output (version 2.1.0) so the same findings appear in GitHub code scanning. Rule ids map to SARIF rule ids; fixes map to SARIF `fixes`.
- **R9** The text report ends with a summary card:

```
TicketTriage   main.loom         2 high  3 medium  1 low
Cost       typical $0.004 / run     worst case $0.06 (bounded by 3 loops, 2 retries)     [assumptions: weave review --explain cost]
Security   trifecta: Replier reads untrusted text and customer data and can send mail (LA01)
Lenses     9 on · 4 optional off (weave review --lens all) · 2 rules not evaluated (declare data.regions, journal)
Next       weave review --fix  applies 2 safe fixes     weave review --explain LR03
```

## 5. The cost estimator (R10 to R17)

### 5.1 What is estimated

For each workflow, an interval `[typical, worst]` in tokens and money, plus the same per step, computed from the graph (`GraphBuilder`) and the price table. A step that calls a model
costs `tokensIn * priceIn + tokensOut * priceOut` for the agent's model.

- **tokensIn** for a step = the agent's system prompt + the step's task text + the *assumed size of interpolated variables* + for a tool-using agent the assumed context growth per tool round.
  Prompt text is measured from the actual prompt files (the same 4 chars/token + 10 % the runtime uses, so estimate and runtime guard agree).
- **tokensOut** = the agent's `max_tokens` when set; else the assumption `assume.output.tokens` (default 600); a step with an `output_schema` uses the schema's sampled size.
- **Tool-using agents** (ReAct) = rounds x (call + growing context); rounds = the agent's declared maximum steps, typical = `assume.react.rounds` (default 3).

### 5.2 How control flow multiplies

| Construct | typical | worst |
|---|---|---|
| `alt`/`else` | the **most expensive** branch weighted by `assume.branch.share` (default: each branch equally likely; the report shows each branch's own cost) | the most expensive branch |
| `loop until ... max N` | `min(N, assume.loop.iterations)` (default 3) | `N` |
| `loop until` with no `max` | `assume.loop.iterations` | **unbounded** (see R12) |
| `for each` / `parallel for each` | `assume.foreach.items` (default 5) | `assume.foreach.max` (default 50) unless the source list has a declared bound |
| `broadcast` to k agents | sum of the k | sum of the k |
| `retry N` | 1 attempt | `N + 1` attempts |
| `rewind ... at most M times` | 0 rewinds | `M` re-executions of the span |
| `call` of a sub-workflow | its typical | its worst; recursion is flagged and counted once |
| `sleep`, `human_prompt`, `run` (task) | 0 tokens | 0 tokens |
| compaction (`context` block) | an assumed extra call when the window fills | same |

- **R10** `weave review` prints and serialises `cost.typical` and `cost.worst` per workflow and per step (tokens and money in the currency of the price table) and the **list of assumptions used**, each with its default and the flag that overrides it.
- **R11** If a `budget` exists at script, agent or step level, the worst case reported is `min(computed, budget)` with a note "capped by the budget at $X"; the uncapped figure is also shown. A budget that is below the computed *typical* cost raises `LC04`.
- **R12** A worst case that depends on an unbounded construct is reported as `unbounded` (never a large made-up number). If a budget caps it, the cap is the reported worst case and the finding `LR03` still says the loop has no `max`.
- **R13** A step whose model has no price in the table reports `unpriced` for money and still reports tokens; the summary says which models are unpriced (it never assumes zero).
- **R14** The price table, the window catalog and the assumptions used are printed in the report footer (`prices: prices.properties, 2026-09-30`), so two people get the same number from the same inputs.
- **R15** The estimator never calls a model or counts tokens with a provider; it is pure and deterministic.

### 5.2a Time and effects (R35, R36)

The same walk also estimates how long a run takes and how many effects it can cause; the rules and their defaults are in §6.15 (R35 for time, R36 for effects). Both appear in the cost block of the JSON and in the summary card (`Time typical 6 s · worst 41 s (+ waits: 1 person)`; `Effects worst 1 send`).

### 5.3 Defaults and overrides

Assumptions are keys with documented defaults; the script's folder may set them in `review.properties` (`assume.foreach.items = 20`) and the command line overrides them. Defaults live in one table
in code with the text shown by `--explain cost`.

### 5.4 Cost advice (LC rules, §6.3)

Advice is derived from the same walk: which step dominates, which agent is on a model larger than its job needs, which loop re-sends a growing context.
Advice never changes the model by itself (`REVIEW` fixes only; §8).

### 5.5 Calibration (R16, R17)

- **R16** `weave review --calibrate <run-dir>` reads a journaled run (its trace/usage records) and prints, per step, estimated typical versus actual tokens and money, and an overall ratio.
  It is the way to find out the defaults are wrong for *this* workflow and to set `review.properties` from evidence ("this loop averages 2.1 iterations, not 3").
- **R17** Accuracy is a measured property, not a promise: the benchmark corpus (§10) includes recorded runs; the acceptance criterion is that the *typical* estimate is within 2x of actual for at least 80 % of corpus workflows and the *worst* estimate is never below the actual (§VERIFICATION A4). The report always says "estimate".

## 6. The rules

Each rule is a small class implementing `ReviewRule` (§7). Ids are stable; severities are defaults that `review.properties` may lower or raise per project.
"Fix" says what the machine-applicable fix is: **S** = `SAFE`, **R** = `REVIEW`, **-** = prose only.

### 6.0 Lenses at a glance

| Lens | Rule ids | Default | Question it answers | Main inputs beyond the script |
|---|---|---|---|---|
| Security | LA01 to LA20 | on | What can an attacker or a mistake make this do? | tool `reach`, loot file |
| Validity | LV | on | Is it well-formed and are the names right? | prompt files, env |
| Cost and performance | LC | on | What will it cost and how long will it take, and where is it wasteful? | prices, windows, assumptions |
| Reliability | LR | on | What happens when things go wrong? | tool `reach` |
| Prompts | LP | on | Are the prompts safe and usable? | prompt files |
| Observability | LO | on | Can people understand it once it is live? | catalogs, Fabric rules file |
| Durability | LD | on | Does it survive a crash or a retry without repeating anything? | project journal fact |
| Testability | LT | on | Is it tested, and where is it not? | test files, golden data, coverage report |
| Privacy and compliance | LY | on | Where does personal data go, and who decides about people? | declared data facts |
| Model governance | LG | **optional** | Are models current, pinned and replaceable? | model catalog |
| Maintainability | LM | **optional** | Will the next person understand and change it? | none |
| Human oversight | LH | **optional** | Is the person in the loop helped or burdened? | the cost walk |
| Blast radius | LB | **optional** | How much can one bad run do? | effects estimate, schedule |

**Form.** Every rule below except `LA` and `LV` is a *data rule* in the bundled `core` rule pack, evaluated over a small fact model (§6.16, R57). The severity, title and fix columns in the tables are what the rule files say; adding or changing a rule is a file change, not a Java change.

### 6.1 Security lens: `LA01` to `LA20`

Unchanged: `SecurityAudit`'s rules, mapped through the adapter (R3). New rules go in the LA namespace only when they are about security (none are added in this phase).

### 6.2 Validity lens: `LV`

`ScriptValidator` problems get stable ids by classification of their message class (a table in code, covered by a test that every distinct message template maps to a rule, so a new
check cannot ship unclassified). Initial set:

| Id | Meaning | Default | Fix |
|---|---|---|---|
| LV00 | Unclassified problem (temporary bucket; a test fails if the table leaves any message here) | as raised | - |
| LV01 | Unknown agent/tool/task/workflow name | error | R: nearest name ("did you mean") |
| LV02 | Missing prompt file or secret | error | S: create the prompt file stub |
| LV03 | Result bound but never used | warning | S: remove the binding |
| LV04 | Condition reads a field the answer does not have | error | R: nearest field |
| LV05 | Human answer that changes nothing | warning | - |
| LV06 | Unreachable statement | warning | S: delete it |
| LV07 | Duplicate definition | error | - |

### 6.3 Cost lens: `LC`

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LC01 | A step is classification-shaped (agent has no tools, an enum/`choose` output, a short prompt) and its model is in the top price tier | MEDIUM | R: suggest a smaller model or `intentclassify`, with the estimated saving per run |
| LC02 | A loop re-sends a growing transcript and the agent has no `context` block | MEDIUM | R: add a `context` block with the default strategy |
| LC03 | `broadcast`/`parallel for each` fans out to k agents, each on a top-tier model | LOW | - |
| LC04 | Budget is below the computed typical cost (the run will be cut off in normal use) or worst case exceeds the budget by more than the configured factor | MEDIUM | - |
| LC05 | One step is more than the configured share (default 60 %) of the workflow's typical cost | INFO | - |
| LC06 | `retry` on an expensive step whose worst case with retries exceeds a configured amount | LOW | - |
| LC07 | A step the prompt describes as deterministic ("calculate", "check against the policy table", "validate the format") uses a model | INFO | - (points to a `run` task; heuristic, off by default) |
| LC08 | Two or more consecutive steps are data-independent (neither reads what the other binds, none is effect-ordered) and run sequentially although they could run in `parallel { }`; the saving on the critical path is shown | LOW | R: wrap them in `parallel { }` (budget and effect order are checked before offering) |
| LC09 | The worst-case time (excluding waits) exceeds `assume.latency.limit` when one is declared, or the sum of declared step timeouts exceeds the caller's limit | MEDIUM | - |
| LC10 | A workflow declared interactive (`workflow.X.interactive = true`) contains a `human_prompt` or `sleep` on its main path, or a model step on a large model with a typical time above the interactive target (default 8 s) | MEDIUM | - |

### 6.4 Reliability lens: `LR`

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LR01 | A `delegate` to an agent with tools has no `retry`/`timeout` | MEDIUM | S: add `retry 2 backoff 2s timeout 60s` (defaults from `review.properties`) |
| LR02 | The agent has write or send tools (by `reach:`) and the step has no `on_failure` | MEDIUM | R: add an `on_failure { ... human_prompt ... }` skeleton |
| LR03 | `loop until` has no `max N ... on_exhausted` | HIGH when the body calls a model, else MEDIUM | S: add `max 5 on_exhausted { ... }` (N from settings) |
| LR04 | `human_prompt` with no timeout/escalation on a workflow that is scheduled or unattended | LOW | - |
| LR05 | An `alt` or `loop until` reads a field of an agent that has no `output_schema` (a branch on free text) | MEDIUM | R: add an `output_schema` skeleton from the fields the condition reads |
| LR06 | An `alt` over an enum field does not cover every value and has no `else` | LOW | S: add an `else` that escalates to a human |
| LR07 | A tool with `reach: sends`/`writes` is called inside a loop or `for each` without `approve:` | MEDIUM | R: add `approve:` |
| LR08 | A scheduled workflow with a provider that has no `rate_limits` | LOW | S: add the default block |
| LR09 | `parallel`/`broadcast` branches write to the same variable name | MEDIUM | - |
| LR10 | A long-running agent loop with no `checkpoint` before an expensive or effectful span | INFO | - |

### 6.5 Prompts lens: `LP`

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LP01 | A variable bound from an untrusted source (a tool result, a human answer, a workflow input) is interpolated into an agent's `system:` prompt or prompt file instructions | HIGH if the agent has tools, else MEDIUM | R: move it to the task text and wrap it as delimited data |
| LP02 | A prompt that feeds a typed branch gives no output-format instruction and the agent has no `output_schema` | MEDIUM | - |
| LP03 | A prompt exceeds the configured size for its model class (default 2,000 tokens for a small model) | LOW | - |
| LP04 | A prompt or script text contains something shaped like a secret (key prefixes, long high-entropy strings) | HIGH | R: replace with `env.NAME` |
| LP05 | An agent that feeds a `decision` or an effect has no instruction on what to do when unsure or when the request is out of scope | INFO | - (heuristic, off by default) |
| LP06 | Two agents' prompts are near-duplicates (a shared persona would do) | INFO | R: extract a `persona` |

- **R18** A rule marked *heuristic* is off by default and says so in `--explain`. A rule that is on by default has a measured precision of at least 0.9 on the benchmark corpus (§10); a rule that cannot reach it is demoted to heuristic.
- **R19** Every rule has an `--explain` page (what it checks, a firing example, a non-firing example, the fix, known false positives, which settings tune it), generated from the rule's own metadata, so docs cannot drift from code.

### 6.6 Observability lens: `LO`

Can the people who run this workflow understand it once it is live? These rules read the same inputs Fabric uses (price and window catalogs, the `.inspect` rules file, the graph).

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LO01 | A scheduled or unattended workflow has no rules file for the incident thresholds it will be watched against (`<script>.inspect` or the project's) | MEDIUM if scheduled, else INFO | S: write a rules file with the documented defaults |
| LO02 | A model the script uses has no price entry or no context window in the catalogs, so Fabric cannot show cost or a context gauge for it | LOW | R: add the line to `prices.properties` / the windows file (value left for the developer) |
| LO03 | A workflow's branches end in different outcomes but nothing in the result or a `note` says which path ran, so a run in Fabric cannot be told apart from its siblings | INFO | R: add a `route:` field to the `return` or a `note` per branch |
| LO04 | A loop's `max` is larger than the loop-incident threshold in force (default 20), so a normal bound would already be reported as a loop | LOW | R: lower the bound or raise the threshold with a stated reason |

### 6.7 Durability lens: `LD`

Will a crash, a restart or a retry leave the world in a state the workflow can recover from, with nothing repeated?

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LD01 | The script pauses (`human_prompt`, `sleep`, `approve`, `decide`, a rate-limit wait) and the project declares no durable journal (`journal = none`, or none declared when `deploy` facts exist); without one a restart loses the pause | HIGH if `journal = none` is declared, INFO if undeclared | R: add the `--journal` setting to the project's run configuration |
| LD02 | A `class` tool (your own Java) with `reach: writes` or `sends` is called by an agent: only the six generic kinds and `run` tasks journal their effects, so a resumed or retried run can repeat it | HIGH | R: switch to a generic kind, move it behind a `run` task, or make the tool idempotent and say so with `idempotent: true` |
| LD03 | A `rewind` span contains an effectful step and the carried values differ from the first attempt, so the repeat is not "identical" and will be performed again | MEDIUM | R: move the effect after the check, or place the `checkpoint` after it |
| LD04 | A `retry` wraps a step with an effect that is not journaled (as LD02) | HIGH | R: as LD02 |

### 6.8 Testability lens: `LT`

Is the workflow testable, and is it tested? Uses phase 11's files and (when fresh) its coverage report; both are read, never produced, by review (R34).

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LT01 | No test file for the script | MEDIUM if it has effects or humans, else LOW | S: create `tests/<name>.test.yaml` with a smoke case |
| LT02 | An agent or classifier is named in no test's `models:` | LOW | R: add a case skeleton |
| LT03 | The last coverage report (script hash matches) lists the node or branch as unreached | LOW | R: the skeleton case from phase 11 R33 |
| LT04 | The script has `retry`, `on_failure`, `rewind` or `on_stopped` and no test has a `fail:` entry | MEDIUM | R: add a failure-injection case skeleton |
| LT05 | The script has `human_prompt`, `approve` or `decide` and no test supplies `humans:` | LOW | R: add a case skeleton |
| LT06 | An agent has no golden dataset entries (`eval/golden`) | LOW | S: `weave eval --init` equivalent for that agent |
| LT07 | A test names a step, agent or field that no longer exists (a stale test) | MEDIUM | R: nearest valid name |

### 6.9 Privacy and compliance lens: `LY`

Maps to the four xAI pillars the README describes (traceability, confidence and escalation, PII, bias). Rules that need project facts use `review.properties` (`data.personal`, `data.regions`), and are **inert** with a note when those are not declared (R38).

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LY01 | A value named or declared personal (default name patterns: email, phone, name, address, dob, ssn, card; or `data.personal`) reaches a hosted model or classifier with no `guard { pii: mask }` (or `block`) on that agent | MEDIUM; HIGH if the agent also has effects | S: add `guard { pii: mask }` |
| LY02 | `guard { pii: warn }` on an agent that has effects or feeds a decision (a warning nobody reads) | MEDIUM | S: change to `mask` |
| LY03 | An agent's provider is not in the declared `data.regions` allow-list | HIGH | - (a decision) |
| LY04 | A `decision`/`ladder` or an agent named as deciding about a person (approve, deny, hire, score) has no `bias:` guard | MEDIUM | S: add `bias: warn` |
| LY05 | An `intentclassify` has no `confidence at least ... else escalate` rule, so doubtful answers are routed as if certain | MEDIUM | S: add `confidence at least 0.8 else escalate` |
| LY06 | A prompt file contains an email, phone number or similar real-looking personal data in an example | MEDIUM | R: replace with a placeholder |

### 6.10 Cost and performance: rules LC08 to LC10 (added to the table in §6.3)

Time is estimated from the same walk as cost (§5.2a). Human waits and sleeps are reported separately from compute time and never added to it.

### 6.11 Optional lens: model governance `LG` (off by default)

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LG01 | A model name is not in the known-model catalog and is close to one that is (a likely typo) | LOW | R: nearest model |
| LG02 | A model is marked deprecated or retired in the catalog (inert when the catalog carries no such flag) | MEDIUM | R: the catalog's suggested replacement |
| LG03 | A critical-path agent has a single provider and no fallback where the agent configuration supports one (inert otherwise; confirm in §13) | LOW | R: add a fallback |
| LG04 | A floating alias (`-latest`) is used on a decision or an effectful flow, so behaviour can change without a commit | MEDIUM | R: pin the version |
| LG05 | Prompts are versioned for some agents (`prompts/x/v2.md`) and not others | INFO | R: version the rest |

### 6.12 Optional lens: maintainability `LM` (off by default)

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LM01 | An agent, tool, task, persona or workflow is defined and never referenced | LOW | S: delete it |
| LM02 | An inline `system:`/`prompt:` longer than the configured length (default 12 lines) in the script | INFO | R: extract to `prompts/<agent>.md` |
| LM03 | A workflow exceeds the configured size (default 60 statements) or nesting depth (default 5) | INFO | - |
| LM04 | Two agents or two workflow bodies are identical apart from names | INFO | R: extract a `persona` or a `call`-ed workflow |
| LM05 | The same literal threshold appears in many places (a confidence of 0.8 in six conditions) | INFO | - |

### 6.13 Optional lens: human oversight `LH` (off by default)

The earned-autonomy rules already in the security lens (a rung that climbs without a person, no `slip`; LA rules) stay there; `LH` adds the ones about the *person's* experience.

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LH01 | A `decision` is followed by an effect and has no `ladder` or `trust` (the agent acts with no record that it earned it) | MEDIUM | R: add a starting ladder at `watch` |
| LH02 | `approve:` is on a tool whose `reach` is `none` or `reads` (noise that trains people to click yes) | LOW | R: remove it or raise the tool's declared reach |
| LH03 | The worst-case number of questions put to a person in one run (from the graph, with loops and `for each`) exceeds the configured limit (default 10) | MEDIUM | - |
| LH04 | A `human_prompt` whose free-text answer is compared in a branch, with no constrained options | LOW | R: constrain the answer (an `options:` list or a yes/no) |

### 6.14 Optional lens: blast radius `LB` (off by default)

How much can one bad run do? Uses the effects estimate (§5.2a).

| Id | Fires when | Severity | Fix |
|---|---|---|---|
| LB01 | An effectful tool is reachable inside a loop or `for each` with no per-run cap (a `budget` calls limit or the tool kind's own limit) | MEDIUM | R: add the cap |
| LB02 | The worst-case number of effects per run (`sends` or `writes`) exceeds `blast.max-effects` (default 25) | MEDIUM | - |
| LB03 | A scheduled workflow's worst-case spend per day (per-run worst case x runs per day from the schedule) exceeds `blast.max-daily-cost`, or there is no `per day` budget | MEDIUM | R: add `budget { ... per day }` |

### 6.15 Lens mechanics (R33 to R40)

- **R33** **Nine lenses are on by default** (security, validity, cost and performance, reliability, prompts, observability, durability, testability, privacy and compliance); **four are optional and off** (governance, maintainability, oversight, blast radius). The default set is a project choice: `lenses = +governance,-testability` in `review.properties`, or `--lens` on the command line (`--lens all` for every lens, `--lens +oversight` to add one to the defaults, `--lens security,cost` for exactly those). The summary card states which lenses ran and how many are off ("4 optional lenses off: `weave review --lens all`").
- **R34** **Extra inputs, read-only and optional.** `ReviewInput` additionally reads: test files (`tests/*.test.yaml`), the eval dataset folder, the rules file for Fabric thresholds, and the last coverage report (`.weave/coverage.json`, written by `weave test --coverage --json`). A coverage report is used only when its recorded script hash equals the current script's; otherwise the rules that need it (LT03) are skipped with a note ("coverage report is stale"). Review never runs tests to get coverage.
- **R35** **Time estimate.** The cost block gains `time`: `typical`, `worst` and `criticalPath` (node ids), per workflow and per step, plus `waits` (human, sleep, rate-limit) listed separately. Per-call latency defaults by model tier (`assume.latency.small|standard|large`, `assume.latency.tool`), `parallel`/`broadcast`/`parallel for each` take the longest branch, `retry` adds its backoff, loops and `for each` multiply as in §5.2. Labelled an estimate; `--calibrate` also compares time.
- **R36** **Effects estimate.** The cost block gains `effects`: for each effectful tool kind (`sends`, `writes`), typical and worst counts per workflow, from the agents that hold the tool x tool rounds (typical `assume.react.rounds`, worst the agent's maximum) x the loop and `for each` multipliers; an unbounded construct makes the worst case `unbounded`, never a made-up number (as R12).
- **R37** The precision rule (R18) applies to every new rule. Rules whose detection is name- or text-based (LY01, LY06, LO03, LM02, LM04, LM05, LT06) are *heuristic*: they are listed as such by `--explain`, and a heuristic rule is on only if its lens is on **and** `heuristics = on` or the rule is named in `review.properties`; the personal-data name patterns of LY01 are configurable.
- **R38** **Declared facts, never guessed.** Some rules need facts the script cannot state (which fields are personal, which regions are allowed, whether a journal is configured, which workflows are user-facing: `workflow.Main.interactive = true`). Where the project has not declared them the rule is **inert** and listed under `notes` as "LY03 not evaluated: `data.regions` is not set", never reported as a pass.
- **R39** **Optional means opt-in, not absent.** An optional lens's rules have ids, pages (`--explain LB02`), tests and corpus cases like any other; they are simply not run unless enabled. Findings from an optional lens that the project enabled carry the same severities and fail `--fail-on` the same way.
- **R40** **De-duplication across lenses.** `LH` rules never repeat an `LA` finding about the same cause (R22): the engine keeps the `LA` one and links the new one as `related`. The same holds between LD02 and LA17-type findings about a tool that acts as the person, and between LT07 and phase 11's test-file validation.


### 6.16 Rules as data: rule packs over a small fact model (R57 to R74)

Rules change faster than code should. A periodic review of what Loom workflows get wrong should end in a **new file in a rule pack**, not a Java change, a release and a rebuilt extension. So the rules in §6 are not written as Java classes. They are **documents** that ask questions of a small, fixed **fact model**. Java computes the facts; data decides what to say about them. Anything the fact model cannot express stays Java, as before.

**The principle: no new language.** A rule is a YAML (or JSON) document with a closed vocabulary. There are no variables, no functions, no loops, no user-defined operators and no expression strings to parse. If a proposed rule needs one of those, the answer is a new *fact* or a Java rule (R73), never a richer rule syntax.

#### A rule, as data

```yaml
# review-rules/core/reliability/LR01.yaml
id: LR01
lens: reliability
severity: medium
about: step                       # the kind of thing it looks at; the rule runs once per such thing
when:
  all:
    - { field: kind, is: delegate }
    - { field: agent.hasTools, is: true }
    - { field: retry, is: false }
    - { field: timeout, is: false }
title: "{label} has a tool but no retry or timeout"
why: "A tool call can fail or hang. Without a retry and a timeout one slow call stalls the whole run."
fixHint: "Add a retry and a timeout to this step."
fix:
  safety: safe
  label: "Add retry and timeout"
  insertAfter: { anchor: statementEnd, text: " {setting.fix.retry}" }
examples:
  fires: [ { file: examples/lr01-fires.loom, expect: 1 } ]
  quiet: [ { file: examples/lr01-near-miss.loom } ]
```

```yaml
# a rule over tools, using a list relation and a count
id: LD02
lens: durability
severity: high
about: tool
when:
  all:
    - { field: kind, is: class }                 # your own Java, not one of the six journaled generic kinds
    - { field: reach, in: [writes, sends] }
    - { field: idempotentDeclared, is: false }
    - { field: usedBy, count: { gt: 0 } }
title: "Tool {name} is your own Java: a resumed run can repeat what it does"
```

```yaml
# a rule that needs a fact the project must declare, so it is inert until it does
id: LY03
lens: privacy
severity: high
about: agent
requires: [ setting.data.regions ]
when: { field: model.provider.region, notIn: { setting: data.regions } }
title: "Agent {name} uses {model.provider.name}, which is outside the allowed regions"
```

#### The fact model (R59, R60)

A deliberately small catalog of things and what is known about them. Version 1 targets **about a hundred fields in total**, and a field is added only when a rule needs it. The published catalog (`weave rules facts`, and `fact-model.json`) is the complete list; this table is its shape.

| Entity | `about:` | Examples of fields (name: type) |
|---|---|---|
| script | `script` | `hasBudget`, `budgetTokens`, `isScheduled`, `usesPausing`, `hasTests`, `hasGoldenData`, `statementCount` |
| workflow | `workflow` | `name`, `statementCount`, `maxDepth`, `interactive` (declared), `hasReturn`, `estimate.*` |
| step | `step` | `kind`, `label`, `line`, `inLoop`, `inForEach`, `inParallel`, `retry`, `retryCount`, `timeout`, `onFailure`, `loopMax`, `agent` (ref), `readsUntypedField`, `parallelizableWithPrevious`, `estimate.*` |
| agent | `agent` | `name`, `model` (ref), `tools` (list of tool), `approve`, `hasSchema`, `hasContext`, `guardPii` (none, warn, mask, block), `guardBias`, `promptSource` (file, inline), `inlinePromptLines`, `hasEffects`, `maxReach` |
| tool | `tool` | `name`, `kind`, `reach`, `approved`, `journaled`, `idempotentDeclared`, `usedBy` (list of agent), `calledInLoop` |
| classifier | `classifier` | `name`, `model`, `questionCount`, `hasConfidenceRule`, `calibrated` |
| model | `model` | `id`, `known`, `nearestKnown`, `priced`, `windowKnown`, `tier` (small, standard, large), `floatingAlias`, `deprecated`, `provider` (ref with `name`, `region`) |
| prompt | `prompt` | `name`, `file`, `tokens`, `lines`, `version`, `untrustedInterpolations` (list), `looksLikeSecret`, `containsPersonalData`, `text` (for bounded `matches`) |
| decision | `decision` | `name`, `hasLadder`, `hasSlipCondition`, `hasEffectAfter` |
| test | `test` | `file`, `workflow`, `agentsNamed`, `hasFailCase`, `hasHumansCase`, `stale` |
| estimate | (a field group on workflow and step) | `typicalCost`, `worstCost`, `worstIsUnbounded`, `shareOfRun`, `typicalSeconds`, `worstSeconds`, `worstEffects`, `worstQuestions` |
| setting | `setting.<key>` | any value from `review.properties`, including declared facts (`data.personal`, `data.regions`, `journal`, `blast.*`) |

- **R59** The fact model is a published, versioned catalog (`factModel: 1`). Each field has a name, a type (`bool`, `number`, `string`, `enum`, `list`, a reference to another entity), a one-line description and the version it appeared in. The catalog, its docs page and the rule validator are generated from the same Java declaration, so they cannot disagree. Adding a field is backward compatible; renaming or removing one is a new `factModel` major.
- **R60** **Java computes the facts; rules never compute.** Facts are produced by pure Java *fact providers* over `ReviewInput`: the cost, time and effects walk (R35, R36), the data-independence analysis behind `parallelizableWithPrevious`, the untrusted-variable analysis behind `untrustedInterpolations`, prompt similarity, coverage joins, the existing audit's profiles. A fact that cannot be known (no coverage report, no declared region) is **unknown**: a condition on an unknown fact is simply not satisfied, and a rule that lists it under `requires:` is reported as **not evaluated** (R38), never as a pass.

#### The vocabulary (R61, R62)

- **R61** A rule document has: `id`, `lens`, `severity`, `about`, `when`, optional `requires`, `title`, optional `why`, `fixHint`, optional `fix`, optional `heuristic: true`, optional `escalate` (a list of `{when, severity}`: the finding takes the highest severity whose condition holds, for rules such as LP01 that are medium and become high when the agent has tools), `since`, optional `references` (OWASP and others), optional `explain` text, and `examples` (R68). `title`, `why` and `fixHint` may contain `{field}` placeholders (and `{setting.key}`) that are filled in from the entity; that is the only substitution, and it is not evaluated as an expression.
- **R62** `when` is a tree of conditions from a **closed vocabulary**:
  - combinators: `all`, `any`, `not`;
  - a leaf names a `field` (a dotted path through declared references, such as `agent.tools`, at most three hops) and **one** operator: `is`, `isNot`, `in`, `notIn`, `gt`, `gte`, `lt`, `lte`, `contains`, `startsWith`, `matches` (a regular expression with a step limit), `exists`, `missing`;
  - over a list field: `some`, `every`, `none` (each with a nested condition) and `count` (with a comparator);
  - a compared value is a literal, `{ setting: key }`, or `{ field: other }` (another field of the same entity).
  There is nothing else. No arithmetic, no string building, no calls. Because of this the engine **terminates by construction** and can say, for any rule on any node, exactly which leaf was true or false (R70).

#### Fixes as templates (R67)

- **R67** A fix is a declared list of edit operations from a closed set: `insertAfter`, `insertBefore`, `replaceText`, `deleteLines`, `createFile`. Anchors come from a closed list that the engine resolves from the parsed script (`statementStart`, `statementEnd`, `blockEnd`, `lineOf: <field>`, `file`). Text is a literal with `{setting.*}` and `{field}` placeholders. The fix declares its safety class (`safe` or `review`).
  **Every edit set is verified by the engine** exactly as a Java fix is (R25): applied to a copy, re-parsed, checked, no new HIGH finding. A template that produces a broken script is withheld, shown as a note on the rule ("fix withheld: verification failed"), and fails the pack's tests. A fix that needs more than these operations is a Java rule.

#### Packs (R63 to R66)

A **rule pack** is a folder: `pack.yaml` (name, version, `factModel` required, rule schema version, id prefix, license, authors), `rules/**/*.yaml` (one rule per file, or several), `examples/` and `CHANGELOG.md` (generated, R74).

- **R63** **Which packs are in force.** The bundled **`core`** pack (every rule in §6 except the `LA` and `LV` rules, R57) ships inside the jar and is on by default. Project packs live under `review-rules/<name>/` and are named in `review.properties` with a pinned version and, optionally, a hash: `packs = core@1, acme-policy@2.0.1 sha256:9c1e…`. A pack that is present but not listed, or whose hash differs from the pin, is a load error that says what it expected. An org's pack can be a folder vendored into the repository or a jar passed with `--rules-jar`.
- **R64** **Never downloaded.** Neither the engine nor `weave rules` fetches a pack from a network. A pack arrives as files, by copy or `weave rules add <path>`, and updating one is an explicit, reviewable change in version control. This keeps the "review never touches the network" guarantee intact.
- **R65** **Inert and bounded.** A pack cannot read files outside its folder, run code, read the environment or the clock. Limits apply and produce clear errors: rules per pack, condition depth, regular-expression steps, entities visited per rule, and total evaluation time (a pack that exceeds it is skipped with a note, never a hang). Evaluating all packs on a 2,000-line script stays inside the review performance budget.
- **R66** **Validation at load.** Schema validation (JSON Schema, published) and semantic validation against the fact model: an unknown field is an error naming the nearest field (as R32); an operator that does not fit a field's type, an unknown lens, a `{placeholder}` that is not a field of the entity, a duplicate id, and an id outside the pack's declared prefix are all errors. A third-party pack declares an id prefix (for example `ACME-001`); the core ids (`L` followed by a letter and digits) are reserved. A project may change a rule's severity or turn it off in `review.properties`, but may not silently redefine it.
- **R68** **Examples are mandatory.** Every rule has at least one `fires` and one `quiet` example (a snippet inline or a file, with the expected finding count and location), and `weave rules test` runs them. A pack containing a rule without both fails to load. For the bundled pack the corpus precision gate (R18, R30) applies on top, unchanged.

#### Tooling and evolution (R69 to R74)

- **R69** Explain pages (R19), the short rule catalog the generator uses (phase 12), the `--explain` text and the docs under `docs/review/rules/` are generated from the pack metadata. A new rule needs no separate documentation work.
- **R70** `weave rules` commands, all offline: `list` (packs, rules, lens, on or off, source), `facts` (the fact catalog), `check <pack>` (validate), `test <pack> [--corpus]` (examples and, optionally, the corpus precision table), `new <ID>` (scaffold a rule and its two example files), `add <path>` (copy a pack in and pin it, showing the diff), and **`explain <ID> --at <file>:<line>`**, which evaluates the rule's condition tree on that node and prints every leaf with the value it saw ("agent.hasTools: true; retry: false; timeout: false, so it fired"), which is how a rule is debugged without reading engine code.
- **R71** **Provenance.** Each finding carries `pack: name@version` and the rule's own `since`/`changed` marker; the text report shows it on request, JSON and SARIF always include it. Accepts, dismissals and baselines (R41 to R46) key on the rule id and fingerprint, not on the pack version, so upgrading a pack does not resurface what was already handled.
- **R72** **Migration is proven, not trusted.** Rules written first in Java (`LA` and `LV` stay Java, R57) or ported are checked by a **parity test**: the Java and the data version run on the whole corpus and must produce identical findings (rule, node, line, severity) before the Java version is removed. After that the corpus labels are the oracle.
- **R73** **Java remains the escape hatch.** A rule that needs a loop, arithmetic across entities, text analysis beyond a bounded regular expression, or a new kind of analysis is a **fact provider** (when the new thing is a question about the script that data rules can then use) or a **Java rule** through the `ServiceLoader` mechanism (R21). Both produce the same findings and are indistinguishable to users except for the pack or jar shown as provenance. The documented guidance is: **do not make the rule syntax richer; add a fact or write Java.**
- **R74** **Upgrading a pack is predictable.** Pack versions follow semver: a new rule or a tightened or loosened `when` is a minor version with a changelog entry; a removed rule or an incompatible fact-model requirement is a major. `weave rules diff core@1.4 core@1.5 --on main.loom` lists rules added, changed and removed and **the findings that would appear or disappear on your script**, by running both versions, so a team reads the effect of a rule change before it lands in CI. This is the review-and-evolve loop: propose a rule file, run `weave rules test` and `weave rules diff`, merge.

#### What stays in Java, and what is data (R57, R58)

- **R57** **Form of every rule.** Every rule in §6 is a **data rule** in the `core` pack, except: the security lens's `LA01` to `LA20` (the existing audit, adapted, which stays Java because it is existing, security-critical code), and the validity lens's `LV` rules (adapters over the validator's own messages). Rules that need a computed analysis are still data rules: they read a fact whose provider is Java. The facts that do not exist yet and must be written as providers are: `step.parallelizableWithPrevious` (LC08), `agent.classificationShaped` (LC01), `prompt.untrustedInterpolations` (LP01), `prompt.looksLikeSecret` and `prompt.containsPersonalData` (LP04, LY06), `agent.duplicateOf` and `prompt.duplicateOf` (LM04, LP06), `step.writesSameVariableAsSibling` (LR09), `model.nearestKnown`, `model.deprecated` and `model.floatingAlias` (LG), `test.stale` and the coverage joins (LT), and the estimate groups. Everything else in the tables is a condition over facts that the existing code already knows.
- **R58** The engine, the fact providers, the fix verifier, fingerprints, de-duplication and the report formats are Java and are not data. A pack can only add or change **what is said about the facts**.

## 7. Architecture

```
review/
  ReviewEngine            runs lenses over a ReviewInput; collects, de-duplicates, ranks, applies settings and suppressions
  ReviewInput             the parsed script, its import closure, prompt files, loot/tools metadata, the graph, prices, windows, assumptions,
                          test files, eval dataset listing, the Fabric rules file, the last coverage report (R34), declared project facts (R38)
  ReviewRule              interface: id(), lens(), defaultSeverity(), describe(), check(ReviewInput, Sink)   (the Java escape hatch, R73)
  pack/                   PackLoader (validate, pin, hash), FactModel (the published catalog), FactProviders (pure Java), DataRule + RuleEngine (the closed vocabulary),
                          FixTemplates, PackTests, RulesCommand (weave rules ...), the bundled core pack under resources/review-rules/core
  rules/                  Java rules only: the LA adapter over SecurityAudit and the LV adapter over the validator, plus any rule a team writes in Java (R73)
  adapters/               Problem -> Finding, audit Finding -> Finding, graph Diagnostic -> Finding
  cost/                   CostModel, Assumptions, Estimate, PriceBook (reads the existing price file), Calibration
  fix/                    FixApplier (applies edits, verifies with check, detects conflicts), Diff (unified diff output)
  report/                 TextReport, MarkdownReport, JsonReport, SarifReport
  cli: ReviewCommand      picocli, registered beside AuditCommand
```

- **R20** `ReviewRule` implementations are pure functions of `ReviewInput`: no clock, no environment, no I/O, no randomness. The engine passes everything in, so rules are unit-testable without files.
- **R21** Third parties can add rules as **rule packs** (R63, the normal way) or, for what data cannot express, through `ServiceLoader<ReviewRule>` (R73) (an org that wants "no `shell` tool ever" writes a rule in ten lines); a rule from outside carries its jar in the report so findings are attributable. Loading is opt-in (`--rules-jar`), never from the script's folder by default.
- **R22** De-duplication: when `weave audit` and a new rule describe the same cause (for example LR07 and LA17 on one tool call), the engine shows the more specific one first and links the other (`related`), never two lines saying the same thing.
- **R23** Ranking: severity, then lens priority (security, reliability, cost, validity, prompts), then location. Stable and deterministic.

## 8. Fixes (R24 to R29)

### 8.1 Safety classes

- **SAFE**: mechanical, local, no choice involved, behaviour changes only as the title says (add a retry, add a `max`, delete an unreachable statement, create a stub prompt file). `--fix` applies these.
- **REVIEW**: needs a decision (which smaller model, what the failure handler does). Shown with a proposed edit; applied only by an explicit action on that one finding (editor click, or `--fix-rule LC01 --yes`).

- **R24** `--fix` applies every SAFE fix, in file/line order, as one atomic operation: if any edit fails to apply, or the result fails `weave check`, **nothing is written**.
- **R25** Edits are verified by applying them to an in-memory copy, re-parsing, and running the validity lens: the fixed script must have no new ERROR and must not *add* a finding of severity HIGH. A fix that cannot be verified is not offered.
- **R26** Overlapping edits are resolved by applying the higher-severity finding's fix first and re-running the rest against the new text; `--fix` loops until no SAFE fix applies or 5 passes, then reports what remains.
- **R27** Idempotent: running `--fix` twice changes nothing the second time.
- **R28** `--fix --dry-run` prints a unified diff and exit 1 if fixes are pending (so CI can demand a clean tree).
- **R29** Fixes preserve comments, blank lines and the file's line endings; they touch only the lines they must.

### 8.2 Parameters in fixes

Defaults for generated text come from `review.properties` (`fix.retry = retry 2 backoff 2s timeout 60s`, `fix.loop.max = 5`), so a team's convention is applied, not a built-in guess.

## 9. What the review cannot tell you

The report ends with a fixed section (not omitted when everything is green): review reads the script and prompts, not model behaviour; it does not know what a custom tool really does beyond its declared
`reach:`; cost is an estimate from stated assumptions; prompt rules are heuristics; a clean review is not a security certification. This mirrors the "what this cannot tell you" panel of phase 5. It also lists the rules that were **not evaluated** because the project has not declared what they need (R38), so a silent lens is never mistaken for a clean one.

## 10. The benchmark corpus (R30)

- **R30** A corpus under `ai-agent4j-loom/src/test/resources/review/corpus/` of at least 40 scripts: every example and sample in the repo as a *clean or known* case, plus scripts that each contain one labelled flaw
  (`// expect: LR03 line 12`). A corpus runner measures per-rule precision and recall, the estimator's error against 8+ recorded runs, and review time. The numbers are written to `VERIFICATION.md` §7 at the end of each sub-phase.

## 11. Configuration: `review.properties`

```properties
# beside main.loom, or --rules
fail-on = high
LR04 = off                                  # turn a rule off
LC01.severity = low                         # change a default
LR05.suppress = Router "free text on purpose; compared with contains"   # suppress at one place, reason required
assume.foreach.items = 20
assume.loop.iterations = 2
assume.latency.standard = 5s
lenses = +governance,+blast-radius,-testability        # project choice of lenses (R33)
heuristics = on
data.personal = customer.email, customer.phone, message  # fields to treat as personal (LY01)
data.regions = eu                                       # allowed provider regions (LY03); unset = LY03 not evaluated
journal = file                                          # none | file | jdbc (LD01); unset = LD01 reports only an INFO
workflow.Main.interactive = true                        # LC10
blast.max-effects = 10
blast.max-daily-cost = 5.00
packs = core@1, acme-policy@2.0.1 sha256:9c1e…            # rule packs in force, pinned (R63); unset = the bundled core pack only
fix.retry = retry 3 backoff 1s timeout 30s
```

- **R31** A suppression requires a reason and names a place (agent/node/line); a suppression without a reason is itself a finding (`LV08`). Suppressed findings are listed in the report's `suppressed[]` with their reasons, never hidden.
- **R32** An unknown key in `review.properties` is an error naming the nearest key.

### 11.1 Handling findings: fix, accept, dismiss, baseline (R41 to R48)

The noise answer is not fewer rules but a fast, safe, *durable* way to deal with each finding. A developer triages by severity and lens, and for any finding or group of findings does exactly one of four things:

| Action | Meaning | Durable? | Committed? | Counted by CI (`--fail-on`)? |
|---|---|---|---|---|
| **Fix** | Apply the change; the finding disappears because it is gone | n/a | the edit is | no longer present |
| **Accept** (ignore with a reason) | "This is intended here" — a decision the team can review | yes | yes (`review.properties`) | no, but listed as accepted |
| **Dismiss** | "Not now / not for me" — a personal, local hide | no (session or workspace) | never | **yes** (CI is unaffected) |
| **Baseline** | "Everything that exists today is known; tell me only about new problems" | yes | yes (`review.baseline.json`) | only new findings count |

- **R41** **Stable fingerprints.** Every finding has a `fingerprint` (in the JSON): a hash of the rule id, the most stable anchor available and the normalised subject. The anchor is, in order of preference, a **name** (agent, tool, task, classifier, workflow), a **semantic path** (workflow + construct kind + ordinal within its block), and only as a last resort a line. Line numbers, whitespace and edits elsewhere in the file do not change it. Accepts, dismissals and baselines are keyed by fingerprint, so an ignore survives an edit above it and does not silently hide a *different* finding that moved into its place.
- **R42** **Accept** (the durable ignore) requires a reason, records who and when (git identity when available), may carry an expiry (`until 2026-12-31`), and is written to `review.properties` as `RULE.accept = <scope> "reason" [until DATE]`. This is the R31 mechanism; "suppress" in earlier text means accept. An expired accept stops hiding the finding and says it expired. Accepted findings stay visible in the report (`accepted[]`) and in the summary ("3 high accepted"); they never vanish.
- **R43** **Scopes.** Accept, dismiss and fix all share the same scope choices, always shown with the number of findings they will affect before anything happens: *this finding*; *this rule on this agent/workflow*; *this rule in this script*; *this rule in the project*; *every finding in this group* (the severity-and-lens group on screen). A scope is never wider than the group being viewed unless chosen explicitly. Ignoring a whole lens is done by turning the lens off (R33), not by accepting its findings.
- **R44** **Guardrails for serious findings.** For findings of severity HIGH, or in the security, durability or privacy lenses: accept needs a reason **per finding** (a shared reason typed once is allowed for a bulk accept but is stored on each entry), bulk accept asks the developer to confirm the number ("accept 4 high findings?"), and `--fail-on` can be told to count accepted ones (`--strict-accepts`) for regulated projects. The **lethal trifecta** is accepted in the language (`trifecta: accepted "reason"`), because audit and Fabric read that form: accepting LA01-type findings writes that statement into the script, not `review.properties`.
- **R45** **Dismiss** needs no reason, is stored outside the repository (editor workspace storage; the CLI has no dismiss), defaults to the current session, and can be extended to the workspace. It never changes the report, the JSON, SARIF output or exit codes, so it cannot mask a problem from CI or a teammate; it only quiets the developer's own panel.
- **R46** **Baseline.** `weave review --baseline-write` (and the editor's **Accept all existing**) records the fingerprints of all current findings in `review.baseline.json` with the date and a single note; later reviews mark them `baselined`, show them in a collapsed group, and count only **new** findings against `--fail-on`. This is how a team adopts review on a large existing script without drowning. Baselines exclude HIGH findings in security, durability and privacy unless `--baseline-include-high` is given, and entries whose finding is gone are removed by `--baseline-prune`. A baseline never hides a new instance of the same rule.
- **R47** **Noise report.** `weave review --noise-report` summarises, per rule, how many findings there are now, how many are accepted, baselined and expired, and which rules are accepted in more than a configured share of places (default 50 %), with the suggestion "tune or turn off". It reads only the project's files; nothing is sent anywhere. This is the feedback loop that tells maintainers which rules are noisy in practice.
- **R48** **Fix semantics.** After any fix (single or group), the engine re-reviews the touched files; the fixed findings are gone, no new HIGH finding appeared (R25), and anything that did change is reported. **Fix all** in a group applies only the group's SAFE fixes; REVIEW fixes are never applied in bulk without a combined preview (one diff for all of them) and an explicit confirmation. Fixing and accepting are separate actions; a finding with no fix offers Accept and Explain only.


### 11.2 Advisory, never blocking (R49 to R56)

**Review informs; it does not gate.** An open finding never stops a developer from running, resuming, packaging, serving or deploying a workflow, whether they use the command line, the VS Code extension or a coding agent with the skills. They are told, once and briefly, that open findings exist, and the choice is theirs.

- **R49** **The rule.** `weave run`, `resume`, `tick`, `daemon`, `package`, `serve`, `deploy`, the extension's Run and the skills' run/deploy steps **never** refuse, prompt, delay beyond R52, or change their exit code because of review findings. The only things that exit non-zero on findings are commands the developer or their CI chose for that purpose: `weave review --fail-on <level>` (exit 1), `weave gate` (phase 14), `weave deploy --require-clean` (phase 15), and `weave audit` as it behaves today. No setting, file or environment variable makes `run` block.
- **R50** **The notice.** Commands that start something (`run`, `daemon` at start, `package`, `deploy`) print **one line to standard error** when open findings exist, and nothing when there are none:

  ```
  ⚠ weave review: 2 high · 3 medium · 1 low open (5 accepted, 2 baselined) — not blocking. Details: weave review   Silence: review.properties notice = off
  ```

  The line is plain text (no colour needed), appears once per process, goes to standard error only, and is never printed by `resume`, `tick`, `check`, `graph`, `explain`, `test`, `why` and other commands that are not starting a workflow or shipping one. For `daemon` it appears once at start, not on each tick. A `deploy` also records the open counts in the generated README (phase 15).
- **R51** **What counts as open.** A finding of a lens that is on, at or above the notice threshold, that is not fixed, **accepted** (R42) or **baselined** (R46). Dismissed findings are local to an editor and invisible to the CLI, so the CLI counts them as open. Accepted-but-expired entries are open. The threshold is `notice = high | medium | low | off` in `review.properties` (default `low`: info-level findings never produce a notice), overridable by `--review-notice <level>` on the starting commands and by the environment variable `LOOM_REVIEW_NOTICE`.
- **R52** **Never in the way.** The notice is computed from a cache keyed by (script and prompt content hash, rules, lens set, review version), so it is normally free. On a cache miss the review runs **concurrently with the command's own start-up**, with a time cap (default 1 s, `notice.timeout`); if it has not finished by then the notice is skipped and a single `review skipped (slow)` line is printed only with `--verbose`. A run never waits for review beyond that cap.
- **R53** **Nothing else changes.** With the notice on or off: standard output, exit code, the journal, trace events and the run directory are byte-identical (this is the exact meaning of program promise P10 for this feature). The notice is never read as input and never asks a question. It is the same single plain line whether or not standard error is a terminal (a CI log is where a warning is most useful), with no colour or escape sequences; `notice = off` or `LOOM_REVIEW_NOTICE=off` silences it.
- **R54** **Review failure is not a run failure.** A crash, a timeout or an unreadable rules file in the review engine at most turns the notice into `review unavailable (<reason>)` on standard error, once. It never fails or delays the command beyond R52.
- **R55** **Observability (optional).** When a run reports to Fabric (`--observe-fabric`), the open counts by severity travel as run attributes (`loom.review.open.high`, ...), so Fabric can show a badge on the run. Counts only: no finding text, no script content.
- **R56** **Skills and coding agents follow the same rule.** The `llm4j-workflow-guide`, `llm4j-harness-design` and `llm4j-loom-deployer` skills tell the agent to: run `weave review` after writing or changing a workflow; report the open counts and the top findings in a sentence; offer to fix or accept them; and **carry on with whatever the user asked** (run, test, package, deploy) without withholding it because findings are open. The skills may recommend fixing first, and must say plainly when a finding is HIGH, but the decision is the user's. Only when the user asks for a gate ("don't deploy unless review is clean") does the agent use `--fail-on` / `--require-clean`. A skill text test (`SkillAdvisoryTextTest`) checks the sentences are present and that no skill instructs the agent to stop on findings.

## 12. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 9a | Finding model, adapters for check/audit/graph, `ReviewEngine`, `weave review` (text, md, json), exit codes, `--explain` for existing rules, the corpus skeleton, `review.properties`; **the fact model v1, the first fact providers, the data-rule engine and the pack format with the bundled `core` pack skeleton (R57 to R66, R68)** | `FindingModelTest`, `AdapterTest`, `ReviewCommandTest`, `ReviewSupersetOfAuditTest` |
| 9b | **`weave rules` (list, facts, check, test, new, add, explain --at), pack pinning and hashes, parity tests for any rule written twice (R63, R70 to R72)**; LV classification of all check messages; SARIF; de-duplication; suppressions | `LvClassificationTest`, `SarifTest`, `SuppressionTest` |
| 9c | Cost estimator, assumptions, summary card, `--calibrate`, LC rules | `CostModelTest`, `CostBudgetCapTest`, `CalibrationTest`, `Lc*Test` |
| 9d | Reliability and prompt rules (LR, LP) **as data rules in the core pack, with their fact providers** | one test class per rule, corpus precision gate |
| 9e | Advisory behaviour (R49 to R56): the one-line notice, open-count definition, cache and time cap, no change to output or exit codes, review failure isolation; Fixes: `FixApplier`, `--fix`, `--dry-run`, verification, idempotence; stable fingerprints, accept with scopes and expiry, baseline, noise report (R41 to R48) | `FixApplierTest`, `FixAtomicityTest`, `FixIdempotentTest`, `FingerprintStabilityTest`, `AcceptScopesTest`, `BaselineTest`, `NoiseReportTest` |
| 9f | Observability, durability and privacy lenses (LO, LD, LY); declared facts and inert-rule notes (R38); lens selection (R33) | one test class per rule, `LensSelectionTest`, `InertRuleNoteTest`, `HeuristicSwitchTest` |
| 9g | Time and effects estimates (R35, R36); performance rules LC08 to LC10; testability lens (LT) with its extra inputs and the coverage-report staleness rule (R34) | `TimeEstimateTest`, `EffectsEstimateTest`, `Lc08ParallelTest`, `Lt*Test`, `CoverageReportStaleTest` |
| 9h | The four optional lenses (LG, LM, LH, LB) and their cross-lens de-duplication (R39, R40) | `Lg*Test`, `Lm*Test`, `Lh*Test`, `Lb*Test`, `OptionalLensesOffByDefaultTest`, `CrossLensDedupTest` |
| 9i | Docs: wiki page, LOOM_GUIDE section, `llms.txt`, skill updates, rule pages generated from the packs; **`weave rules diff` and pack versioning policy (R74); the guide for writing a rule** | docs tests, `RulesDiffTest` |

## 13. Decisions to confirm (open questions)

1. **Rule namespace.** LV/LC/LR/LP beside the existing LA. Alternative: one `LRxx` space for everything. Recommendation: keep LA as is (it is already published in reports and docs) and add the four families.
2. **Default `--fail-on`.** `none`: `weave review` is advisory and exits 0 on findings, so it can never be the thing that stops a `&&` chain by surprise (R49); CI and teams that want a gate set `--fail-on high` or `fail-on = high` in `review.properties`. `weave audit` keeps its existing default (`high`). Confirm.
3. **Heuristic rules on by default?** LC07, LP05, LP06 are off by default (R18). Confirm.
4. **Price source of truth.** The estimator reads the same `prices.properties` that `weave run` uses. If none exists, a bundled default table (clearly labelled with a date) is used and the report says so. Confirm that a bundled table is acceptable, given prices change.
5. **Where fixes may write.** Only the script, its imports and its prompt files, inside the project folder. Confirm this boundary. (Fixes that create a test file, a rules file or a golden stub also write inside the project folder; confirm.)
6. **Nine default lenses may be noisy.** Mitigations are the precision gate (R18), heuristics off unless enabled (R37), inert rules (R38), and `fail-on = high` by default. Confirm the default set, or start with the original five plus durability and observability and add testability and privacy once the corpus numbers are in.
7. **Model fallbacks (LG03).** The rule is inert unless the agent configuration offers a fallback model. Confirm whether Loom should gain a `fallback:` setting (a language change) or whether LG03 should be dropped.
8. **Interactive flag source (LC10).** A project declaration in `review.properties` is used. Alternative: a language-level workflow modifier. Recommendation: the declaration; no language change.
9. **Where durable ignores live.** `review.properties` (one file, easy to read in review) vs a separate `review.ignore` file (keeps rule tuning apart from decisions). Recommendation: `review.properties` for rule settings and accepts, `review.baseline.json` for baselines.
10. **Bulk accept of HIGH.** R44 allows it with a per-entry reason and a count confirmation. Alternative: forbid bulk accept for HIGH altogether. Recommendation: allow with the guardrails; forbidding it pushes people to the baseline, which is less visible.
11. **YAML or JSON for rules.** Both load (one schema). Recommendation: YAML for authoring (comments, readability, matches the repo's test and golden files), JSON accepted for generated packs.
12. **Do the LV rules become data?** They adapt the validator's own messages, so they stay Java for now. A later phase could make the message-to-rule table a data file. Confirm leaving it.
13. **How rich the first fact model is.** About a hundred fields is a target, not a promise; the rule is to add a field only when a rule needs it. Confirm that adding facts is a normal, small Java change and that no rule syntax will be added.
