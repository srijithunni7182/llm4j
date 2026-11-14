# Phase 36: decisions

Status: **draft; D1 to D8 are taken, ten decisions are open.** Each gives the question, why it matters, the options and a recommendation. The recommendation is a proposal: the owner decides one at a time and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against; [DESIGN.md](DESIGN.md) holds the facts.
References: "35-D6" is decision D6 of [phase 35](../35-framework-tools/DECISIONS.md). Facts about the code are from DESIGN §1 and are marked (read) or (not read) there.

## Decision log

| | Decision | State |
|---|---|---|
| D1 | The scope and staging of this phase | **taken** (one phase, six sub-phases, one review gate after 36a) |
| D2 | Where OpenAPI lives, and the compatibility plan | **taken** (a new `openapi` kind in an extras module on the shared HTTP layer; scripts switch at once; the old Java classes deprecated, removed at the next major) |
| D3 | The OpenAPI call model: one generic tool, or one tool per operation | **taken** (one declared tool per operation, with `operation:`; the legacy all-operations tool kept and warned about until the next major) |
| D4 | Effects and idempotency taken from the system's own description, and the pins | **taken** (the tooling infers and a person approves; the result is written into the script as declared values with a pin; no lockfile) |
| D5 | Large answers and pagination | **taken** (a cap that says it cut and a `save_dir` in 36a; bounded read-only pagination in 36b) |
| D6 | The MCP client: transports, versions, what it reads | **taken** (both eras, stdio and Streamable HTTP, tools only; stdio and fixes in 36a, the modern era, HTTP and authorization in 36b) |
| D7 | An MCP server or a command as a program: isolation, arguments, secrets | **taken** (the `sandbox` rules, in three states so existing scripts do not break; six rules) |
| D8 | Authentication: the consumer brings the token | **taken** (pass-in only: every door accepts `invoker.NAME`, per request and per run, with typed credential errors; llm4j never acquires, stores or refreshes a token) |
| D9 | Typed command adapters | open |
| D10 | `weave connect`: authoring an integration | open |
| D11 | Recording and replaying tool results | open |
| D12 | Health and drift | open |
| D13 | The documentation and the code agree | open |
| D14 | Security rules and documents | open |
| D15 | Testing: fakes, guards, canaries | open |
| D16 | Names | open |
| D17 | Visibility: the ecosystem and the time-to-first-safe-call benchmark | open |
| D18 | What stays out of this phase | open |

## D1. The scope and staging of this phase

**Why.** The owner has said this is the last framework phase for a while, so it should carry what the verticals need from integrations. But it mixes fixes to existing code, new reach, and an authoring command.

- **A. One phase, six sub-phases** (36a to 36f, [DESIGN §3](DESIGN.md)), with a review gate after 36a (the fixes), starting after phase 35 is merged.
- **B. Fixes only:** 36a, and the rest as separate later phases.
- **C. Everything except authoring** (no `weave connect`, D10).

**Recommendation: A.** The fixes in 36a are correctness and safety problems and should land first and be looked at; the rest is what turns "an integration you can write" into "an integration you can get in minutes". Out of scope are spreadsheet and XML formats and an adapter catalog (D18).

**Decision (owner): option A.** One phase with six sub-phases (36a to 36f, [DESIGN §3](DESIGN.md)): 36a fix what exists; 36b reach (remote MCP, credential pass-in, pagination); 36c safe commands (MCP servers under isolation, the typed `command` kind); 36d `weave connect`; 36e tool-result replay and drift checks; 36f the ecosystem and the benchmark. It starts after phase 35 is merged.
**The gate:** the implementation session **stops after 36a**, reports what it built and what it found, and waits for the owner, who then tells it to continue or to change something. Nothing is merged in between. Out of scope: spreadsheet and XML formats and an adapter catalog (D18).


## D2. Where OpenAPI lives, and the compatibility plan

**Why.** The OpenAPI tool is in the **core** and uses `java.net.http` directly, so it cannot use `NetPolicy` and `HttpSupport` (the tools module, which the core must not depend on); and it pulls `swagger-parser` into the core.

- **A. A new `openapi` kind in an extras module** (`ai-agent4j-tools-openapi`) built on `HttpSupport`, carrying `swagger-parser`; the core drops the dependency; `weave` bundles the module. The old core classes are deprecated for one release and then removed; `docs/API_COMPATIBILITY.md` and a migration note say so.
- **B. Keep the classes in the core** and add the guards there by copying `NetPolicy` into the core (duplicated security code).
- **C. A new `openapi` kind in the tools module with a small Jackson-only reader** for the subset of OpenAPI we need, and no swagger-parser anywhere.

**Recommendation: A.** It gives one set of guards (principle 33) and a light core (principle 8). C avoids the dependency but means writing and maintaining a parser for a large specification, which is a risk of its own. B repeats security code in two places. A is a break for Java users of the old package, and the owner should decide whether a release of deprecation is enough.

**Decision (owner): option A, with this compatibility plan.** A new `openapi` kind in an extras module, `ai-agent4j-tools-openapi`, built on `HttpSupport` and carrying `swagger-parser`; `weave` bundles it, so `use: openapi` keeps working out of the box.
1. **Scripts are unaffected at once:** `use: openapi` switches to the new module immediately.
2. **Java users follow the deprecation policy** (`docs/API_COMPATIBILITY.md`): the new module comes first; the old classes in `io.github.llm4j.agent.tools.openapi` are marked `@Deprecated(forRemoval = true)` in the next 6.x release with migration notes.
3. **Removal waits for the next major:** the core classes and `swagger-parser` leave the core in that release. Until then the core keeps the dependency (measured at about 1.7 MB in 13 jars under `io/swagger`, transitive jars of other groups not counted). Making it `<optional>` in a minor release was considered and refused: it would break Java users at run time without a compile error.
**Why A:** one set of guards (principle 33) and no copy of security code in the core (the core cannot depend on the tools module, which is why the OpenAPI tool lacks `NetPolicy` today). The weight of `swagger-parser` is moderate and is not the reason. B (copying the guards into the core) and C (a Jackson-only reader we would maintain, plus a YAML dependency) were not taken.


## D3. The OpenAPI call model: one generic tool, or one tool per operation

**Why.** Today the agent gets one tool whose arguments are `endpoint` and `method`, with a description of every endpoint; a large API makes a large description, a model chooses a path as free text, and one spec describes both reads and writes under one name.
**What the engine does (read):** an agent's `tools:` names are each resolved to **one tool**, wrapped for approval by that name, and added; policy hooks, `Capabilities`, `approve:` and the audit key off the declared name. MCP is the exception: an agent's `mcp_servers:` go through a separate loop that lists a server's tools and adds each directly, **without the approval and decision wrappers**, and a failed `tools/list` is logged and swallowed (whether MCP tools honour `approve:` some other way is not read; it is a 36a read, see D6).

- **A. One declaration expands into one tool per operation,** as MCP servers do: it changes the engine, the audit, the review facts, the graph and `weave test`'s scripting. Large.
- **B. Keep the single generic tool** with a required `operations:` allow-list and strict validation: small, but the model still builds paths and methods as free text and approval is for the whole tool.
- **C. One declared tool per operation,** each with `operation:` and typed parameters from that operation's own schema, written by `weave connect` (D10).

**Decision (owner): option C.** The owner's reason, which settles it: *one spec describes both a GET and a POST, but their effects differ*; if each operation is its own declared tool, each carries its own effect (a GET reads, a POST changes), approval and audit entry.
```loom
tool Pet_get  { use: openapi  spec: "specs/petstore.json"  operation: "getPetById"  effect: reads }
tool Pet_add  { use: openapi  spec: "specs/petstore.json"  operation: "addPet"  effect: changes }
agent Shop { tools: [Pet_get, Pet_add]  approve: [Pet_add] }
```
- **No engine change:** one name still means one tool, so approval, effect, profile, audit entry and test scripting work as they do. Deny by default is natural: an agent gets only the operations declared, and there is no "all" to forget.
- **Typed parameters:** the parser already provides each operation's `operationId`, parameters and request-body schema (read), so the tool's schema is built from them.
- **Compatibility:** a script with `use: openapi spec:` and no `operation:` keeps the legacy behaviour, on the new guarded HTTP layer: one tool exposing every operation, with a warning in `weave check` and the finding of D14 saying it exposes the whole API; it becomes an error at the next major.
- **Names:** the tool name is whatever is declared; `weave connect` suggests `<Tool>_<operationId>` and derives one from the method and path when the spec has none; names must satisfy providers' tool-name rules, which the check enforces. The separate `operations:` allow-list of option B is not added.

## D4. Effects and idempotency from the system's own description, and the pins

**Why.** Principle 34. Today every OpenAPI operation is assumed to change things, a GET included, and the MCP hint logic (`McpEffects`) exists but nothing calls it (DESIGN §1). The audit runs nothing and reads no files: `ToolKind.check` is documented as touching neither the network nor the files, and `profile` (35-D2) is the same kind of pure question. So a kind cannot open a spec at audit time and the audit cannot start an MCP server.

- **A. A lockfile** (`integrations.lock`) read by the host and passed to the kinds: a new file format and mechanism.
- **A′. Write it into the script.** `weave connect` (D10) and `weave mcp list --write` read the spec or the server, propose the effect and the retry behaviour, **a person approves, and the result is written into each declaration as declared values**, with a pin for drift detection. No new file format.
- **B. Infer at audit time from the local spec:** breaks the rule that the audit touches no files.
- **C. Declared only,** with no tooling to help.

**Decision (owner): option A′, with no lockfile.** Reasons: with one tool per operation (D3) the effect is already an explicit property of each declaration; the tooling proposes and a person approves what a tool may change (principle 36); a declared effect is final for the runtime and the audit (the guide), so the report contains no guesses; the audit stays pure; and no new file format is added in the last framework phase. A hand-written tool that states no effect gets the cautious `changes` and a hint saying to declare it or run `weave connect`.
**What the tooling infers and writes**
| Source | Rule | Written into the declaration |
|---|---|---|
| OpenAPI GET, HEAD, OPTIONS | reads | `effect: reads` |
| OpenAPI PUT, DELETE | changes, safe to repeat | `effect: changes`, `on_unknown: retry` |
| OpenAPI POST, PATCH | changes, not safe to repeat | `effect: changes`, `on_unknown: skip` |
| A POST with an idempotency-key header parameter | repeat is safe with the key | `idempotency: true`, `on_unknown: retry` |
| MCP `readOnlyHint: true` | reads | `effects: { tool: reads }` |
| MCP `destructiveHint` set, or no hint | changes | `effects: { tool: changes }` |
| MCP `idempotentHint: true` | safe to repeat | `on_unknown: retry` |
**The specification says clients MUST treat tool annotations as untrusted unless the server is trusted** (D6), so the tooling shows an annotation as the server's word and a person approves the effect it writes; the annotation field names and defaults were not read from the schema page and are checked when 36a starts. MCP's `openWorldHint` is left unmapped (the audit's three legs do not correspond to it cleanly) and is shown in the audit note.
**Pins for drift.** A `pin` option on an `openapi` tool and on an `mcp` block holds a hash of the spec or of the server's tool list including its annotations. `weave check` verifies a local spec file against its pin offline and warns on a mismatch; `weave doctor --network` compares the pin with a remote spec or a live server (D12).
**What this gives the MCP hints:** `weave mcp list` (the missing command) becomes the caller of `McpEffects`, and the guide is corrected to say the hints are read by that command and written into the script, not "used when read" (D13).
**Cost, accepted:** scripts are more explicit and a little longer, and someone who hand-writes a tool without running `connect` gets the cautious default.

## D5. Large answers and pagination

**Why.** An OpenAPI answer is cut at 2,000 characters today, which makes data-extraction integrations unusable; `http` (read) caps at `max_bytes` (default 64 KiB, at most 4 MiB), says when it cut, and reads the body capped as it arrives. Both return text only, so a binary answer has nowhere to go.

- **A. A cap that says it cut, plus saving the full answer to a directory,** and bounded pagination as a second step.
- **B. The cap only;** pagination stays in the script with a loop of `use` calls.
- **C. Neither.**

**Decision (owner): option A, in two steps.**
**Step 1 (36a): the cap, the marker and `save_dir`.**
- `openapi` and `http` share the `max_bytes` default of `http` (64 KiB) and its marker.
- **`save_dir:`** is a plain directory option with the path jail of the `file` tool. When an answer exceeds the cap, or is not text, the full body is written there and the tool returns the path, the size, the content type and a preview of the first part; the agent or script hands the file to `workspace`, `verify`, `compute csv` or `documents`.
- The filename is derived from the tool name and a hash of the content, **never from anything the server sends** (no path traversal; replays are stable).
- `save_max_bytes` has a named default and a hard ceiling (proposed 16 MiB and 128 MiB, to tune).
- A tool with `save_dir` writes locally, so the audit shows a local write for it, as for `file` in write mode.
- The text the tool returns is redacted; the saved body is kept as received.
- It is a plain directory and not a link to the `workspace` tool because whether one tool's option can name another is unread (35's R2); if that turns out well, a later change can point `save_dir` at a workspace.
**Step 2 (36b): bounded pagination,** for reads only, through the shared HTTP layer so `http` and `openapi` get it together: **`link`** (follow `Link: …; rel="next"`), **`cursor`** (the next cursor from a JSON path in the body, sent back in a named parameter) and **`page`** (increment a page or offset parameter until a page is empty); `max_pages` with a named default and the same total-size cap; refused on anything that changes state; each page passes every guard; the whole is one tool result. A tool that returns "page 1 of an unknown number" invites an agent to loop without bound, so the bound belongs in the tool.
**Left out:** merging pages into one structure for the agent, streaming, resumable downloads.

## D6. The MCP client: transports, versions, what it reads

**Why.** The client speaks one protocol version from 2024, reaches only local stdio servers, and reads tools only (DESIGN §1). The specification has since changed in kind, not only in degree (DESIGN §1, "The MCP specification today").

- **A. Both eras, stdio and Streamable HTTP, tools only:** probe each server and cache the answer, keep the legacy handshake and add the modern one, handle `isError`, `structuredContent`, paging and timeouts, and add HTTP authorization.
- **B. The legacy era only,** updated to a current legacy version, over both transports.
- **C. The modern era only:** the simplest long term and it fails on every legacy server (the spec's own compatibility matrix).
- **D. Stdio only,** with fixes.

**Decision (owner): option A, in two stages, and the authorization-code flow for remote MCP belongs to D8.** The owner's words: *move with the times*.
**36a (stdio, legacy era, as it works today):** a tool result with `isError: true` becomes a failure; `tools/list` paging (`cursor`, `nextCursor`); per-call timeouts; a failed `tools/list` is a load error and not a log line; a crashed stdio server is restarted or reported; **MCP tools get the approval and decision wrappers other tools have** (a 36a read first: whether they honour `approve:` some other way); the offered protocol version is brought up to date and the server's counter-offer accepted. The script surface (`args`, `env`, isolation) is D7.
**36b (the rest):** the **modern era** (`server/discover`, the protocol version in each request's metadata, and `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` over HTTP; an `UnsupportedProtocolVersionError` is answered by selecting a version from the server's `supported` list); **Streamable HTTP** in both eras (a POST to one endpoint, a JSON or event-stream reply, sessions only in the legacy era); the **probe and its per-server cache** (stdio: `server/discover`; HTTP: a modern request, falling back to `initialize` on a 400 without a recognised modern error); `structuredContent` and `outputSchema`; and **authorization for HTTP servers: the consumer's token is carried and a rejection is surfaced (D8); no flow runs in llm4j**.
**Trust:** per the specification, tool annotations are **untrusted unless the server is trusted** and tool descriptions and results are untrusted content; results are `unchecked`, and annotations are only a proposal a person approves (D4).
**Out:** resources, prompts, sampling, roots, logging, elicitation, and the multi-round-trip `input_required` result (a tool call that returns it fails with a clear message).
**Risks, accepted:** 36b's MCP piece is the largest single item of the phase (mostly authorization); how many servers have moved to the modern era is unknown, which is why both are supported; no page was found that labels a revision "stable", so no code assumes one.

## D7. An MCP server or a command as a program: isolation, arguments, secrets

**Why.** A script's `mcp { cmd: "npx ..." }` runs an arbitrary program with the user's privileges. **Read:** `StdioMcpTransport` builds a `ProcessBuilder` and adds the script's variables on top of the **whole inherited host environment**; `cmd` is one string split on whitespace; the script cannot set `env` at all; the server's error stream is inherited (it goes to the console, unredacted and unbounded). The specification says stdio servers take credentials from the environment. Principle 37.

- **A. The same rules as `sandbox` (35-D5, 35-D8),** adapted so existing scripts do not break.
- **B. Add `args` and `env` only,** leaving the program on the host with an audit rule.
- **C. Leave as is.**

**Decision (owner): option A, in three states, with six rules.**
| State | What happens |
|---|---|
| **No `isolation:` line** (every existing script) | Behaves as today, with a load warning and an LA31 finding (35-D19, extended in D14); an error at the next major |
| **`isolation: none`** | An explicit choice to run on the host; the rules below apply, and the audit still flags it |
| **`isolation: process` or `container`** (or a registered backend) | The full set of rules |
**The rules for an explicit isolation level**
1. **`args:` is a list;** each value is one argument, never split.
2. **A scrubbed environment:** nothing is inherited from the host; the server gets the `env:` entries and the names listed in `env_inherit:` (for example `PATH`).
3. **Secrets in `env:`** come from the secret store (`env.NAME` or `secret.NAME`) and are resolved when the server starts (the one place a secret is not resolved per call, because a process cannot take a new environment); never logged; the audit lists them by name.
4. **The server's error stream is captured,** redacted, bounded and shown in the trace and in `weave why` on a failure; it no longer goes to the console.
5. **A container needs `image:`,** and takes `workspace:` (a mount) and `network:` (`none`, `allow` with `hosts:`, `open`) as for `sandbox`, with the same egress proxy; a server that downloads its own packages at start needs the registry on its allow-list, and a prepared image avoids that.
6. **The server belongs to the run:** started at boot as today, stopped on end and cancel, leftovers from a crashed run stopped when a run resumes (35-D7's registry), and a crashed server restarted a bounded number of times and then reported.
D9's `command` adapters use the same process machinery, so the rules apply once.
**Costs, accepted:** a container needs an image per server, which is more work than `npx something`; `process` isolation is weak and is flagged; the legacy state keeps a known hole open for one release, with a warning.

## D8. Authentication: the consumer brings the token

**Why.** Most SaaS cannot be reached with a key in a header, and remote MCP servers expect OAuth 2.1. But **llm4j is not the product: a consumer application already has its own sign-in, its own grant and its own OAuth library**; what it needs is a way to hand a token to an integration and have it used safely. **Read:** the path mostly exists. A host hands a run its credentials (`LoomEngine` takes an `InvokerCredentials`, `LoomHost.resume(runId, invoker)` takes one, the generated Java bindings have `invoker(...)`); a script writes `invoker.NAME`; Loom's `ToolKind.lateBound()` lists the options that may take it and the value is asked for **on every request**; `CredentialSource.rejected()` is a refresh hook with one retry on a 401 in the OpenAPI tool. What is missing is breadth: only `openapi`'s `auth_value` accepts `invoker.NAME`; `http` does not, and MCP has no authentication.

- **A. Pass-in only:** every integration door accepts the consumer's credential, resolved per request and per run; llm4j never acquires, stores or refreshes a token.
- **B. Pass-in plus reference OAuth flows** (client credentials, refresh, a sign-in command, authorization code with PKCE).
- **C. Leave as is.**

**Decision (owner): option A.** The owner's words: *weave is not the product; consumers want a way to pass in the OAuth token they acquire through whichever grant.*
1. **Every door accepts `invoker.NAME`:** `http`, the new `openapi` kind, and remote MCP (an `auth:` option that sends `Authorization: Bearer …` on each request); any kind that takes a credential declares it in `lateBound()`.
2. **Resolved on every request, never cached by llm4j,** so whatever refresh the consumer does, in any OAuth library, just works.
3. **Per run, never shared across users:** a remote MCP connection that carries a user's token belongs to that run, and in the legacy MCP era its session is bound to the run; a server hosting many users must not share one connection. A 36a read: how the engine and a host that pools executors interact (the executors read are built per run).
4. **A rejection is a typed outcome:** on a 401 the client calls `rejected()` and retries once; if it still fails, or the server answers 403 with `insufficient_scope`, the tool fails with a `credential_rejected` error that carries the server's `WWW-Authenticate` challenge, redacted of secrets, so the consumer can start its own step-up or its own MCP discovery (the challenge carries the `resource_metadata` address) and resume the run with a new token through `resume(runId, invoker)`.
5. **A documentation page** with an example for REST, OpenAPI and remote MCP, showing a host implementing `InvokerCredentials` around any OAuth library; it adds no dependency.
6. **A command-line user** supplies a static token with `env.` or `secret.` as today; a short-lived one comes from their own tooling.
**Not in llm4j (D18):** OAuth flows (client credentials, refresh, authorization code with PKCE, device code), token storage and refresh, a login command, protected-resource-metadata discovery, client ID metadata documents, AWS Signature V4, client certificates (a consumer supplies a `CredentialSource`).

## D9. Typed command adapters

**Why.** `sandbox` (35-D6) takes a program and a free argument list, so an agent builds its own command line within an allow-list. A command the owner wants to offer, `ffmpeg` or `git` or a converter, is better offered as a tool with named, typed, validated parameters.

- **A. A `command` kind on the sandbox backend:** a template and typed parameters (`path` in or out of the workspace, `int` with a range, `enum`, `string` with a pattern, `flag`), each value a **single argument, never split or re-parsed**, an output reading (`text`, `json`, `lines`), an exit-code mapping, an effect and idempotency declared or inferred (a `path(out)` writes), isolation required as for `sandbox`.
- **B. Named templates inside `sandbox`.**
- **C. Nothing;** a script uses `sandbox` and trusts the agent's arguments.

**Recommendation: A.** One tool per command gives the model a per-command schema, makes injection through an argument impossible by construction, and gives the audit an exact description. It needs 35's `sandbox` and so lands after 35d.

**Decision (owner):** open.

## D10. `weave connect`: authoring an integration

**Why.** The ease of integration is the moat; today a tool declaration is written by hand.

- **A. Deterministic first.** From an OpenAPI file or an MCP server: list the operations, let the person choose them, write the tool declaration with the effects proposed and, once a person approves them, written as declared values, and the pin (D4), write test fakes from the spec's examples, run `weave check` and `weave audit`. **A model-assisted mode** for a system with no spec: from a command's help, documentation or sample files, a model drafts a `command` adapter or an `http` tool; **code verifies the draft against the samples** in a fake and the draft is never emitted if it fails; a person approves every effect classification.
- **B. Deterministic only.**
- **C. Model-assisted only.**

**Recommendation: A, in two steps:** the deterministic part in 36d with the pins, the model-assisted part after it in the same sub-phase if the first works. It is principle 36 applied to authoring: a model drafts, code proves, a person approves.

**Decision (owner):** open.

## D11. Recording and replaying tool results

**Why.** `capture` records models, classifiers, people, tasks and failures, but not the results of tools; so a real run's external calls cannot be replayed in a test or a regression.

- **A. Record tool results in `capture`** (matched by tool and a hash of the arguments, secrets and personal data masked first), and replay them in `weave test` and `weave regress`; an unmatched call fails the case; effect tools are never replayed live (already the rule).
- **B. Leave to hand-written `ScriptedTools`.**

**Recommendation: A.** It makes a real integration testable without the system, which is the other half of "easy". Masking uses the existing `Redactor`; a canary secret must appear in no recorded file.

**Decision (owner):** open.

## D12. Health and drift

**Why.** Systems change: an API drops an operation, an MCP server renames a tool. Nothing detects it today (`doctor` checks keys and models).

- **A. The pins of D4 checked in two places:** `weave check` compares a local spec file to its `pin` offline and warns on a mismatch; `weave doctor --network` fetches each spec and MCP tool list and reports a difference, and tests each endpoint's reachability without making a call that changes anything.
- **B. `doctor` only.**
- **C. Nothing.**

**Recommendation: A.** The pins already exist for D4, so drift detection costs a comparison. `doctor --network` never calls an operation that changes anything.

**Decision (owner):** open.

## D13. The documentation and the code agree

**Why.** The guide says MCP hints are used when read; the code does not call them. Principle 39.

Proposed: (1) give `McpEffects` its caller in the missing `weave mcp list --write` (D4) and correct the guide; (2) a **docs test** that runs the guide's table of default effects against `Capabilities`, so a documented default that is not wired fails; (3) a test that `weave mcp list --write` turns a hint into a declared effect in the script; (4) the same treatment for every claim the guide makes about tool behaviour that a test can state.

**Recommendation: all four.** The cheapest way to stop this recurring is a test that reads the claim.

**Decision (owner):** open.

## D14. Security rules and documents

**Why.** New things start programs, reach remote servers and hold tokens.

Proposed (numbers continue after 35's LA33): **extend LA31** to an MCP stdio server and a `command` adapter run at `none` or `process`; **LA34:** an `openapi` tool without `operation:` exposes the whole API (the legacy mode of D3; medium); **LA35:** a `pin` differs from the spec or tool list it pins (a warning in `weave check`, an error in `weave audit --strict`); a `docs/security/integrations.md` page (what the guards cover, how a token is stored, why an MCP server is a program); every rule listed in `weave-audit.md` and mapped in `owasp-llm-top-10.md`; matching `weave review` rules (regenerated with `make regen`).

**Recommendation: all of it.**

**Decision (owner):** open.

## D15. Testing: fakes, guards, canaries

**Why.** Principles 36 and 38, and the repository's rule that the default build has no network.

Proposed: **fakes** (a scripted HTTP server for OpenAPI with `MockWebServer`, including one that answers 401 and then accepts a refreshed token and one that answers 403 with `insufficient_scope`; a scripted MCP server, in process and as a stdio program; a fake clock for `Retry-After`); **guards tested by breaking them once:** an OpenAPI spec whose server is a loopback or metadata address is refused (SSRF), a timeout fires, a cap says it truncated and saves the rest, retries respect `Retry-After`, a canary token appears in no log, trace, journal, capture or exception, an MCP `isError` becomes a failure, a command parameter containing spaces, `;` or `--flag` stays one argument, a draft adapter that fails its samples is not emitted, a pin mismatch is flagged; **canaries** through OpenAPI and MCP results into the provenance oracle (results are `unchecked`); a **mutation profile** for the new decision-making classes (the inference rules, the credential-rejection handling, the argument builder, the pin comparison) at 85% or more.

**Recommendation: all of it,** with the real-server contract tests tagged `@Tag("integration")`.

**Decision (owner):** open.

## D16. Names

**Why.** Words in a script and in a tool's options are public from the day they ship.

Proposed: kinds `openapi` (the options `spec`, `operation`, `effect`, `on_unknown`, `pin`, `base_url`, `server`, `max_bytes`, `timeout`, `retries`, and the auth options of `http`), `command`; the `mcp` block gains `args`, `env`, `isolation`, `workspace`, `network`, `url` (for a remote server) and `auth` and `pin`; commands `weave connect` and `weave mcp list`; the extras module `ai-agent4j-tools-openapi`. Option names follow `http` and `sandbox` wherever they mean the same thing.

**Recommendation: these.**

**Decision (owner):** open.

## D17. Visibility: the ecosystem and the time-to-first-safe-call benchmark

**Why.** Principle 40, and the standing rule that docs ship with the code.

Proposed: the whole documentation pass of 35-D20, again nothing deferred (the guide's tool and `mcp` sections, the wiki, the READMEs of the new module, `llms.txt`, the extension's hover text and snippets, CTK scenarios, the skills, a tutorial chapter, a starter that connects a system, release notes, `docs/API_COMPATIBILITY.md` for D2); and the **benchmark:** a fixed set of five systems (a REST API with a bearer token supplied by the host, against a scripted server, a local MCP server, a remote MCP server where one is available, a command-line tool, a PDF through `documents`), each connected with `weave connect`, measuring the manual edits needed, whether the audit is clean, whether tests were generated and whether the first call succeeded; the results are published with the method.

**Recommendation: all of it.**

**Decision (owner):** open.

## D18. What stays out of this phase

Proposed: spreadsheet and XML formats; an adapter catalog or registry; GraphQL, gRPC and SOAP adapters; MCP resources, prompts and sampling; queues and event streams (35-D18); OAuth flows, token storage and refresh, a login command, protected-resource-metadata discovery and client ID metadata documents (the consumer's application does these, D8); AWS Signature V4 and client certificates (a consumer supplies a `CredentialSource`); a graphical connection wizard; anything in the VS Code extension beyond hover text, snippets and the changelog (35-D22); and a global default for isolation (35-D5).

**Recommendation: confirm the list.**

**Decision (owner):** open.
