# Phase 36: design

Status: **draft.** Facts marked (read) come from the source on the phase 35 branch at the time of writing; anything marked (not read) must be read before the work that depends on it starts. Principles: [PRINCIPLES.md](PRINCIPLES.md). Choices: [DECISIONS.md](DECISIONS.md). **This phase builds on phase 35** (`ToolProfile`, the kind registry, `sandbox`, `workspace`) and starts only after 35 is merged.

## 1. What the code does today (read)

**OpenAPI** (`src/ai-agent4j/.../agent/tools/openapi/`, 1,000 lines in six files; Loom's `tools/OpenApiKind.java`):
- One tool, `OpenAPITool`, takes `endpoint`, `method`, `parameters` and `body`; its description lists every endpoint of the spec. The request builder handles GET, POST, PUT and DELETE; a `SUPPORTED_METHODS` check refuses others.
- It uses `HttpClient.newHttpClient()` directly: **no `NetPolicy`** (the SSRF guard the repository's rule requires), **no request timeout, no retries, no `Retry-After`.** Only the first `servers` entry of the spec is used and there is no override.
- A successful answer is **cut at 2,000 characters**; the response body is logged at INFO after credentials are scrubbed.
- Auth is a key in a header or in the query, with one retry on a 401 when the credential can refresh (`RequestAuth.Resolved.canRetry`).
- `OpenApiKind` takes `spec` (required) and `auth_header`, `auth_query`, `auth_value` (a secret): no operation list, no base URL, no timeout or size option.
- `OpenAPIParser` uses `io.swagger.parser.v3` and reads a URL, a file or a string. `swagger-parser` 2.1.19 is a dependency of the **core** `pom.xml`: 13 jars and about 1.7 MB under `io/swagger` in the local repository, **not counting transitive jars of other groups** (the dependency plugin cannot resolve in the build environment, so a full tree was not obtained; `mvn dependency:tree` gives it where it can).
- The guide says an `openapi` tool may declare `effects: { operationId: reads|changes }`; an undeclared operation is assumed `changes`, a GET included.

**`http`** (`src/ai-agent4j-tools/.../HttpKind.java`, `HttpSupport.java`): `base_url`, `methods`, `allow_paths`, `auth_*`, `max_bytes`, `follow_redirects`, header prefixes and the shared network options; `HttpSupport` has `NetPolicy`, timeouts, retries with backoff and `Retry-After`, redirects and idempotency. There is no pagination.

**MCP** (`src/ai-agent4j/.../mcp/`, 549 lines; `McpServerDef` and `HarnessExecutor` in Loom):
- `McpClient` sends `initialize` with a fixed `protocolVersion` of `2024-11-05` and no client capabilities, then `tools/list` (no paging) and `tools/call`. It returns the raw result; a tool-level `isError` in a result is not examined. No resources, prompts, sampling or notifications are handled.
- The only transport is `StdioMcpTransport` (a command and an environment map; the server's error stream is inherited).
- **MCP tools reach an agent by a separate path** (`HarnessExecutor`): a loop over the agent's `mcp_servers:` lists each server's tools and adds them directly, **without the `ApprovalTool` and `EvidenceTool` wrappers** that other tools get, and a failed `tools/list` is logged and swallowed, leaving the agent without its tools. (Whether MCP tools honour `approve:` some other way is not read.)
- A script declares `mcp Name { transport: "stdio"  cmd: "..."  effects: {...} }`. The parser accepts nothing else: `cmd` is one string split on whitespace, and **there is no clause for the environment**, so a script cannot give a server an API key. Servers start at engine boot; a failure is a load error.
- `McpEffects` (`security/McpEffects.java`) maps `readOnlyHint` and `destructiveHint` to an effect, and the guide says the hints are used when read. **Nothing in production code calls it; only `McpHintsTest` does.** Its message tells the user to run `weave mcp list`, **a command that does not exist.**

**The MCP specification today (read from the official pages, 2026-07-28 revision; the latest revision found, no page labels any revision "stable"):**
- **Two eras.** The revision removes the `initialize` handshake and protocol-level sessions; each request carries `io.modelcontextprotocol/protocolVersion` in its metadata and, over HTTP, the `MCP-Protocol-Version` header; a mismatch returns `UnsupportedProtocolVersionError` (-32022) with a `supported` list; servers must implement `server/discover`. A modern client talking to a legacy server "fails" (the spec's compatibility matrix); a dual-era client probes (stdio: `server/discover`; HTTP: a modern request, falling back to `initialize` on a 400) and caches the result per server.
- **Transports:** stdio and Streamable HTTP (each message an HTTP POST to one endpoint, answered by JSON or a request-scoped event stream; `Mcp-Method` and `Mcp-Name` headers; no sessions; HTTP+SSE deprecated since 2025-03-26).
- **Authorization** is optional: for HTTP servers OAuth 2.1 with protected-resource-metadata discovery (RFC 9728), authorization-server metadata, client identity by metadata document (dynamic registration deprecated) or pre-registration, a resource indicator (RFC 8707), an issuer check (RFC 9207); for stdio servers the spec says to take credentials from the environment.
- **Tools:** a tool error is a result with `isError: true`; `structuredContent` conforms to an `outputSchema`; `tools/list` pages with `cursor` and `nextCursor`; **clients MUST treat annotations as untrusted unless the server is trusted.** Roots, sampling and logging are deprecated; `ping` and `logging/setLevel` are removed.
- **Not read:** the annotation field names and defaults (on the schema page), the Streamable HTTP binding page.

**Credentials** (`src/ai-agent4j/.../secret/`): `SecretStore` implementations (environment, encrypted file, in memory, chained; `put`, `remove`, `resolveFor(name, host)`), `SecretRef` and `CredentialSource` (asked on every request, with a `rejected()` hook), and `InvokerCredentials`, an interface the host implements to hand a run the credentials of whoever started it (for example an OAuth access token, refreshed on every request): a run takes one (`LoomEngine`, `LoomHost.resume(runId, invoker)`, the generated bindings), a script writes `invoker.NAME`, and Loom's `ToolKind.lateBound()` lists the options that may take it, today only `openapi`'s `auth_value`. **No OAuth flow ships in the library** (and none will, D8); the only OAuth code is in an unpublished example application.

**Record and replay:** `weave capture` turns a run into a test case with the recorded models, classifiers, people, tasks and failures; `weave regress` tries a candidate on past runs with recorded answers only. **No tool results are recorded.**
**Generation and health:** `weave generate` turns a description into a workflow project; `weave doctor` and `weave preflight` check keys, models, the run store and agent probes, not integration endpoints.

**Not read:** the exact `SUPPORTED_METHODS` set, how Loom maps a path and method to an `operationId` for the `effects:` map at run time, the swagger-parser version and its transitive weight, `RequestAuth`'s remaining cases, and the MCP specification's current requirements (transports, authorization, version negotiation), which must be read from the specification when 36b starts.

## 2. The target shape

**Two doors, one control layer.** A system is reached through OpenAPI, MCP, a command, a file or a document, a page, or plain `http`. Every door yields tools that carry, from phase 35's `profile`, an effect, a reach and an isolation, and that run through the same guards (principle 33).

**Declared effects and pins.** The audit runs nothing and reads no files, so it cannot ask an MCP server for its tools or open a spec. The tooling does that once: `weave connect` and `weave mcp list --write` read the spec or the server, propose each operation's effect and retry behaviour, and, when a person approves, write them into the script as declared values with a `pin` (a hash) that later detects drift. `weave check` compares a local spec with its pin offline; `weave doctor --network` compares a remote one. There is no lockfile.

**A guided way in.** `weave connect` reads an OpenAPI file, an MCP server, or a command's help, and writes one tool declaration per chosen operation with the effects proposed and approved and the pin, test fakes from the examples, and runs the audit. Where there is no spec, a model drafts a typed command adapter or an `http` tool, and code verifies the draft against samples before anything is emitted (principle 36).

## 3. Sub-phases

| Sub-phase | What | Gate |
|---|---|---|
| 36a | **Fix what exists.** `openapi` rebuilt on the shared HTTP guards as one tool per operation (`operation:`), with a base URL, a size cap and saved large answers; MCP script surface (environment, arguments, errors, paging, timeouts) and MCP tools given the same wrappers as other tools; `weave mcp list` and the pins; the guide corrected and the docs tests that would have caught the MCP gap | The owner reviews the branch before 36b |
| 36b | **Reach.** The modern MCP era and Streamable HTTP, carrying the consumer's token (D6, D8); `invoker.NAME` accepted by every door; bounded pagination | |
| 36c | **Safe commands.** MCP stdio servers under the isolation rules; the typed `command` kind on the sandbox backend | |
| 36d | **Authoring.** `weave connect`, deterministic first, model-assisted for systems without a spec | |
| 36e | **Replay and health.** Tool results in `capture` and `regress`; drift and endpoint checks | |
| 36f | **The ecosystem** pass and the time-to-first-safe-call benchmark | |

## 4. Risks (for the owner to weigh against the decisions)

1. **Moving OpenAPI out of the core is a compatibility break** for Java users of `io.github.llm4j.agent.tools.openapi` (D2). It is the price of a light core and of one set of guards.
2. **The MCP specification moves.** The client speaks a 2024 revision; the current one removes the handshake. Both eras are supported (D6), and the exact requirements are re-read from the specification when 36b starts (R-items in VERIFICATION).
3. **A token is a secret passing through us.** llm4j does not store or refresh one (D8), but it carries it on every request and must never log it; the canary tests carry that.
4. **A model-drafted adapter is the largest new attack surface in the phase.** The draft is verified against samples and its effects are a person's decision (principle 36); a draft that fails verification is never emitted.
5. **The phase is large and is the last for a while.** The gate after 36a and the order are meant to let the owner stop after any sub-phase with something whole.
