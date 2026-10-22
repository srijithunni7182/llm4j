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
- **Five lenses**: *security* (today's audit), *validity* (today's check), *cost*, *reliability*, *prompts*. Each rule has a stable id, an explanation page, and a precision target.
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
        Lens lens,              // SECURITY | VALIDITY | COST | RELIABILITY | PROMPTS
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
  --lens security,cost,...        run only these lenses (default: all)
  --min-severity high|medium|low|info   show at least this (default: info)
  --fail-on high|medium|low|info|none   exit 1 when a finding is at least this severe (default: high)
  --fix                           apply every SAFE fix, then review again and print what changed
  --fix --dry-run                 print the diff, change nothing
  --explain <RULE>                the rule's page: what it checks, an example that fires, one that does not, the fix, known false positives
  --rules <file>                  rule settings and suppressions (default: review.properties beside the script)
  --prices <file>, --windows <file>   as in weave run
  --loot <file>, --no-env         as in weave check / audit
  --assume key=value              override a cost assumption (repeatable)
  --calibrate <run-dir>           compare the cost estimate with what a journaled run really spent (§5.5)
```

- **R6** Exit codes: `0` nothing at or above `--fail-on`; `1` a finding at or above it; `2` the script could not be read or parsed (the existing convention). `--fix` exits by what remains after fixing.
- **R7** `weave audit` and `weave check` are unchanged. `weave review` runs both internally, so `review` is a superset: no finding they produce is missing from `review` (a test pins this).
- **R8** SARIF output (version 2.1.0) so the same findings appear in GitHub code scanning. Rule ids map to SARIF rule ids; fixes map to SARIF `fixes`.
- **R9** The text report ends with a summary card:

```
TicketTriage   main.loom         2 high  3 medium  1 low
Cost       typical $0.004 / run     worst case $0.06 (bounded by 3 loops, 2 retries)     [assumptions: weave review --explain cost]
Security   trifecta: Replier can read customer data and send mail with no approval (LA05)
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

## 7. Architecture

```
review/
  ReviewEngine            runs lenses over a ReviewInput; collects, de-duplicates, ranks, applies settings and suppressions
  ReviewInput             the parsed script, its import closure, prompt files, loot/tools metadata, the graph, prices, windows, assumptions
  ReviewRule              interface: id(), lens(), defaultSeverity(), describe(), check(ReviewInput, Sink)
  rules/{validity,security,cost,reliability,prompts}/   one class per rule; security rules wrap SecurityAudit
  adapters/               Problem -> Finding, audit Finding -> Finding, graph Diagnostic -> Finding
  cost/                   CostModel, Assumptions, Estimate, PriceBook (reads the existing price file), Calibration
  fix/                    FixApplier (applies edits, verifies with check, detects conflicts), Diff (unified diff output)
  report/                 TextReport, MarkdownReport, JsonReport, SarifReport
  cli: ReviewCommand      picocli, registered beside AuditCommand
```

- **R20** `ReviewRule` implementations are pure functions of `ReviewInput`: no clock, no environment, no I/O, no randomness. The engine passes everything in, so rules are unit-testable without files.
- **R21** Third parties can add rules through `ServiceLoader<ReviewRule>` (an org that wants "no `shell` tool ever" writes a rule in ten lines); a rule from outside carries its jar in the report so findings are attributable. Loading is opt-in (`--rules-jar`), never from the script's folder by default.
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
`reach:`; cost is an estimate from stated assumptions; prompt rules are heuristics; a clean review is not a security certification. This mirrors the "what this cannot tell you" panel of phase 5.

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
fix.retry = retry 3 backoff 1s timeout 30s
```

- **R31** A suppression requires a reason and names a place (agent/node/line); a suppression without a reason is itself a finding (`LV08`). Suppressed findings are listed in the report's `suppressed[]` with their reasons, never hidden.
- **R32** An unknown key in `review.properties` is an error naming the nearest key.

## 12. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 9a | Finding model, adapters for check/audit/graph, `ReviewEngine`, `weave review` (text, md, json), exit codes, `--explain` for existing rules, the corpus skeleton, `review.properties` | `FindingModelTest`, `AdapterTest`, `ReviewCommandTest`, `ReviewSupersetOfAuditTest` |
| 9b | LV classification of all check messages; SARIF; de-duplication; suppressions | `LvClassificationTest`, `SarifTest`, `SuppressionTest` |
| 9c | Cost estimator, assumptions, summary card, `--calibrate`, LC rules | `CostModelTest`, `CostBudgetCapTest`, `CalibrationTest`, `Lc*Test` |
| 9d | Reliability and prompt rules (LR, LP) | one test class per rule, corpus precision gate |
| 9e | Fixes: `FixApplier`, `--fix`, `--dry-run`, verification, idempotence | `FixApplierTest`, `FixAtomicityTest`, `FixIdempotentTest` |
| 9f | Docs: wiki page, LOOM_GUIDE section, `llms.txt`, skill updates, rule pages generated | docs tests |

## 13. Decisions to confirm (open questions)

1. **Rule namespace.** LV/LC/LR/LP beside the existing LA. Alternative: one `LRxx` space for everything. Recommendation: keep LA as is (it is already published in reports and docs) and add the four families.
2. **Default `--fail-on`.** `high` (as `audit`). Alternative: `medium` for `review` because it covers more. Recommendation: `high`; teams tighten in `review.properties`.
3. **Heuristic rules on by default?** LC07, LP05, LP06 are off by default (R18). Confirm.
4. **Price source of truth.** The estimator reads the same `prices.properties` that `weave run` uses. If none exists, a bundled default table (clearly labelled with a date) is used and the report says so. Confirm that a bundled table is acceptable, given prices change.
5. **Where fixes may write.** Only the script, its imports and its prompt files, inside the project folder. Confirm this boundary.
