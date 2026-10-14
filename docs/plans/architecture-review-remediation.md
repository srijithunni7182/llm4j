# Plan: addressing the architecture review (14 Oct 2026)

Status: **draft for approval**. Branch: `claude/kind-albattani-lesl3e`.

Source: the independent static review of llm4j, plus a follow-up check of each finding against the code. This plan
orders the work, sets the definition of done for each item, and says what is deliberately left out.

## Principles

- **One PR per workstream.** Each is small enough to review and revert alone. Nothing is bundled with a refactor.
- **Tests before moves.** Every extraction in workstream F is preceded by characterization tests in the same PR, and
  is a pure move: no behaviour change, no public API change, existing tests unchanged.
- **Verify with the repo's own gates:** `make test` (the default build with coverage gates) for every PR;
  `make format-check` for `ai-agent4j` and `ai-agent4j-addons`; `make quick` before the last PR of each phase.
- **Docs match code.** A claim in `docs/security/owasp-llm-top-10.md` or `SECURITY.md` changes in the same PR as
  the behaviour it describes.

## What the code check found (basis for the order)

| # | Review finding | Verified? | Plan item |
|---|---|---|---|
| 1 | Sensitive data in logs/audit | **Yes.** `ReActAgent` logs full LLM output and tool observations at INFO (`:323`, `:550`, `:715`); `FileAuditLogger` writes 1000-char tool input/output and the full final answer. The docs say audit logs "record kinds, not data". | A |
| 2 | Journal concurrency | **Yes, narrower.** `JdbcRunJournal.put` is UPDATE-then-INSERT; a race ends in a PK violation (loud), not silent corruption. | B |
| 3 | Approval / tenant isolation | Already a documented gap (LLM06, LLM08). | D |
| 4 | Budget coverage | Already a documented gap (LLM10). `BudgetedLLMClient` itself is sound. | D |
| 5 | Jackson drift | **Yes.** Root declares 2.22.3; modules use 2.21.7, 2.21.3 and 2.18.11 (`ctk`). | C |
| 6 | God classes | **`HarnessExecutor`: yes** (2,884 lines, about 145 methods, 8 jobs). **`ReActAgent`: accepted as is** (about 1,000 lines of logic; maintainer decision). | F |
| 7 | Audit failure policy | **Partly.** `ReActAgent` fails open with a warning; there is no way to mark a sink mandatory. | E |

## Phasing

```
Phase 1  (exposure)      A  Redaction            B  Journal race
Phase 2  (hygiene)       C  Dependency versions  D  Policy docs + outbound-tool defaults
Phase 3  (structure)     F  HarnessExecutor decomposition, F0..F8, one PR each
Phase 4  (optional)      E  Audit fail-closed flag   (independent of F; implemented in ReActAgent as it stands)
```

A and B are independent and can run in parallel. `ReActAgent` is not being restructured, so A and E change it in place.

---

## Workstream A: stop sensitive data reaching logs and audit (P1)

**Goal:** synthetic secrets and PII never appear in application logs or audit files unless a raw-debug mode is
turned on explicitly.

Steps
1. Write the failing test first. Run a `ReActAgent` with a stub tool that returns a synthetic API key, an email
   and a bearer token. Capture SLF4J output (a test appender) and read the `FileAuditLogger` output. Assert none of
   them appear. It fails today.
2. Move content logging at `ReActAgent.java:323`, `:550` and `:715` from INFO to DEBUG. Keep metadata at INFO
   (iteration number, tool name, observation length, tool-call count).
3. Add a redaction hook to `FileAuditLogger`: a `Function<String,String>` applied to tool input/output and the
   final answer before write, defaulting to the existing privacy masker. Check what `privacy/` already offers
   before writing anything new.
4. Add the raw-debug opt-in as an explicit builder flag, off by default, with a Javadoc warning.
5. Reconcile the docs: either the code now matches "audit logs record kinds, not data", or the OWASP page says
   precisely what the plain-Java path records. Add retention, file-permission and at-rest-encryption notes to
   `SECURITY.md`.
6. Check the Loom path (`HoldingAuditLogger`, `HarnessExecutor` trace listeners) for the same leak with the same test.

Definition of done: the new test passes; `FileAuditLoggerTest` and `ReActAgent` tests still pass; docs reconciled.
Risk: low. Users who rely on INFO-level content logs lose them; call out in the release notes.
Estimate: 1 day.

## Workstream B: make journal writes race-safe and state the guarantees (P1)

**Goal:** concurrent writers on one `(run_id, step_id)` end with a defined outcome, and the crash semantics are
written down.

Steps
1. Test first: two threads call `JdbcRunJournal.put` on the same key against H2 (check which database the existing
   journal tests use), many iterations. Today some iterations throw.
2. Fix `put`: on a duplicate-key `SQLException` (SQLState `23xxx`), retry the UPDATE once. Last-writer-wins is the
   documented semantic, since a step's entry is overwritten as the step progresses. Wrap the pair in one
   transaction. Optionally use a per-dialect upsert (`MERGE` / `ON CONFLICT`) behind a small detector, with the
   retry path as the fallback.
3. Crash-injection test around `TaskRunner` (`effect_pending` written, then the process dies before `done`):
   resume and assert the policy outcome. Extend `DurableRunTest` rather than starting a new harness.
4. Document in `RunJournal`'s Javadoc and the Loom guide: single vs multi-writer assumptions, durability level,
   and that effects are **at-least-once with the idempotency key**, not exactly-once.

Definition of done: concurrent-writer test is green over 1,000 iterations; crash-recovery test asserts the
documented policy; guarantees documented.
Risk: low. The change is confined to one class.
Estimate: 1 to 1.5 days.

## Workstream C: one Jackson version (P2)

Steps
1. Find out why `loom/ctk` pins 2.18.11 (compatibility with a consumer?). Keep it as a documented exception if
   there is a real reason.
2. Import `jackson-bom` in the parent's `dependencyManagement` at the root version; delete the per-module
   `jackson.version` properties and explicit versions. `ai-agent4j-loom` already imports the BOM, so use it as the
   template. Note that `eval4j-report` shades Jackson; confirm the shade config still resolves.
3. Add `maven-enforcer-plugin` with `dependencyConvergence`, and run it in `make test`.
4. Run `make test-extended` once to catch cross-module incompatibilities from the 2.21 to 2.22 move.

Definition of done: `mvn help:effective-pom` shows one Jackson version everywhere (except any documented
exception); the enforcer rule fails if drift returns.
Risk: low to medium (a minor Jackson upgrade). Estimate: half a day.

## Workstream D: policy defaults and budget scope (P1, mostly docs and defaults)

Steps
1. **Budget scope.** Add an explicit "what a budget covers" section to the README and Loom budget docs: model
   tokens, calls and cost are covered; embeddings, tool execution and channel ingress are not. Have `weave audit`
   state this in its LA03 output. Metering embeddings is deferred (see Out of scope).
2. **Outbound tool approval.** Make email, webhooks and writing HTTP tools require approval unless explicitly
   marked unattended, mirroring what `shell` already does. This is a behaviour change: add `weave check`
   diagnostics that name the migration, and a note in `MIGRATION_GUIDE`. Update LLM06's "Gaps" cell.
3. **Tenant isolation.** Do not build it now. Document the supported pattern (one index per audience, as already
   advised) and add one negative test proving two indexes never cross-return. Record per-tenant retrieval control
   as a roadmap item with an owner.

Definition of done: docs state the exact budget scope; outbound tools fail closed without an approval decision;
`weave audit` and `weave check` agree with the docs.
Risk: medium for step 2 (it breaks scripts that rely on the old default), so ship it behind a clear migration
message. Estimate: 2 days.

## Workstream F: decompose `HarnessExecutor` (P2, structure only)

Goal: split `HarnessExecutor` (2,884 lines, about 145 methods) so each responsibility has one home, collaborators
depend on narrow interfaces instead of the whole executor, and a future change to one concern (execution scope,
agent construction, limits, statements) touches one class and not a spread of others. **No behaviour change and no
public API change in this workstream.** New features are not planned here; the seams are chosen from the code.
`ReActAgent` is out of scope (kept as is).

### What the code shows

- **The executor is both the engine and everyone's back-reference.** `TaskRunner`, `ApprovalGate`, `RunEffectContext`,
  `AgentGuard`, `Rewinder`, `Decider` and `RecordingTool` are each constructed with the executor and call back
  into its package-private members. Measured, those calls are a small set: `trace`, `audit`, `journal`/`getJournal`,
  `currentStep`, `identityStep`, `clock`, `sleeper`, `maskPii`, `simulating`, `humanInterface`, `approvals`. A narrow
  interface covers them.
- **`initialize()` (about 210 lines) is a resource-setup responsibility.** It configures audit, validates the script,
  boots MCP servers, builds budgets and knowledge bases, builds every agent (guards, memory, voice, retrievers,
  tools) and starts schedules. It owns eight registries (`activeAgents`, `mcpClients`, `ragAgents`,
  `memoryServices`, `schedulers`, `guards`, `voices`, `retrievers`...).
- **Per-run execution state is scattered.** Step id, block frames, block-local names, budget scopes and invoker
  live in `ThreadScope` or in separate `ThreadLocal`s (`frames`, `rootNext`, `attempt`); the variable context is a
  plain field that `executeCall` replaces by reflection (`setInternalContext`, on a `final` field).
- **About 30 public setters** make the executor a mutable bag of collaborators, and they are public API used by
  the CLI, tests and example apps, so they stay.

### Target shape

`HarnessExecutor` stays as the public façade and coordinator (same constructor, setters and `LoomEngine` methods)
but delegates:

| New unit | Owns | Moves out of |
|---|---|---|
| `RunServices` (interface; the executor implements it) | The narrow read-mostly surface collaborators need: journal, trace, audit, clock, sleeper, step ids, PII masking, simulate flag | The back-references in `TaskRunner`, `ApprovalGate`, `RunEffectContext`, `AgentGuard`, `RecordingTool`, `Rewinder`, `Decider` |
| `ScriptChecks` (extends the existing `ScriptValidator`) | Provider, model, agent-setting, memory, voice, guard, knowledge and tool/approval checks | `check*` methods, `validationContext` |
| `PromptResolver` | System prompt, persona, skills, fallback prompt | `resolveSystemPrompt`, `basePrompt`, `persona`, `resolveSkills`, `fallbackSystemPrompt` |
| `RunLimits` | Budgets, metering, spend restore and report, rate-limit waits, ask-for-more, budget scopes | `setUpBudgets`, `metered`, `journalUsage`, `restoreSpend`, `newBudget`, `onLimit`, `runUnderLimits`, `askForMore`, `refills`, budget fields |
| `RunResources` | MCP clients, knowledge indexes, agents and their guards, memory, voice and retrievers, schedulers: build in `initialize()`, release in `shutdown()` | `initialize`, `shutdown`, the registries |
| `ExecutionScope` (extends `ThreadScope`) | Everything that is "where this thread is in the run": step, block frames, block-local names, variable context, budget scopes, invoker, attempt | `frames`, `rootNext`, `attempt`, the `context` field and the reflection in `setInternalContext` |
| `StatementExecutor` per kind, behind a dispatcher | `executeStatement` becomes a lookup; `executeDelegate`, `executeForEach`, `executeCall`, parallel, broadcast, human, sleep move to their own classes working on `ExecutionScope` and `RunServices` | `executeStatement` and the `execute*` methods |
| `RunConfig` (package-private holder) | The values the setters set; the public setters become one-line pass-throughs | The setter fields |

Dependency direction: statement executors depend on `RunServices`, `ExecutionScope`, `RunLimits` and
`RunResources`; none of those depend on the executor or on each other except through `RunServices`.

### Steps (each its own PR; each starts with characterization tests, each is a pure move)

| Step | Work | Risk | Done when |
|---|---|---|---|
| F0 | Baseline: record the current test results for `ai-agent4j-loom` (and the modules that use the executor), plus golden outputs: `weave check` over the sample scripts, journal key shapes for a representative resumed run, spend report for a budgeted run | none | Baseline recorded in the PR; golden tests committed and green |
| F1 | **`RunServices`**: add the interface, implement it on the executor, switch the seven collaborators to depend on it | low (mechanical) | No collaborator imports `HarnessExecutor`; tests unchanged |
| F2 | **`ScriptChecks`**: move the `check*` methods behind the existing validator | low | `weave check` output byte-identical on all samples |
| F3 | **`PromptResolver`** | low | Prompt output identical on samples |
| F4 | **`RunLimits`** | medium (spend and resume) | Budget, rate-limit and resume tests unchanged; new test for `restoreSpend` after resume |
| F5 | **`RunResources`**: move `initialize`/`shutdown` and the registries | medium | Lifecycle tests unchanged; `shutdown` releases everything it built (new test) |
| F6 | **`ExecutionScope`**: fold the per-thread state and the variable context into one object carried by `ThreadScope`; remove the reflection | medium-high (concurrency) | New tests: `call` inside `parallel` and `for_each`; scope never leaks across pooled threads |
| F7 | **Statement executors** behind a dispatcher; start with `call` and `for_each`, then `delegate` last | high (largest, most entangled) | `DurableRunTest`, `ForEachAndResilienceTest`, `ConcurrentTopLevelRunsTest`, `RunControlTest` unchanged |
| F8 | **`RunConfig`**: setters delegate to a holder | low | Public API unchanged (API-compat check) |

Order is by risk and by dependency: F1 first because every later extraction would otherwise inherit the
back-reference. F6 and F7 last because they need the smaller class around them. Stop after any step; each leaves the
code better than before.

After F8: `HarnessExecutor` should be a coordinator of well under 1,000 lines (lifecycle, wiring, public API).

Estimate: F0 0.5 day, F1 1 day, F2 0.5, F3 0.5, F4 1.5, F5 1.5, F6 2, F7 3, F8 0.5; about 11 days.

## Workstream E: audit failure policy (P2)

Steps
1. Add `AuditPolicy { BEST_EFFORT, MANDATORY }` on the audit logger (default `BEST_EFFORT`, which preserves
   today's behaviour).
2. In `ReActAgent`'s tool-execution path (a small change in the existing code): `BEST_EFFORT` logs a warning and increments a failure counter exposed for
   health checks; `MANDATORY` aborts the step with a distinct status (not the same as a tool or model failure).
3. Tests for both modes, including an audit sink that throws.

Done when: both modes are tested and the run status distinguishes an audit failure from a tool failure.
Estimate: 1 day.

---

## Order and total effort

| PR | Workstream | Estimate | Depends on |
|---|---|---|---|
| 1 | A | 1 day | none |
| 2 | B | 1 to 1.5 days | none (parallel with 1) |
| 3 | C | 0.5 day | none |
| 4 | D | 2 days | none |
| 5 to 13 | F0 to F8 | about 11 days | none; F1 first |
| 10 | E | 1 day | none |

Roughly 17 working days in sequence (F is now the full `HarnessExecutor` decomposition). Phases 1 and 2 (PRs 1 to 4) are about 5 days and remove every
finding the review rated P1.

## Out of scope (deliberately)

- Embedding-cost metering and tool-call budgets: document the gap first, build only if users hit it.
- Per-tenant retrieval access control: a design task of its own.
- Encryption at rest for file-based indexes and ledgers: documented, not built.
- Restructuring `ReActAgent` (kept as is; only the logging and audit changes in A and E touch it).
- A formal penetration test or concurrency harness beyond the targeted tests above.

## Risks

| Risk | Mitigation |
|---|---|
| Refactor changes journal or resume behaviour | Characterization tests first; pure moves; `DurableRunTest` and `ConcurrentTopLevelRunsTest` run on every F PR |
| An extraction changes behaviour unnoticed | Golden outputs in F0 (`weave check`, journal key shapes, spend report); pure moves only; stop on any diff |
| INFO to DEBUG surprises users who read logs | Release note; raw-debug opt-in flag |
| Outbound-tool approval default breaks scripts | `weave check` migration diagnostic; migration-guide entry |
| Jackson bump breaks a consumer or the shaded report jar | `make test-extended` once; keep a documented `ctk` exception if needed |
| Solo-maintainer bandwidth | Phases are independently valuable; stop after any phase |

## Decisions needed from you

1. Is making outbound tools require approval by default acceptable as a breaking change (D2), or should it be
   opt-in until the next major version?
2. Is `loom/ctk` pinned to Jackson 2.18 for a reason I should preserve?
