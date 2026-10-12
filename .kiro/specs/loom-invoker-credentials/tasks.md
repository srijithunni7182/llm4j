# Implementation Plan

**Prerequisite:** the design in [design.md](design.md) is reviewed and its open question is answered or deferred. Order: the host API and the tool first (usable from Java on
their own), then the scope, then the script syntax, then audit/CLI/docs. Tests are written with each task. See [verification.md](verification.md).

- [ ] 1. **Prerequisite check** — read what the design marks as not yet verified: whether `ReActAgent.run` keeps state between calls (the open question); every `lookupKey()` and `isReference()` call site (design §2 table is complete); that no code moves work to another thread other than the four sites; that `HttpClient` does not forward `Authorization` across hosts on redirect (V4.4).
- [ ] 2. **Host API** (`ai-agent4j`, `io.github.llm4j.secret`)
  - [ ] 2.1 `InvokerCredentials` (`get`, `invalidate`, `provides`, `none`, `of(Map)`, `of(name, Supplier)`) and `CredentialSource` (`get`, `rejected`). Javadoc states: called per request, any thread, must be thread-safe. V2.11, V5.5.
  - [ ] 2.2 `MissingInvokerCredentialException` (names only). V4.1.
- [ ] 3. **OpenAPITool**
  - [ ] 3.1 `headerAuth(String, CredentialSource)` and `apiKeyAuth(String, CredentialSource)`; resolve per request beside the existing `SecretRef` maps; feed `scrub`. V2.1–V2.3, V2.8, V2.9, V5.4.
  - [ ] 3.2 401: `rejected()`, fetch again, retry once, same body. V2.5–V2.7.
  - [ ] 3.3 https-or-localhost rule for credential sources; redirect test. V4.2, V4.4.
  - [ ] 3.4 Existing `OpenAPIToolTest` unchanged and green. V2.10.
- [ ] 4. **Run scope** (`ai-agent4j-loom`, `execution`)
  - [ ] 4.1 `RunScope` (capture, install, restore) carrying `step`, `locals`, `scopes`; move the four sites (`forkWith`, broadcast, `callWithTimeout`, `runWithTimeout`) onto it **before** adding `invoker`, so the refactor is verified by the existing tests on its own. V3.5.
  - [ ] 4.2 `invokerScope` thread-local; set and cleared by the top-level `executeWorkflow`; nested workflows inherit; `RunScope` carries it. V3.1–V3.4.
  - [ ] 4.3 `executeWorkflow(name, ctx, InvokerCredentials)`; old overload delegates; `LoomEngine` default method. V6.1, V6.2.
  - [ ] 4.4 Run-start check with `provides`. V4.1.
  - [ ] 4.5 Guard: a second concurrent top-level `executeWorkflow` on one executor throws `IllegalStateException` (decided in review); test that it throws and that nested workflows and parallel branches are unaffected. V3.6.
- [ ] 5. **Script syntax and tool factory**
  - [ ] 5.1 `OptionValue.Source.CALLER`, `fromInvoker()`, `isCredential()`, `lookupKey()` throws; fix every call site in the design table. V1.1, V1.7.
  - [ ] 5.2 `LoomParser.optionValue` for `invoker.NAME`, errors with the right form. V1.2.
  - [ ] 5.3 `ToolKind.lateBound()` and the `create` overload; `ToolFactory.problems/resolve/create` pass late-bound suppliers; `ToolFactory.create` overload taking the invoker binding; the `openapi` kind lists `auth_value`. V1.3, V1.5, V1.6.
  - [ ] 5.4 `HarnessExecutor` builds the binding (`invokerScope`-backed) and passes it in `createTool`; providers refuse `invoker.*`. V1.4, V2.1–V2.9.
  - [ ] 5.5 `AgentIdentity`, `Decider.secrets`, `credentialValue`, `Capabilities.opt` handle CALLER. V1.9, V5.6.
- [ ] 6. **Audit and CLI**
  - [ ] 6.1 `SecurityAudit` finding "acts as the invoker" (MEDIUM with write methods, LOW GET-only; notes `approve:`). V4.5.
  - [ ] 6.2 `weave check` prints the invoker credentials a script uses; unused-tool warning still applies. V1.8, V4.6.
  - [ ] 6.3 `weave run --invoker-env NAME=ENVVAR`; triggers and schedules fail clearly. V6.3, V6.4.
- [ ] 7. **Safety suites** — the secrets sweep with echoing servers, suspend/resume with a new token, replay, hostile endpoint arguments. V4.3, V5.1–V5.3.
- [ ] 8. **Editor support** — TextMate grammar and completion for `invoker.` in `vscode-loom`. V6.6.
- [ ] 9. **Docs** — `LOOM_GUIDE.md` section, `LOOM_PROMPT.md`, `OpenAPI-Tool.md`, `llms.txt`/`LLM_CONTEXT.md`; doc-example test covers new blocks. V7.1–V7.3.
- [ ] 10. **Quality** — JaCoCo rule for the new classes, `scripts/sabotage_invoker_credentials.py` (S1–S12), regression run.
- [ ] 11. **Completion** — `scripts/verify-invoker-credentials.sh` (G1–G9), the G5 demo, evidence and `SIGN-OFF.md`.
- [ ] 12. *(optional, separate change)* `http` kind: late-bound `auth_value` through `HttpTool.Config`, `HttpSupport` and `Redactor`; the same V2/V4/V5 checks for it.
- [ ] 13. *(optional, separate spec)* `loom-reentrant-executor`: a `Run` object that owns the per-run fields listed in design §Concurrent runs, adopting `RunScope`. Only after task 4.5's guard test and the open question are answered.
