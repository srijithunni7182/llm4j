# Implementation Plan

Order: foundations first, then the kinds from least to most dangerous, so each step is testable alone and
`webhook`/`email`/`http`/`file` (which make the digest use case script-only) land before `shell` and `sql`.

- [ ] 1. **Foundations (R1)**
  - [ ] 1.1 `ToolKind` defaults (`prefixes`, `sideEffect`); `ToolFactory.problems` accepts prefixed options
        and the universal `description:`; `DescribedTool`.
  - [ ] 1.2 Parser: quoted option keys in `tool` blocks; grammar and error messages.
  - [ ] 1.3 `Options` (durations, sizes, ints, bools, lists, choices, headers, `isSecretHeader`), with
        the header-secret rule applied at load.
  - [ ] 1.4 `Redactor`, `Limits`, and the `GenericTool` base (error-to-text wrapper, description building,
        audit/trace through `EffectContext`).
  - [ ] 1.5 Unit tests for each; an existing-suite run to prove the old kinds are unchanged.
- [ ] 2. **Effect journal (R2)**
  - [ ] 2.1 `CanonicalArgs` extracted from `ApprovalGate.key` (behaviour unchanged).
  - [ ] 2.2 `EffectContext` and `EffectTool`: keys, pending/done/failed, replay, `on_unknown`, per-run
        counts from `journal.all()`, the `isEffect(args)` hook.
  - [ ] 2.3 `ToolFactory.create(def, env, baseDir, ctx)`; executor passes the context (audit, trace,
        journal, step, reserved paths).
  - [ ] 2.4 Tests with a fake kind: replay, unknown outcome (each policy), identical vs different calls,
        parallel steps, failure then retry, in-memory and file journals.
- [ ] 3. **Egress policy (R3)**
  - [ ] 3.1 `NetPolicy` (scheme, userinfo, host list, address classes, IPv4-mapped IPv6, loopback exception).
  - [ ] 3.2 `HttpSupport`: OkHttp client with the checking `Dns`, manual bounded redirects, capped reads,
        retries with `Retry-After`, redacted error excerpts.
  - [ ] 3.3 Tests against MockWebServer and a stub resolver.
- [ ] 4. **`webhook` (R4)** — `WebhookKind`, the four body formats, retries, idempotency key.
- [ ] 5. **`file` (R7)** — `FileKind`, `permit`, atomic write, locked append, reserved paths.
- [ ] 6. **`http` (R6)** — `HttpKind`, path validation and matching, body and query building, content
      types, per-call `isEffect`.
- [ ] 7. **`email` (R5)**
  - [ ] 7.1 Add Angus Mail to the pom (test: GreenMail). `EmailSender` isolated so the library isn't
        loaded unless used.
  - [ ] 7.2 `EmailKind`: address parsing and patterns, injection checks, limits, attachments, `outbox`.
  - [ ] 7.3 Session security options and error redaction.
- [ ] 8. **`shell` (R8)**
  - [ ] 8.1 `ShellKind`: PATH resolution, interpreter deny-list, argv run, environment clearing, capped
        output, process-tree kill.
  - [ ] 8.2 The approval-or-`unattended` load rule in `checkToolsAndApprovals`.
- [ ] 9. **`sql` (R9)**
  - [ ] 9.1 `SqlGuard` token scanner; `SqlKind` (read-only connection, bound params, row and byte caps,
        `schema`); `ResultTable`.
  - [ ] 9.2 Packaging: PostgreSQL driver in the `weave` JAR; a clear load error when a driver is missing.
- [ ] 10. **Docs, tooling, sample (R10)**
  - [ ] 10.1 `LOOM_GUIDE.md` "Generic Tools"; `generic_tools_examples.loom` and its parse-and-validate test.
  - [ ] 10.2 VS Code grammar and language server; `LOOM_PROMPT.md`; READMEs; gap-analysis status.
  - [ ] 10.3 `samples/digest/` (`digest.loom`, `digest-slack.loom`, `run.sh`) and its test against
        MockWebServer.
- [ ] 11. **Full suites green and results recorded in `verification.md`**
- [ ] 12. *(optional, needs accounts)* Live checks (L1–L4).
