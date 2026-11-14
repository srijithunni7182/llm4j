# Phase 36 verification plan: integrations

Status: **final for phase 36; nothing is built.** Section 0 lists what was read and what is still to read; section 1 lists what must be proved. Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md), [phase 35 VERIFICATION](../35-framework-tools/VERIFICATION.md).
**Evidence levels:** E0 read in the source or documents; E1 a unit test; E2 an engine test with scripted models on the controlled scheduler; E3 a kill-and-resume or concurrency test; E4 run by hand on a real system (the owner's gate). **Integration** means an `@Tag("integration")` test that runs only where a network and a container runtime exist. No live model key is needed for the default build; `make test-live` is never run.

## 0. Survey

### Read (E0), with where

All facts are in [DESIGN §1](DESIGN.md#1-what-the-code-does-today-read); the places:
- **OpenAPI:** `src/ai-agent4j/.../agent/tools/openapi/` (`OpenAPITool`, `OpenAPIParser`, `OpenAPIEndpoint`, `RequestAuth`) and Loom's `tools/OpenApiKind.java`.
- **`http`:** `src/ai-agent4j-tools/.../HttpKind.java`, `HttpSupport.java`, `HttpTool.java`, `Limits.java`.
- **MCP:** `src/ai-agent4j/.../mcp/` (`McpClient`, `StdioMcpTransport`, `McpToolAdapter`); Loom's `ast/McpServerDef.java`, the `parseMcpServer` method of `parser/LoomParser.java`, the MCP boot loop and the MCP tool loop of `execution/HarnessExecutor.java`, `security/McpEffects.java`.
- **The two orphaned effect paths:** `security/McpEffects.resolve` (only `McpHintsTest` calls it) and `security/Capabilities.operationEffect` (only `OpenApiOperationEffectsTest` calls it); `ast/ToolDef.getOperationEffects`.
- **Credentials:** `src/ai-agent4j/.../secret/` (`SecretStore`, `CredentialSource`, `InvokerCredentials`); Loom's `tools/ToolKind.lateBound()`; `runtime/LoomEngine.java`, `host/LoomHost.java`, `bind/Bindgen.java`.
- **Capture and test scripting:** `loop/Capture.java`, `wtest/ScriptedTools.java`, `execution/UseRunner.java` (a `use` writes a journal entry of kind `use`).
- **Generation, health, commands:** the `generate/` package (`Gate`, `CheckGate`, `TestsGate`, `ReviewGate`, `RepairLoop`, `DerivedTests`, `Interview`), `doctor/Doctor.java` (a `Check` record; a `Probe` that is a TLS connect with no request and no credentials), `cli/` (`SecretCommands`, `GenerateCommand`, `CaptureCommand`, `RegressCommand`, `DoctorCommand`).
- **Failure categories:** `wtest/Failures.java` (a closed list of seven) and the core's `ErrorCategory`.
- **The MCP specification:** the official pages for the 2026-07-28 revision (changelog, lifecycle, transports, authorization, tools), read through a page-reading helper on 2026-11-14; no page labels a revision "stable".
- **Compatibility:** `docs/API_COMPATIBILITY.md` (deprecated APIs stay at least one minor release cycle when feasible; major versions may break; a replacement comes before a removal).
- **The extension:** `src/loom/vscode-loom/src/lsp/server.ts` (hand-written hover text; the language server serves `check`, `graph` and `review`).

### To read before the work that depends on it (each ends in a short note under this section)

| # | What | Why | When |
|---|---|---|---|
| R1 | The exact `SUPPORTED_METHODS` set, and how Loom maps a path and method to an `operationId` for the legacy `effects:` wiring | D13 | 36a |
| R2 | **How `swagger-parser` fetches a remote spec and remote `$ref`s, and whether it can be stopped or routed through `HttpSupport` and `NetPolicy`;** its version and transitive weight (the dependency plugin did not resolve where the spec was written) | D2, D10, G70: a parser that fetches on its own would bypass `NetPolicy` | 36a, first |
| R3 | Where MCP tools are wrapped, and whether `approve:` and the decision wrapper reach them by any other path | D6, G79 | 36a, first |
| R4 | How a host builds and pools executors: whether MCP clients live across runs or users | D8 rule 3, G75 | 36a |
| R5 | The MCP annotation field names and defaults (the schema page), the Streamable HTTP binding page, which revision each reference server implements, and the specification schemas' licence for vendoring | D6, D15 | 36a (annotations), 36b (the rest) |
| R6 | `StdioMcpTransport`: process lifecycle, shutdown, stderr handling, timeouts | D6, D7 | 36a |
| R7 | How `ToolKind.lateBound()` and the `create(... sources ...)` overload reach a tools-module kind through `GenericKindAdapter`: can `http` accept `invoker.NAME` | D8 rule 1 | 36a |
| R8 | How `Capabilities`, `review`, `whatif` and `PolicyToolHook` treat MCP (assumed all three; the `mcp` flag), and where an `mcp` block's `effects:` and `reach:` must be wired | D13, D14 | 36a |
| R9 | Whether new `check` findings reach the editor through the language server | D18 | 36f |
| R10 | The `tools:` format `ScriptedTools` reads (a list per tool, `repeat: last`) and the journal entry shapes `UseRunner` writes for a read and for an effect | D11 | 36e |
| R11 | Whether `generate`'s `Gate`, `RepairLoop` and patch machinery can be reused to edit a user's script and to gate a drafted tool | D10 | 36d |
| R12 | `Doctor`'s extension points and how `WeaveCLI` registers a command | D12, D16 | 36e, 36d |
| R13 | Every use of `ErrorCategory` and the failure-category list, for adding `credential_rejected` | D16 | 36a |
| R14 | Whether a tool's option can name another tool (35's R2) | D9 `workspace:` | 36c |
| R15 | The state of phase 35 at the start: that `profile`, the kind registry, `sandbox`, `workspace` and the extras structure exist as specified | the dependency of this whole phase | before 36a |
| R16 | Which reference MCP servers and public OpenAPI services are usable for the integration tier and the benchmark, and their licences | D15, D17 | 36a, 36f |
| R17 | The dependency direction and the self-contained jar of the new extras module (35-D4 rule 4); the release configuration for it (35's R4) | D2 | 36a |
| R18 | **What phase 35 left of the `substitution` setting** and how variables are replaced in the merged language; that no new example, fixture, starter or guide text in this phase uses the removed setting; and that tool option values (the `run:` template of D9) are never passed through variable replacement | D9 rule 6, the ecosystem pass | before 36a, and again in 36f |

### Where the code contradicts a decision or the design (filled in by the build)

None yet. If a read shows that a decision cannot be built as written, the session stops at that point, writes the finding here with the evidence, and asks the owner; it does not deviate silently.

## 1. Acceptance: what must be true when the phase is built

Every row is `not built`.

| # | The claim | How | Level |
|---|---|---|---|
| A1 | **`openapi` is a new kind in `ai-agent4j-tools-openapi`** on `HttpSupport`: `NetPolicy`, a timeout, retries with `Retry-After`; the spec parser never fetches on its own; `use: openapi` in existing scripts is unaffected; the old core classes are deprecated with migration notes (G69 to G71, G92) | its tests | E1 |
| A2 | **One typed tool per operation** with its own effect, validated names, and the legacy all-operations mode kept with a warning and LA34 (G76) | its tests | E1 |
| A3 | **A cap that says it cut, and `save_dir`** (G72) | its tests | E1 |
| A4 | **The MCP stdio client is fixed** and MCP tools get the approval and decision wrappers (G79) | its tests | E1, E2 |
| A5 | **MCP programs are isolated as D7 says** at `none` and `process` in 36a (G80) | its tests | E1 |
| A6 | **The two orphaned effect paths are wired,** the guide is corrected, and `EffectDefaultsDocsTest` passes (G77, G78); the orphan report is written | the break-it tests | E1, E2 |
| A7 | **Pins, `weave mcp list`, and the pin warnings of `weave check`** (G88) | its tests | E1 |
| A8 | **Credentials pass in at every door,** per request and per run, with `credential_rejected` as a failure category and an outcome (G73 to G75) | its tests and canaries | E1, E3 |
| A9 | **The modern MCP era and Streamable HTTP,** with the probe and conformance to the official schemas (G81 to G83) | its tests | E1, E2, integration |
| A10 | **Bounded pagination** (G91) | its tests | E1 |
| A11 | **Container isolation for MCP servers and the `command` kind** (G84, G85) | its tests; G85 is integration | E1, integration |
| A12 | **`weave connect`:** deterministic for OpenAPI and MCP, model-assisted for a command and for a documentation page, with every gate (G86, G87) | its tests with scripted models | E1, E2 |
| A13 | **Capture reads `use` results; `doctor` is reachability only; `connect --refresh --check` reports drift** (G89, G90) | its tests | E1, E2 |
| A14 | **The audit rules and `reach:` on `mcp` blocks** (G95), and the security documents of D14 | `SecurityDocTest` and one test each | E1 |
| A15 | **Compatibility holds** for scripts written before this phase, in normal and strict modes (G92); `docs/API_COMPATIBILITY.md` is updated | the tests | E1 |
| A16 | **The fakes are as real as D15 requires:** wire-level HTTP with real TLS, conformance to the official MCP schemas, real recorded fixtures with metadata and a clean `scan` (G82, G94) | the tests; the differential runs are integration | E1, integration |
| A17 | **The provenance oracle passes** with the new canaries (G93) | `make test-oracle` | E3 |
| A18 | **The ecosystem pass is complete** (D17, G96): guide and prompt, wiki, READMEs, `llms.txt`, hover text, snippets, CTK, the skills, the tutorial chapter, the starter, release notes; `make regen` then `make docs-check` clean | `make docs-check` | E1, E2 |
| A19 | **The gate is kept:** the session stopped after 36a, reported what it built and found, and waited for the owner | the report | n/a |
| A20 | **The benchmark** of five real systems is run and its table published with the method, the versions and the date | the owner, by hand | **E4** |

## 1b. What the build reached (kept current; written at the end of each sub-phase)

Nothing is built.

## 2. How the report is written

At the end of each sub-phase the report states, for each row, the level reached and the evidence (the test names), **what was not run** (`make test-full`, the other `slow` tests, every integration test that needed a network or a container runtime the session lacked, anything at E4), the risks of DESIGN §4 that turned out to be real, each R-item read and what it found, and any decision that was changed and why. A row is never marked reached without the test that proves it. **If a session cannot start a container runtime or reach a real service, the integration tests and the differential runs are reported as not run, not as passing.**
