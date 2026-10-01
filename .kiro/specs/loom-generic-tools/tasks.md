# Implementation Plan

Order: test support and foundations first, then the kinds from least to most dangerous, so each step is
testable alone and `webhook`/`file`/`http`/`email` (which make the digest use case script-only) land before
`shell` and `sql`. Each kind's task includes its L0/L1 tests, its hostile-model attacks and its failure
paths; nothing is "tested later". See [test-strategy.md](test-strategy.md).

- [ ] 1. **Test support (strategy §3, §11)**
  - [ ] 1.1 `generic/support/`: `ScriptedModel`, `FaultJournal`, `StubResolver`, `EchoServer` (MockWebServer
        dispatcher), `RecordingEffects`, a scripted SMTP socket server, and a journal-matrix factory
        (memory, file, JDBC/H2).
  - [ ] 1.2 pom: GreenMail (test scope); surefire properties (`user.language=en`, `user.timezone=UTC`);
        the `loom.live` and `loom.fuzz.iterations` switches; `@Tag("live")` excluded by default.
  - [ ] 1.3 JaCoCo `check` rule for the Loom module (80% lines) and per-class 90% branches for the guard
        classes, wired into the existing "Unit Tests & Coverage" stage.
- [ ] 2. **Foundations (R1)**
  - [ ] 2.1 `ToolKind` defaults (`prefixes`, `sideEffect`); `ToolFactory.problems` accepts prefixed options
        and the universal `description:`; `DescribedTool`.
  - [ ] 2.2 Parser: quoted option keys in `tool` blocks; grammar and error messages.
  - [ ] 2.3 `Options` (durations, sizes, ints, bools, lists, choices, headers, `isSecretHeader`), with the
        header-secret rule applied at load. Fuzz F5.
  - [ ] 2.4 `Redactor`, `Limits`, and the `GenericTool` base (error-to-text wrapper, description building,
        audit and trace through `EffectContext`). Fuzz F4.
  - [ ] 2.5 Run the existing suites to prove the old kinds are unchanged (V1.9).
- [ ] 3. **Effect journal (R2)**
  - [ ] 3.1 `CanonicalArgs` extracted from `ApprovalGate.key` (behaviour unchanged, V2.11).
  - [ ] 3.2 `EffectContext` (with `Sleeper`, `Clock`, reserved paths, `noop()`) and `EffectTool`: keys,
        pending/done/failed, replay, `on_unknown`, per-run counts, the `isEffect(args)` hook.
  - [ ] 3.3 `ToolFactory.create(def, env, baseDir, ctx)`; the executor passes the context.
  - [ ] 3.4 V2 checks on all three journals; concurrency C1.
- [ ] 4. **Egress policy (R3)**
  - [ ] 4.1 `NetPolicy` with `Resolver` seam (scheme, userinfo, host list, address classes, IPv4-mapped IPv6,
        loopback exception).
  - [ ] 4.2 `HttpSupport`: OkHttp client with the checking `Dns`, bounded redirects, capped reads, retries
        through the sleeper, redacted error excerpts.
  - [ ] 4.3 V3 checks. Mutation pass for `NetPolicy`.
- [ ] 5. **`webhook` (R4)** — `WebhookKind`, body formats, retries, idempotency key, the failure table
      (§7.2). V4, H1, C1.
- [ ] 6. **`file` (R7)** — `FileKind`, `permit`, atomic write, locked append, reserved paths. V7, H4, F2
      (file side), C2, C4.
- [ ] 7. **`http` (R6)** — `HttpKind`, path validation and matching, body and query building, content
      types, per-call `isEffect`, failure paths. V6, H2, F2 (http side).
- [ ] 8. **`email` (R5)**
  - [ ] 8.1 Add Angus Mail to the pom; `EmailSender`, `SmtpSender`, `OutboxSender`; lazy loading (V5.9).
  - [ ] 8.2 `EmailKind`: address parsing and patterns, injection checks, limits, attachments, `outbox`.
        Fuzz F3.
  - [ ] 8.3 Session security options, partial-send off, the SMTP failure stages (§7.3), redaction.
  - [ ] 8.4 V5, H3, C3.
- [ ] 9. **`shell` (R8)**
  - [ ] 9.1 `ShellKind`: PATH resolution, interpreter deny-list, argv run, environment clearing, capped
        output, process-tree kill, Windows refusal.
  - [ ] 9.2 The approval-or-`unattended` load rule in `checkToolsAndApprovals`.
  - [ ] 9.3 V8, H5.
- [ ] 10. **`sql` (R9)**
  - [ ] 10.1 `SqlGuard` token scanner and its reference tokenizer; `SqlKind` (read-only connection, bound
        params, row and byte caps, `schema`); `ResultTable`. Fuzz F1. Mutation pass for `SqlGuard`.
  - [ ] 10.2 Packaging: PostgreSQL driver in the `weave` JAR; a clear load error when a driver is missing.
  - [ ] 10.3 V9, H6.
- [ ] 11. **Docs, tooling, sample (R10)**
  - [ ] 11.1 `LOOM_GUIDE.md` "Generic Tools"; `generic_tools_examples.loom` and its parse-and-validate test.
  - [ ] 11.2 VS Code grammar and language server; `LOOM_PROMPT.md`; READMEs; gap-analysis status.
  - [ ] 11.3 `samples/digest/` (`digest.loom`, `digest-slack.loom`, `run.sh`), its L3 test, and the test that
        runs the documented CLI commands (V10.7).
- [ ] 12. **Cross-cutting suites**
  - [ ] 12.1 Hostile-model suite completed for all six tools (V12), including the secrets assertion.
  - [ ] 12.2 Concurrency C5 (repeat under random order).
  - [ ] 12.3 Mutation pass recorded for `NetPolicy`, `SqlGuard`, path/`permit`, `Redactor`, `EffectTool`,
        and the address validator.
- [ ] 13. **Done criteria (strategy §8)**: L0–L3 and fuzz green twice in a row, coverage gates pass, the
      existing suites unchanged, results recorded in `verification.md`.
- [ ] 14. *(optional, needs accounts)* Live checks L1–L4.
- [ ] 15. **Completion verification ([completion-verification.md](completion-verification.md))**
  - [ ] 15.1 Tag every test with its check ID (`@Tag`/`@DisplayName`), as the plan describes.
  - [ ] 15.2 Write and review `scripts/verify-generic-tools.sh` (G1, G2, G4, G6, G8 and the reports for
        G3, G7, G9); wire G1, G2 and G8 into the Jenkins "Unit Tests & Coverage" stage.
  - [ ] 15.3 Run G0–G9 on a clean checkout, by someone other than the implementer where possible; commit the
        `evidence/` folder and `SIGN-OFF.md`.
