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
| 6 | God classes | **Yes.** `HarnessExecutor` is 2,884 lines and about 145 methods in 8 jobs; `ReActAgent` has two duplicated loops plus voice and prompt assembly. | F |
| 7 | Audit failure policy | **Partly.** `ReActAgent` fails open with a warning; there is no way to mark a sink mandatory. | E |

## Phasing

```
Phase 1  (exposure)      A  Redaction            B  Journal race
Phase 2  (hygiene)       C  Dependency versions  D  Policy docs + outbound-tool defaults
Phase 3  (structure)     F  Extractions, one PR each (F1..F5)
Phase 4  (optional)      E  Audit fail-closed flag   (pulled forward if F3 lands first)
```

A and B are independent and can run in parallel. F3 makes E cheaper, which is why E sits after it.

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

## Workstream F: structural refactor (P2, the long-term adaptability work)

Ground rules: each step is its own PR; it begins with characterization tests that fail if behaviour moves, and
ends with the same tests green. `HarnessExecutor` and `ReActAgent` public signatures stay put until F5.

| Step | Extraction | From | Seam / notes | Done when |
|---|---|---|---|---|
| F1 | `RunLimits` (budgets, metering, spend restore, rate-limit waits, ask-for-more) | `HarnessExecutor` ~2277–2600 | Already has its own state (`restoreSpend`, `journalUsage`). Existing budget and resume tests are the safety net; add one on `restoreSpend` after resume. | `HarnessExecutor` shrinks by about 350 lines; spend and resume tests unchanged |
| F2 | Script validation into `ScriptValidator` collaborators (`ProviderChecks`, `AgentChecks`, `KnowledgeChecks`) | `HarnessExecutor` ~690–1040 | `ScriptValidator.Checker` already exists. Snapshot-test `weave check` output over the sample scripts before moving. | `weave check` output byte-identical on all samples |
| F3 | Shared tool-dispatch step so `run()` and `runNative()` differ only in output parsing; voice into a `VoiceAgent` decorator | `ReActAgent` | Characterize both protocols with the existing `ToolAgentProtocolTest`, plus loop-detection and approval cases. Audit and redaction (A) then live in one path. | One dispatch path; both loops under ~100 lines; A's test still green |
| F4 | `PromptResolver` (system prompt, persona, skills) | `HarnessExecutor` ~1805–1890 and `ReActAgent` ~910–965 | Pure functions of config; easy to test in isolation. | Prompt output identical on sample scripts |
| F5 | Replace the `protected` hooks (`customizeAgent`, `agentForDelegate`, `beforeDelegateExecution`, `onDelegateReplayed`) with an interceptor interface | `HarnessExecutor` | **Public API change.** Keep the old hooks `@Deprecated`, delegating to the new interface, for one release. Needs an entry in `API_COMPATIBILITY.md`. | Subclassing is no longer the only extension path; deprecation documented |

After F1 to F5, re-measure: `HarnessExecutor` should be under about 1,500 lines, with `executeStatement` and
`executeDelegate` as the remaining large methods, to be split by statement kind in a later pass if it still hurts.

Estimate: F1 1.5 days, F2 1 day, F3 2 days, F4 1 day, F5 1.5 days.

## Workstream E: audit failure policy (P2, after F3)

Steps
1. Add `AuditPolicy { BEST_EFFORT, MANDATORY }` on the audit logger (default `BEST_EFFORT`, which preserves
   today's behaviour).
2. In the single dispatch path from F3: `BEST_EFFORT` logs a warning and increments a failure counter exposed for
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
| 5 to 9 | F1 to F5 | about 7 days | F3 should follow A |
| 10 | E | 1 day | F3 |

Roughly 13 to 14 working days in sequence. Phases 1 and 2 (PRs 1 to 4) are about 5 days and remove every
finding the review rated P1.

## Out of scope (deliberately)

- Embedding-cost metering and tool-call budgets: document the gap first, build only if users hit it.
- Per-tenant retrieval access control: a design task of its own.
- Encryption at rest for file-based indexes and ledgers: documented, not built.
- Splitting `executeStatement` / `executeDelegate` by statement kind: revisit after F1 to F5.
- A formal penetration test or concurrency harness beyond the targeted tests above.

## Risks

| Risk | Mitigation |
|---|---|
| Refactor changes journal or resume behaviour | Characterization tests first; pure moves; `DurableRunTest` and `ConcurrentTopLevelRunsTest` run on every F PR |
| INFO to DEBUG surprises users who read logs | Release note; raw-debug opt-in flag |
| Outbound-tool approval default breaks scripts | `weave check` migration diagnostic; migration-guide entry |
| Jackson bump breaks a consumer or the shaded report jar | `make test-extended` once; keep a documented `ctk` exception if needed |
| Solo-maintainer bandwidth | Phases are independently valuable; stop after any phase |

## Decisions needed from you

1. Is making outbound tools require approval by default acceptable as a breaking change (D2), or should it be
   opt-in until the next major version?
2. Should F5 (replacing the protected hooks) ship in the next minor version with deprecations, or wait for a major?
3. Is `ctk` pinned to Jackson 2.18 for a reason I should preserve?
