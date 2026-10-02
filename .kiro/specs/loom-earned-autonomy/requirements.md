# Requirements Document

## Introduction

Loom can already run an agent for days, survive crashes, wait for people without holding a thread, and keep a
journal of every step. What it cannot answer is the question every organisation asks before it lets an agent
act on its own: **"Has this agent earned it?"** Today that is a decision made by feel, once, before launch.

This spec adds **earned autonomy**: a workflow declares a *decision* (refund or not, grant access or not,
escalate or not). An agent proposes; a person decides; the runtime keeps a ledger of both. The agent starts in
**watch** (it proposes, nobody sees it, humans decide as they always did), moves up a ladder as its record
earns it, and moves back down when the record says it should. Because the ledger holds the inputs of every past
case, any *candidate* change (a new model, a new prompt, a new policy) can be **replayed over the last N days
of real cases**, with nothing sent and nothing changed, and graded against what people actually decided.

> *Don't trust the agent. Make it earn it, with your own history as the exam.*

This spec is built on [loom-rewind-and-fork](../loom-rewind-and-fork/requirements.md), which must exist first. Replay is not a separate
engine: a replay of a past case is an **ephemeral fork** of that case's run, taken just before the `decide` statement, run under a
candidate script in **simulate** mode and stopped when the decision completes. What this spec adds is what to *do* with such forks:
grade them against humans, and let the result decide how much freedom an agent has.

The four ideas, and why each one needs the runtime rather than a prompt:

| Idea | What it means |
|---|---|
| **Watch first** | The agent's proposal is recorded but hidden from the human who decides, so agreement is measured honestly (a person who sees the answer first tends to agree with it) |
| **A ladder, not a switch** | `watch` → `suggest` → `act`, per kind of case, each step gated by evidence and each step reversible |
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

- **Decision**: a named, declared choice with a fixed set of choices (`approve`, `reject`, `escalate`).
- **Case**: one occurrence of a decision, with its captured inputs. It has a **scope** (a key such as `tier`) that selects its ladder.
- **Proposal**: the agent's choice for a case, with a reasoning and an optional confidence.
- **Verdict**: the choice that actually takes effect.
- **Level**: `watch`, `suggest` or `act`, held per decision and scope.
- **Ledger**: the append-only record of cases, proposals, verdicts and later outcomes.
- **Fork, ephemeral fork, simulate, generation, boundary**: as defined in loom-rewind-and-fork.
- **Incumbent / candidate**: the script as it ran when the cases were decided, and a changed script being tried against them.

## Requirements

### Requirement 1: Declaring a decision

**User Story:** As a workflow author, I want to declare a decision and its ladder in the script, so that the rules for earning autonomy are written down, reviewed and versioned with the workflow.

#### Acceptance Criteria

1. THE script grammar SHALL accept a top-level `decision Name { … }` block, written in plain phrases (design §1.4), with: `proposed by:` (an agent in the script), `choices:` (two or more names), `group cases by:` (a variable naming the case's scope; optional, default one scope), `remember:` (the variables captured as the case's inputs), `dangerous mistake:` (zero or more), `ask:` (who decides), `keep records for:`, `when the agent changes:`, optional `tell <tool> when trust changes`, and a `trust { … }` block (R3).
2. THE script grammar SHALL accept the statement `decide Name -> verdict` (read: "decide a Refund, call the answer verdict"), usable wherever a statement is, which binds `verdict` to the choice that takes effect, `verdict_proposal` to the agent's choice, and `verdict_level` to the level it ran at.
3. WHEN a script is loaded, THE loader SHALL report, naming the line: an unknown agent; fewer than two choices or a duplicate choice; a `group cases by` or `remember` entry that is not a variable known at the `decide` statement; a `decide` for an undeclared decision; percentages outside 0 to 100 and counts that are not positive; a `to <level>` rule that skips a level or names an unknown one; `never go above` a level below `start at`.
4. THE agent named by a decision SHALL be required to declare `output_schema` with a `choice` (one of the choices) and a `reasoning`, or the loader SHALL report that it can't produce a proposal. `confidence` (0 to 1) is optional.
5. A `decide` inside a loop or `for each` SHALL create one case per iteration, with a case id that is stable across a resume (R2.4).
6. THE feature SHALL work with every journal and trigger store Loom supports (memory, file, JDBC), and with scheduled, resumed and paused runs.
7. A script that already uses `decision`, `decide` or `autonomy` as a variable, agent or tool name SHALL keep loading and running unchanged; the new words are recognised only where the grammar expects them. A test SHALL cover every script in the repository.

### Requirement 2: Cases and the ledger

**User Story:** As an operator, I want every decision recorded with what the agent saw, what it proposed and what was decided, so that I have the evidence to judge it, and the material to replay it.

#### Acceptance Criteria

1. WHEN a `decide` statement runs, THE runtime SHALL append a **case record**: case id, decision, scope value, **the run and step that hold the case's inputs** (the journal locator: a run directory or a JDBC run id), timestamp, the captured `fields`, the proposal (choice, reasoning, confidence), the level the case ran at, the incumbent's identity (R6.1), and, once known, the verdict, the decider's name, and how long the decision took. The case's task text and the evidence (R2.5) SHALL be written to the **run journal** under the decide step (`…#decide-task`, `…#decide-evidence:<n>`), not duplicated in the ledger, because a fork of the run sees the journal.
2. THE ledger SHALL be append-only: a later fact about a case (the verdict, an outcome) is a new record that refers to the case, never an edit. Reading a case folds its records in order.
3. THE ledger SHALL have implementations for a file (one JSON-lines file per decision, in the run store) and for JDBC, behind one interface, plus one in memory for tests and unattended one-shot runs; all SHALL pass the same contract tests.
4. A case id SHALL be derived from the run id and the **generation-free** step id (loop position included), so a run that is resumed appends nothing twice. Appending the same record twice SHALL leave one. WHEN a rewind causes a case to be decided again in a later generation, THE ledger SHALL keep both: the later generation is the case's current decision, the earlier ones are marked `superseded` and SHALL NOT count in any statistic; a person's blind verdict is reused for an identical question (rewind-and-fork R4.4), so a rewound case does not ask the person twice.
5. THE runtime SHALL capture, for the decision's agent, every read-only tool call and result made while proposing (the **evidence**) into the run journal, up to a per-case size limit. A case whose evidence was cut off SHALL be marked so replay can say it can't be replayed faithfully (R7.4).
6. THE ledger SHALL apply the agent's `guard` settings (PII masking) before writing, SHALL never store a value that the redactor would scrub, and SHALL support a `keep records for: 180 days` setting, after which the cases' fields are removed, leaving only counts, and the **journals of finished runs** they point to are removed too (a run that is not finished is never removed). A case whose journal is gone can no longer be replayed (R7.4).
7. THE agent SHALL NOT be given any tool, argument or prompt text that lets it read or write the ledger or the level. The ledger and the level store are reserved paths for the `file` tool.

### Requirement 3: The ladder

**User Story:** As a risk owner, I want the agent to be given more freedom only as evidence accumulates, and less when evidence turns, so that autonomy is something earned and revocable.

#### Acceptance Criteria

1. THERE SHALL be three levels, in order. **`watch`**: the agent's proposal is recorded and hidden; a person decides and that is the verdict. **`suggest`**: the person sees the proposal and confirms or overrides it; the person's answer is the verdict. **`act`**: the agent's proposal is the verdict, except for cases sent to a person by audit sampling (R3.6) or by a limit (R3.5).
2. A decision's ladder SHALL be held **per scope value**: `tier: gold` and `tier: basic` earn separately. A scope value seen for the first time starts at the declared `start` level.
3. THE `trust` block SHALL declare, per step up, a rule written `to <level>: after N cases over D days, agreeing at least P%` (optionally `, with no dangerous mistakes` or `, with at most X% dangerous mistakes`), meaning: the minimum number of cases, the minimum number of days they span, a floor for **agreement**, which is judged by a cautious estimate (the lower end of the 95% Wilson interval of "the proposal equals the person's decision" over the most recent window: 20 out of 20 is not treated as 100%), and a ceiling for the **dangerous-mistake rate** (R3.4). A step up is allowed only when every part of its rule holds.
4. THE script SHALL be able to name **dangerous mistakes**: pairs `(proposal, decision)`, such as `approve` proposed and `reject` decided. The dangerous-mistake rate is their share of cases. A dangerous mistake SHALL count more heavily than its share suggests: a rule written `with no dangerous mistakes` forbids a step up while one exists in the window.
5. THE `act` level SHALL support limits that send a case to a person regardless of level: `always ask a person when <condition>` (a condition on the remembered values, such as `amount > 200`), `always ask a person after N cases a day`, and a budget. A case sent to a person by a limit is recorded as a `suggest` case and keeps feeding the evidence.
6. THE `act` level SHALL send a configured share of cases (`check 5% of cases with a person who doesn't see the proposal`) to a person **blind** (without the proposal), chosen by a deterministic function of the case id so a resume makes the same choice. Audit cases count as evidence exactly as in `watch`.
7. THE ladder SHALL step down when a **demotion rule** holds: reversals (R5.5), dangerous mistakes or unusable proposals above a count in a window, or agreement that has fallen below a floor, each written `drop to <level> when …` (for example `drop to suggest when 2 dangerous mistakes in 50 cases`). A demotion SHALL go to the level the rule names, take effect from the next case, and be recorded with its reason.
8. THE line `never go above <level>` SHALL cap the level whatever the evidence says; the default SHALL be `suggest`, so reaching `act` is a deliberate choice written in the script.
9. THE script SHALL say `moving up needs approval from: <who>` (default, with a named approver required) or `moving up is automatic`. With approval, reaching a rule's thresholds creates a **promotion proposal** that a named person approves or rejects through the usual durable approval path; nothing moves until they do. `auto` moves it and records the change. Demotion is always immediate and never needs approval.
10. THE level in force for a case SHALL be read once when the case begins and recorded in the run journal, so a run that is resumed after a level change finishes the case at the level it began with.
11. A frozen decision (R5.4) SHALL be treated as `suggest` at most, whatever its level.

### Requirement 4: Honest measurement

**User Story:** As an auditor, I want to know the agent's agreement was measured without leading the people who decided, so that a high number means something.

#### Acceptance Criteria

1. IN `watch` the person SHALL be asked without the proposal, its reasoning or its confidence, and nothing in the question, the trace, the console or the notification SHALL reveal them before the person answers.
2. THE runtime SHALL record the decider's name and whether the proposal was shown, per case, and agreement computed for evidence SHALL use only cases decided blind (`watch` and audit cases). `suggest` cases are recorded and reported, and SHALL NOT count towards promotion.
3. THE agreement figure SHALL be defined only when the person's choice and the agent's choice come from the same choice set, and SHALL treat an `escalate` proposal as its own choice (an agent that always escalates is correct only when people escalate); the report SHALL show **coverage** (the share of cases the agent proposed a non-escalating choice for) beside agreement.
4. A case with no human verdict yet SHALL not count; a case whose human verdict is missing for longer than a configured period SHALL be listed as stale in the status output.
5. THE statistics SHALL be computed by one documented function, tested against published reference values for the Wilson interval, so a number reported by `status` and a number used to promote are the same number.
6. A change in the incumbent's identity (R6.1) SHALL start a new **evidence epoch**: evidence from before it does not count towards the new incumbent's promotion unless R6.3 applies.

### Requirement 5: Operating it

**User Story:** As an operator, I want to see where every ladder stands, intervene at once, and feed in what happened later, so that I stay in control of something that runs for months.

#### Acceptance Criteria

1. `weave autonomy status <store> [--decision Name]` SHALL print, per decision and scope: level, cases in the window, agreement and its lower bound, dangerous-mistake rate, coverage, evidence epoch, progress towards the next rule (each part, with what is missing), stale cases, and the last level change with its reason.
2. `weave autonomy history <store> [--decision Name]` SHALL list every level change and promotion proposal with time, scope, from, to, reason and who approved.
3. `weave autonomy promote|demote <store> <decision> --scope <value> [--to level] --reason "…"` SHALL change a level by hand (a promotion beyond the ceiling or beyond what the evidence supports SHALL need `--force` and be recorded as forced). Both SHALL need a reason.
4. `weave autonomy freeze|unfreeze <store> [<decision>] --reason "…"` SHALL stop `act` at once for one decision or all, effective for cases that begin after the command and not for one in progress, and SHALL be recorded.
5. `weave autonomy outcome <store> <decision> <case-id> --result reversed|upheld|… [--note …]` SHALL record a later fact about a case, and the same SHALL be recordable by a script statement or a trigger. A `reversed` outcome SHALL count as an unsafe event for the demotion rules (R3.7) when the verdict was the agent's.
6. EVERY level change, proposal, freeze and outcome SHALL be written to the audit log with the decision, scope, the rule that fired and the figures it used.
7. A **notification hook** SHALL let a script name an agent-free action (a tool call) to run on a level change or a promotion proposal (for example a Slack message), run once per change, through the effect journal.
8. THE commands SHALL read and change the same stores the run uses, work while runs are in progress, and never leave a store half-written (writes are atomic or transactional).

### Requirement 6: Identity of the incumbent

**User Story:** As an engineer, I want to know which agent produced which decisions, so that evidence earned by one version is never credited to another.

#### Acceptance Criteria

1. EACH case SHALL record the incumbent's **identity**: a hash over the decision's agent definition (model, system prompt, persona, temperature, tools and their options excluding secrets, output schema, guard settings, skills and knowledge sources by content hash), the decision block, and the names of the models that served the call. Changing a comment or whitespace SHALL NOT change the identity.
2. WHEN the identity of the agent that would run a decision differs from the one on the ladder's current epoch, THE runtime SHALL begin a new epoch and apply the decision's `when the agent changes:` policy: `start over` (go back to the `start at` level), `test it on past cases` (R6.3), or `keep the trust` (only with `never go above` at most `suggest`; loaded with a warning otherwise).
3. WITH `when the agent changes: test it on past cases`, THE runtime SHALL, before the first case of the new epoch, replay the most recent window of the old epoch's blind cases under the new identity (R7), and let the new epoch inherit the highest level whose rule that replay satisfies, at most the old level. The replay result and the inherited level SHALL be recorded. If too few replayable cases exist, the epoch SHALL start at `start`.
4. A model alias that the provider re-points (a `latest` name) SHALL be recorded by the name the provider returns where the response carries it; where it does not, the decision SHALL warn at load that a silent model change cannot be detected for it.

### Requirement 7: Replay

**User Story:** As a decision owner, I want to re-run past cases under a change before I ship it, with nothing sent and nothing changed, so that I can see what the change would have done.

#### Acceptance Criteria

1. `weave replay <script> --decision Name [--candidate <script>] [--since <duration|date>] [--scope v] [--limit n] [--report <file>] [--format md|json]` SHALL, for each selected case, take an **ephemeral fork** (rewind-and-fork R5.9) of the case's run journal at the case's `decide` step, run it under the candidate script (default: the script given) in **simulate** mode, stop right after the proposal is journaled and **before any person is asked** (the stop point `<step>#decide-proposal`), and read the candidate's proposal from the fork. It SHALL then write a report.
2. REPLAY SHALL NOT cause any side effect outside the process: the fork's parent journal, the ledger, the level store, the trigger store and the run directories SHALL be byte-identical afterwards (only the replay's own directory is written, R7.5); no effect SHALL be performed (rewind-and-fork R4.5, R4.8); no human SHALL be asked anything (a null human interface that fails the case if asked).
3. IN replay, tool calls made by the decision's agent SHALL be handled by class, on top of simulate: a call that is a known read (`Effectful` with `isEffect` false, or the built-in calculator and date tools) SHALL be answered from the case's recorded evidence when the same call was recorded; a read not recorded SHALL make the case *not replayable under this candidate* (default) or SHALL run live under `--live-reads`, flagged non-deterministic; every other tool SHALL be simulated (it returns `(simulated: not performed)`); a tool declared `replay: allow` in the script SHALL run, and SHALL be listed at the top of the report.
4. A CASE SHALL be skipped, with a reason counted in the report, when: its journal is gone (`journal_missing`, including retention); its evidence was cut off; effects were performed while proposing; a read the candidate makes was not recorded; the candidate's script differs from the case's script **before** the decide step (`prefix_drift`, by rewind-and-fork R5.5; `--allow-drift` accepts it and the report flags the cases); its fields were masked beyond use. THE report SHALL give the replayable share of the selected cases, so a good figure over a small share can't hide.
5. A REPLAY SHALL write only its own directory `<store>/autonomy/<decision>/replays/<id>/` (plan, log, report), never a ledger record, level or run journal.
6. THE report SHALL give, overall and per scope: cases replayed, not replayable (with reasons), the incumbent's agreement and lower bound against humans on the same cases, the candidate's, the **flips** (cases where candidate and incumbent differ, with both choices and the human's), the dangerous-mistake rate and coverage of each, token and money cost of the replay and the projected per-case cost of the candidate, and the level the candidate's replay would earn under the declared rules.
7. THE report SHALL list individual flips, sorted by how much they matter (unsafe first), each with the case's fields and both reasonings, subject to the same masking as the ledger.
8. REPLAY SHALL be repeatable: the same ledger, candidate and `--seed` give the same selection and ordering; a model's own nondeterminism is reported by `--repeat n` (how often the candidate's choice changes across n runs of the same case).
9. REPLAY SHALL obey budgets: `--max-cost` and `--max-tokens` stop it cleanly and the report says how far it got; a rate limit pauses it, and `weave replay --resume <id>` continues from the replay log.
10. A **policy replay** (`--policy <file>`) SHALL replace the content of a file the candidate's agent references, for the candidate only, so a rule change can be replayed without editing the script; with no such reference it is an error naming that.
11. THE operator MAY do the same for one case by hand with `weave fork <run> --at <decide step> --script candidate.loom --effects simulate --until <decide step>#decide-proposal` (rewind-and-fork R5.4); replay is that, repeated and graded.
12. WHEN a decision is replayed under `when the agent changes: test it on past cases` (R6.3), THE same engine SHALL be used, over the old epoch's cases, with the new incumbent as the candidate.

### Requirement 8: Safety of the feature itself

**User Story:** As a security reviewer, I want the ladder to be hard to game, from inside the workflow and from the data it processes, so that autonomy can't be talked into existence.

#### Acceptance Criteria

1. NOTHING an agent returns, a tool returns or a case's fields contain SHALL change a level, a rule, a ledger record or a freeze; only the runtime's own code and the `weave autonomy` commands write them.
2. A proposal whose choice is not one of the declared choices SHALL be treated as `escalate` and counted as a malformed proposal; repeated malformed proposals SHALL be reportable as a demotion rule.
3. A case's fields and task text SHALL be treated as untrusted when shown to a person (no markup, no control characters), as for any other text shown to a person.
4. THE hostile-model suite SHALL include, for decisions: a proposal that claims to be human-approved, a case field instructing the agent to "mark this as reversed" or "promote", a proposal that asks for the ledger path, and attempts to reach the level store with the `file` and `shell` tools.
5. `--force` and hand-set levels SHALL be visible in every status output until the evidence catches up, so a forced level is never mistaken for an earned one.
6. THE feature SHALL fail closed: if the ledger or the level store can't be read, a decision SHALL run at `watch` (or pause, when the decider can't be reached) and say why, never at a higher level on a guess.

### Requirement 9: Documentation and tooling

**User Story:** As a workflow author, I want the feature explained and supported by the tools, so that I can use it without reading the code.

#### Acceptance Criteria

1. `LOOM_GUIDE.md` SHALL have an "Earned autonomy" section covering the declaration, the three levels, blind measurement, thresholds, replay, operating commands and what the feature does not do; every `loom` block in it SHALL be validated by the existing guide-examples test.
2. `LOOM_PROMPT.md`, the READMEs, the VS Code grammar and language-server hover text SHALL mention `decision`, `decide` and `autonomy`.
3. THE spec SHALL NOT include a sample workflow; examples in the guide are fragments.
