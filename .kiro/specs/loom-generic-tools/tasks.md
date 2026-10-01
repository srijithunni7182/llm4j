# Implementation Plan

Order: test support and foundations first, then the kinds from least to most dangerous, so each step is
testable alone and `webhook`/`file`/`http`/`email` (which make the digest use case script-only) land before
`shell` and `sql`. Each kind's task includes its L0/L1 tests, its hostile-model attacks and its failure
paths; nothing is "tested later". See [test-strategy.md](test-strategy.md).

- [x] 1. **Test support (strategy §3, §11)**
  - [x] 1.1 `generic/support/`: `ScriptedModel`, `FaultJournal`, `StubResolver`, `EchoServer` (MockWebServer
        dispatcher), `RecordingEffects`, a scripted SMTP socket server, and a journal-matrix factory
        (memory, file, JDBC/H2).
  - [x] 1.2 pom: GreenMail (test scope); surefire properties (`user.language=en`, `user.timezone=UTC`);
        the `loom.live` and `loom.fuzz.iterations` switches; `@Tag("live")` excluded by default.
  - [x] 1.3 JaCoCo `check` rule for the Loom module (80% lines) and per-class 90% branches for the guard
        classes, wired into the existing "Unit Tests & Coverage" stage.
- [x] 2. **Foundations (R1)**
  - [x] 2.1 `ToolKind` defaults (`prefixes`, `sideEffect`); `ToolFactory.problems` accepts prefixed options
        and the universal `description:`; `DescribedTool`.
  - [x] 2.2 Parser: quoted option keys in `tool` blocks; grammar and error messages.
  - [x] 2.3 `Options` (durations, sizes, ints, bools, lists, choices, headers, `isSecretHeader`), with the
        header-secret rule applied at load. Fuzz F5.
  - [x] 2.4 `Redactor`, `Limits`, and the `GenericTool` base (error-to-text wrapper, description building,
        audit and trace through `EffectContext`). Fuzz F4.
  - [x] 2.5 Run the existing suites to prove the old kinds are unchanged (V1.9).
- [x] 3. **Effect journal (R2)**
  - [x] 3.1 `CanonicalArgs` extracted from `ApprovalGate.key` (behaviour unchanged, V2.11).
  - [x] 3.2 `EffectContext` (with `Sleeper`, `Clock`, reserved paths, `noop()`) and `EffectTool`: keys,
        pending/done/failed, replay, `on_unknown`, per-run counts, the `isEffect(args)` hook.
  - [x] 3.3 `ToolFactory.create(def, env, baseDir, ctx)`; the executor passes the context.
  - [x] 3.4 V2 checks on all three journals; concurrency C1.
- [x] 4. **Egress policy (R3)**
  - [x] 4.1 `NetPolicy` with `Resolver` seam (scheme, userinfo, host list, address classes, IPv4-mapped IPv6,
        loopback exception).
  - [x] 4.2 `HttpSupport`: OkHttp client with the checking `Dns`, bounded redirects, capped reads, retries
        through the sleeper, redacted error excerpts.
  - [x] 4.3 V3 checks. Mutation pass for `NetPolicy`.
- [x] 5. **`webhook` (R4)** — `WebhookKind`, body formats, retries, idempotency key, the failure table
      (§7.2). V4, H1, C1.
- [x] 6. **`file` (R7)** — `FileKind`, `permit`, atomic write, locked append, reserved paths. V7, H4, F2
      (file side), C2, C4.
- [x] 7. **`http` (R6)** — `HttpKind`, path validation and matching, body and query building, content
      types, per-call `isEffect`, failure paths. V6, H2, F2 (http side).
- [x] 8. **`email` (R5)**
  - [x] 8.1 Add Angus Mail to the pom; `EmailSender`, `SmtpSender`, `OutboxSender`; lazy loading (V5.9).
  - [x] 8.2 `EmailKind`: address parsing and patterns, injection checks, limits, attachments, `outbox`.
        Fuzz F3.
  - [x] 8.3 Session security options, partial-send off, the SMTP failure stages (§7.3), redaction.
  - [x] 8.4 V5, H3, C3.
- [x] 9. **`shell` (R8)**
  - [x] 9.1 `ShellKind`: PATH resolution, interpreter deny-list, argv run, environment clearing, capped
        output, process-tree kill, Windows refusal.
  - [x] 9.2 The approval-or-`unattended` load rule in `checkToolsAndApprovals`.
  - [x] 9.3 V8, H5.
- [x] 10. **`sql` (R9)**
  - [x] 10.1 `SqlGuard` token scanner and its reference tokenizer; `SqlKind` (read-only connection, bound
        params, row and byte caps, `schema`); `ResultTable`. Fuzz F1. Mutation pass for `SqlGuard`.
  - [x] 10.2 Packaging: PostgreSQL driver in the `weave` JAR; a clear load error when a driver is missing.
  - [x] 10.3 V9, H6.
- [x] 11. **Docs, tooling, sample (R10)**
  - [x] 11.1 `LOOM_GUIDE.md` "Generic Tools"; a test that reads the section and validates every block in it.
  - [x] 11.2 VS Code grammar and language server; `LOOM_PROMPT.md`; READMEs; gap-analysis status.
  - [x] 11.3 `samples/digest/` (`core.loom`, `digest.loom`, `digest-slack.loom`, `run.sh`), its L3 test, and the test that
        runs the documented CLI commands (V10.7).
- [x] 12. **Cross-cutting suites**
  - [x] 12.1 Hostile-model suite completed for all six tools (V12), including the secrets assertion.
  - [x] 12.2 Concurrency C5 (repeat under random order).
  - [x] 12.3 Mutation pass recorded for `NetPolicy`, `SqlGuard`, path/`permit`, `Redactor`, `EffectTool`,
        and the address validator.
- [x] 13. **Done criteria (strategy §8)**: L0–L3 and fuzz green twice in a row, coverage gates pass, the
      existing suites unchanged, results recorded in `verification.md`.
- [ ] 14. *(optional, needs accounts)* Live checks L1–L4.
- [x] 15. **Completion verification ([completion-verification.md](completion-verification.md))**
  - [x] 15.1 Tag every test with its check ID (`@Tag`/`@DisplayName`), as the plan describes.
  - [x] 15.2 Write and review `scripts/verify-generic-tools.sh` (G1, G2, G4, G6, G8 and the reports for
        G3, G7, G9); wire G1, G2 and G8 into the Jenkins "Unit Tests & Coverage" stage.
  - [x] 15.3 Run G0–G9 on a clean checkout, by someone other than the implementer where possible (here: run by the
        implementer, with a separate `/security-review` pass; see `SIGN-OFF.md`); commit the
        `evidence/` folder and `SIGN-OFF.md`.
