# Requirements Document

## Introduction

Loom can already run an agent for days, survive crashes, wait for people without holding a thread, and keep a
journal of every step. What it cannot answer is the question every organisation asks before it lets an agent
act on its own: **"Has this agent earned it?"** Today that is a decision made by feel, once, before launch.

This spec adds **earned autonomy**: a workflow declares a *decision* (refund or not, grant access or not,
escalate or not). An agent proposes; a person decides; the runtime keeps a ledger of both. The agent starts in
**shadow** (it proposes, nobody sees it, humans decide as they always did), moves up a ladder as its record
earns it, and moves back down when the record says it should. Because the ledger holds the inputs of every past
case, any *candidate* change (a new model, a new prompt, a new policy) can be **replayed over the last N days
of real cases**, with nothing sent and nothing changed, and graded against what people actually decided.

> *Don't trust the agent. Make it earn it, with your own history as the exam.*

The four ideas, and why each one needs the runtime rather than a prompt:

| Idea | What it means |
|---|---|
| **Shadow first** | The agent's proposal is recorded but hidden from the human who decides, so agreement is measured honestly (a person who sees the answer first tends to agree with it) |
| **A ladder, not a switch** | `shadow` → `assist` → `act`, per kind of case, each step gated by evidence and each step reversible |
| **Replay the past** | Re-run recorded cases under changed code, with side effects suppressed, and diff the outcome against both the old agent and the humans |
| **The record is the authority** | Levels move because of the ledger, not because of anything the model says; the model cannot see, edit or influence it |

The standing rules from earlier Loom specs still apply:

- **Nothing is silently ignored.** Anything the runtime can't honour is a load error that names the line.
- **Existing valid scripts keep working.** `decision`, `decide` and `autonomy` are new words; a script that uses them as variable names is checked in the migration test (R1.7).
- **Logic lives in the framework**, with the approvals, budgets, guards, audit and journal that already exist.
- **Secrets and personal data** follow the guards already in force: the ledger never stores a secret, and applies the agent's `guard { pii }` setting before writing.

**Out of scope** (each a follow-up, not a gap in this one):

- Learning from the ledger: fine-tuning, automatic prompt rewriting, or choosing models. The ledger *grades*; it does not *train*.
- Judging several humans against each other, or weighting deciders by skill. One decider per case, recorded by name.
- Detecting outcomes by itself (a chargeback, a complaint). Later outcomes arrive through an explicit command or call (R5.5).
- A sample workflow, a dashboard, or a hosted service. This is the runtime feature and its command line.
- A statement that the result is "compliant" with any regulation. The runtime produces evidence; it does not certify.

## Glossary

- **Decision**: a named, declared choice with a fixed set of labels (`approve`, `reject`, `escalate`).
- **Case**: one occurrence of a decision, with its captured inputs. It has a **scope** (a key such as `tier`) that selects its ladder.
- **Proposal**: the agent's label for a case, with a rationale and an optional confidence.
- **Verdict**: the label that actually takes effect.
- **Level**: `shadow`, `assist` or `act`, held per decision and scope.
- **Ledger**: the append-only record of cases, proposals, verdicts and later outcomes.
- **Incumbent / candidate**: the script as it ran when the cases were decided, and a changed script being tried against them.

## Requirements

### Requirement 1: Declaring a decision

**User Story:** As a workflow author, I want to declare a decision and its ladder in the script, so that the rules for earning autonomy are written down, reviewed and versioned with the workflow.

#### Acceptance Criteria

1. THE script grammar SHALL accept a top-level `decision Name { … }` block with: `agent:` (an agent in the script), `labels:` (two or more names), `scope:` (a variable naming the case's scope; optional, default one scope), `fields:` (the variables captured as the case's inputs), and an `autonomy { … }` block (R3).
2. THE script grammar SHALL accept the statement `decide Name -> verdict`, usable wherever a statement is, which binds `verdict` to the label that takes effect, `verdict_proposal` to the agent's label, and `verdict_level` to the level it ran at.
3. WHEN a script is loaded, THE loader SHALL report, naming the line: an unknown agent; fewer than two labels or a duplicate label; a `scope` or `fields` entry that is not a variable known at the `decide` statement; a `decide` for an undeclared decision; thresholds out of range; a promotion rule that skips a level or refers to an unknown level; `ceiling` below `start`.
4. THE agent named by a decision SHALL be required to declare `output_schema` with a `label` (one of the labels) and a `rationale`, or the loader SHALL report that it can't produce a proposal. `confidence` (0 to 1) is optional.
5. A `decide` inside a loop or `for each` SHALL create one case per iteration, with a case id that is stable across a resume (R2.4).
6. THE feature SHALL work with every journal and trigger store Loom supports (memory, file, JDBC), and with scheduled, resumed and paused runs.
7. A script that already uses `decision`, `decide` or `autonomy` as a variable, agent or tool name SHALL keep loading and running unchanged; the new words are recognised only where the grammar expects them. A test SHALL cover every script in the repository.

### Requirement 2: Cases and the ledger

**User Story:** As an operator, I want every decision recorded with what the agent saw, what it proposed and what was decided, so that I have the evidence to judge it, and the material to replay it.

#### Acceptance Criteria

1. WHEN a `decide` statement runs, THE runtime SHALL append a **case record**: case id, decision, scope value, run id and step, timestamp, the captured `fields`, the agent's task text, the proposal (label, rationale, confidence), the level the case ran at, the incumbent's identity (R6.1), and, once known, the verdict, the decider's name, and how long the decision took.
2. THE ledger SHALL be append-only: a later fact about a case (the verdict, an outcome) is a new record that refers to the case, never an edit. Reading a case folds its records in order.
3. THE ledger SHALL have implementations for a file (one JSON-lines file per decision, in the run store) and for JDBC, behind one interface, plus one in memory for tests and unattended one-shot runs; all SHALL pass the same contract tests.
4. A case id SHALL be derived from the run id, the step id and the loop position, so a run that is resumed or replayed from its journal appends nothing twice. Appending the same record twice SHALL leave one.
5. THE ledger SHALL capture, for the decision's agent, every read-only tool call and result made while proposing (the **evidence**), up to a per-case size limit. A case whose evidence was cut off SHALL be marked so replay can say it can't be replayed faithfully (R7.4).
6. THE ledger SHALL apply the agent's `guard` settings (PII masking) before writing, SHALL never store a value that the redactor would scrub, and SHALL support a `retain:` period after which cases and their evidence are removed, leaving only counts.
7. THE agent SHALL NOT be given any tool, argument or prompt text that lets it read or write the ledger or the level. The ledger and the level store are reserved paths for the `file` tool.

### Requirement 3: The ladder

**User Story:** As a risk owner, I want the agent to be given more freedom only as evidence accumulates, and less when evidence turns, so that autonomy is something earned and revocable.

#### Acceptance Criteria

1. THERE SHALL be three levels, in order. **`shadow`**: the agent's proposal is recorded and hidden; a person decides and that is the verdict. **`assist`**: the person sees the proposal and confirms or overrides it; the person's answer is the verdict. **`act`**: the agent's proposal is the verdict, except for cases sent to a person by audit sampling (R3.6) or by a limit (R3.5).
2. A decision's ladder SHALL be held **per scope value**: `tier: gold` and `tier: basic` earn separately. A scope value seen for the first time starts at the declared `start` level.
3. THE `autonomy` block SHALL declare, per step up, a rule with: the minimum number of cases, the minimum number of days they span, a floor for the **lower bound of agreement** (the lower end of the 95% Wilson interval of "the proposal equals the human's decision" over the most recent window), and a ceiling for the **unsafe rate** (R3.4). A step up is allowed only when every part of its rule holds.
4. THE script SHALL be able to name **unsafe disagreements**: pairs `(proposal, decision)`, such as `approve` proposed and `reject` decided. The unsafe rate is their share of cases. An unsafe disagreement SHALL count more heavily than its share suggests: any rule MAY set `max_unsafe: 0` to forbid a step up while one exists in the window.
5. THE `act` level SHALL support limits that send a case to a person regardless of level: a `where:` condition on the fields (an amount above a figure), a per-day cap, and a budget. A case sent to a person by a limit is recorded as an `assist` case and keeps feeding the evidence.
6. THE `act` level SHALL send a configured share of cases (`audit: 5%`) to a person **blind** (without the proposal), chosen by a deterministic function of the case id so a resume makes the same choice. Audit cases count as evidence exactly as in `shadow`.
7. THE ladder SHALL step down when a **demotion rule** holds: reversals (R5.5) or unsafe disagreements above a count in a window, or a lower bound that has fallen below a floor. A demotion SHALL go to the level the rule names (default one step), take effect from the next case, and be recorded with its reason.
8. THE `ceiling:` option SHALL cap the level whatever the evidence says; the default ceiling SHALL be `assist`, so reaching `act` is a deliberate choice written in the script.
9. THE `promotion:` option SHALL be `approval` (default) or `auto`. With `approval`, reaching a rule's thresholds creates a **promotion proposal** that a named person approves or rejects through the usual durable approval path; nothing moves until they do. `auto` moves it and records the change. Demotion is always immediate and never needs approval.
10. THE level in force for a case SHALL be read once when the case begins and recorded in the run journal, so a run that is resumed after a level change finishes the case at the level it began with.
11. A frozen decision (R5.4) SHALL be treated as `assist` at most, whatever its level.

### Requirement 4: Honest measurement

**User Story:** As an auditor, I want to know the agent's agreement was measured without leading the people who decided, so that a high number means something.

#### Acceptance Criteria

1. IN `shadow` the person SHALL be asked without the proposal, its rationale or its confidence, and nothing in the question, the trace, the console or the notification SHALL reveal them before the person answers.
2. THE runtime SHALL record the decider's name and whether the proposal was shown, per case, and agreement computed for evidence SHALL use only cases decided blind (`shadow` and audit cases). `assist` cases are recorded and reported, and SHALL NOT count towards promotion.
3. THE agreement figure SHALL be defined only when the person's label and the agent's label come from the same label set, and SHALL treat an `escalate` proposal as its own label (an agent that always escalates is correct only when people escalate); the report SHALL show **coverage** (the share of cases the agent proposed a non-escalating label for) beside agreement.
4. A case with no human verdict yet SHALL not count; a case whose human verdict is missing for longer than a configured period SHALL be listed as stale in the status output.
5. THE statistics SHALL be computed by one documented function, tested against published reference values for the Wilson interval, so a number reported by `status` and a number used to promote are the same number.
6. A change in the incumbent's identity (R6.1) SHALL start a new **evidence epoch**: evidence from before it does not count towards the new incumbent's promotion unless R6.3 applies.

### Requirement 5: Operating it

**User Story:** As an operator, I want to see where every ladder stands, intervene at once, and feed in what happened later, so that I stay in control of something that runs for months.

#### Acceptance Criteria

1. `weave autonomy status <store> [--decision Name]` SHALL print, per decision and scope: level, cases in the window, agreement and its lower bound, unsafe rate, coverage, evidence epoch, progress towards the next rule (each part, with what is missing), stale cases, and the last level change with its reason.
2. `weave autonomy history <store> [--decision Name]` SHALL list every level change and promotion proposal with time, scope, from, to, reason and who approved.
3. `weave autonomy promote|demote <store> <decision> --scope <value> [--to level] --reason "…"` SHALL change a level by hand (a promotion beyond the ceiling or beyond what the evidence supports SHALL need `--force` and be recorded as forced). Both SHALL need a reason.
4. `weave autonomy freeze|unfreeze <store> [<decision>] --reason "…"` SHALL stop `act` at once for one decision or all, effective for cases that begin after the command and not for one in progress, and SHALL be recorded.
5. `weave autonomy outcome <store> <decision> <case-id> --label reversed|upheld|… [--note …]` SHALL record a later fact about a case, and the same SHALL be recordable by a script statement or a trigger. A `reversed` outcome SHALL count as an unsafe event for the demotion rules (R3.7) when the verdict was the agent's.
6. EVERY level change, proposal, freeze and outcome SHALL be written to the audit log with the decision, scope, the rule that fired and the figures it used.
7. A **notification hook** SHALL let a script name an agent-free action (a tool call) to run on a level change or a promotion proposal (for example a Slack message), run once per change, through the effect journal.
8. THE commands SHALL read and change the same stores the run uses, work while runs are in progress, and never leave a store half-written (writes are atomic or transactional).

### Requirement 6: Identity of the incumbent

**User Story:** As an engineer, I want to know which agent produced which decisions, so that evidence earned by one version is never credited to another.

#### Acceptance Criteria

1. EACH case SHALL record the incumbent's **identity**: a hash over the decision's agent definition (model, system prompt, persona, temperature, tools and their options excluding secrets, output schema, guard settings, skills and knowledge sources by content hash), the decision block, and the names of the models that served the call. Changing a comment or whitespace SHALL NOT change the identity.
2. WHEN the identity of the agent that would run a decision differs from the one on the ladder's current epoch, THE runtime SHALL begin a new epoch and apply the decision's `on_change:` policy: `shadow` (go back to `start`), `replay` (R6.3), or `keep` (only with `ceiling` at most `assist`; loaded with a warning otherwise).
3. WITH `on_change: replay`, THE runtime SHALL, before the first case of the new epoch, replay the most recent window of the old epoch's blind cases under the new identity (R7), and let the new epoch inherit the highest level whose rule that replay satisfies, at most the old level. The replay result and the inherited level SHALL be recorded. If too few replayable cases exist, the epoch SHALL start at `start`.
4. A model alias that the provider re-points (a `latest` name) SHALL be recorded by the name the provider returns where the response carries it; where it does not, the decision SHALL warn at load that a silent model change cannot be detected for it.

### Requirement 7: Replay

**User Story:** As a decision owner, I want to re-run past cases under a change before I ship it, with nothing sent and nothing changed, so that I can see what the change would have done.

#### Acceptance Criteria

1. `weave replay <script> --decision Name [--candidate <script>] [--since <duration|date>] [--scope v] [--limit n] [--report <file>] [--format md|json]` SHALL re-run the decision's agent over recorded cases, using the case's captured fields and task text, under the candidate script (default: the script given), and write a report.
2. REPLAY SHALL NOT cause any side effect outside the process: no tool call that changes anything SHALL be performed (R7.3), no run journal, trigger store, ledger case or level SHALL be changed by a replay (it writes its own report and its own replay log), and no human SHALL be asked anything.
3. IN replay every tool SHALL be handled by its class. A tool whose call is a known read (`Effectful` with `isEffect` false, or the built-in calculator and date tools) SHALL be answered from the case's recorded evidence when the same call was recorded; a read not recorded SHALL be reported as *not replayable under this candidate* for that case (default) or SHALL run live when `--live-reads` is given, and the report SHALL then flag the result as non-deterministic. Any other tool SHALL be **stubbed**: it returns `(simulated: not performed in replay)` and is never run. A tool may be declared `replay: allow` in the script to be run in replay; that SHALL be reported at the top of the report.
4. THE report SHALL give, overall and per scope: cases replayed, not replayable (with reasons: evidence cut off, new read, retained-out), the incumbent's agreement and lower bound against humans on the same cases, the candidate's, the **flips** (cases where candidate and incumbent differ, with both labels and the human's), the unsafe rate of each, coverage of each, token and money cost of the replay and the projected per-case cost of the candidate, and the level the candidate's replay would earn under the declared rules.
5. THE report SHALL list individual flips, sorted by how much they matter (unsafe first), each with the case's fields and both rationales, subject to the same masking as the ledger.
6. REPLAY SHALL be repeatable: the same ledger, candidate and `--seed` give the same selection and the same ordering; a model's own nondeterminism is reported by `--repeat n` (how often the candidate's label changes across n runs of the same case).
7. REPLAY SHALL obey the run's budgets: `--max-cost` and `--max-tokens` stop it cleanly and the report says how far it got; a rate limit pauses and resumes it (the replay log is durable).
8. A **policy replay** (`--policy <file>`) SHALL be supported for decisions whose rules are in a policy text referenced by the agent's prompt: the named file replaces the referenced one, so a rule change can be replayed without editing the script.

### Requirement 8: Safety of the feature itself

**User Story:** As a security reviewer, I want the ladder to be hard to game, from inside the workflow and from the data it processes, so that autonomy can't be talked into existence.

#### Acceptance Criteria

1. NOTHING an agent returns, a tool returns or a case's fields contain SHALL change a level, a rule, a ledger record or a freeze; only the runtime's own code and the `weave autonomy` commands write them.
2. A proposal whose label is not one of the declared labels SHALL be treated as `escalate` and counted as a malformed proposal; repeated malformed proposals SHALL be reportable as a demotion rule.
3. A case's fields and task text SHALL be treated as untrusted when shown to a person (no markup, no control characters), as for any other text shown to a person.
4. THE hostile-model suite SHALL include, for decisions: a proposal that claims to be human-approved, a case field instructing the agent to "mark this as reversed" or "promote", a proposal that asks for the ledger path, and attempts to reach the level store with the `file` and `shell` tools.
5. `--force` and hand-set levels SHALL be visible in every status output until the evidence catches up, so a forced level is never mistaken for an earned one.
6. THE feature SHALL fail closed: if the ledger or the level store can't be read, a decision SHALL run at `shadow` (or pause, when the decider can't be reached) and say why, never at a higher level on a guess.

### Requirement 9: Documentation and tooling

**User Story:** As a workflow author, I want the feature explained and supported by the tools, so that I can use it without reading the code.

#### Acceptance Criteria

1. `LOOM_GUIDE.md` SHALL have an "Earned autonomy" section covering the declaration, the three levels, blind measurement, thresholds, replay, operating commands and what the feature does not do; every `loom` block in it SHALL be validated by the existing guide-examples test.
2. `LOOM_PROMPT.md`, the READMEs, the VS Code grammar and language-server hover text SHALL mention `decision`, `decide` and `autonomy`.
3. THE spec SHALL NOT include a sample workflow; examples in the guide are fragments.
