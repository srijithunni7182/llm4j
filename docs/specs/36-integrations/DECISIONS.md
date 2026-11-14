# Phase 36: decisions

Status: **draft; all eighteen decisions are open.** Each gives the question, why it matters, the options and a recommendation. The recommendation is a proposal: the owner decides one at a time and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against; [DESIGN.md](DESIGN.md) holds the facts.
References: "35-D6" is decision D6 of [phase 35](../35-framework-tools/DECISIONS.md). Facts about the code are from DESIGN §1 and are marked (read) or (not read) there.

## Decision log

| | Decision | State |
|---|---|---|
| D1 | The scope and staging of this phase | open |
| D2 | Where OpenAPI lives, and the compatibility plan | open |
| D3 | The OpenAPI call model: one generic tool, or one tool per operation | open |
| D4 | Effects and idempotency taken from the system's own description, and the lockfile | open |
| D5 | Large answers and pagination | open |
| D6 | The MCP client: transports, versions, what it reads | open |
| D7 | An MCP server or a command as a program: isolation, arguments, secrets | open |
| D8 | Authentication: OAuth and signed requests | open |
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

**Decision (owner):** open.

## D2. Where OpenAPI lives, and the compatibility plan

**Why.** The OpenAPI tool is in the **core** and uses `java.net.http` directly, so it cannot use `NetPolicy` and `HttpSupport` (the tools module, which the core must not depend on); and it pulls `swagger-parser` into the core.

- **A. A new `openapi` kind in an extras module** (`ai-agent4j-tools-openapi`) built on `HttpSupport`, carrying `swagger-parser`; the core drops the dependency; `weave` bundles the module. The old core classes are deprecated for one release and then removed; `docs/API_COMPATIBILITY.md` and a migration note say so.
- **B. Keep the classes in the core** and add the guards there by copying `NetPolicy` into the core (duplicated security code).
- **C. A new `openapi` kind in the tools module with a small Jackson-only reader** for the subset of OpenAPI we need, and no swagger-parser anywhere.

**Recommendation: A.** It gives one set of guards (principle 33) and a light core (principle 8). C avoids the dependency but means writing and maintaining a parser for a large specification, which is a risk of its own. B repeats security code in two places. A is a break for Java users of the old package, and the owner should decide whether a release of deprecation is enough.

**Decision (owner):** open.

## D3. The OpenAPI call model: one generic tool, or one tool per operation

**Why.** Today the agent gets one tool whose arguments are `endpoint` and `method`, and a description of every endpoint; a large API makes a large description, and a model chooses a path as free text.

- **A. One tool per allowed operation,** with typed parameters taken from the operation's schema, an `operations:` list that is **required** (no default of "all"), an explicit `all: true` that the audit flags, and a cap on the number of operations per tool.
- **B. Keep the single generic tool,** with the list as an option.
- **C. Both,** chosen by an option.

**Recommendation: A.** Typed per-operation tools are what the model's function calling handles best, they make approval, effect and audit per operation natural (the `effects:` map is already keyed by `operationId`), and a required list is deny-by-default. The generic form stays only in the deprecated core class.

**Decision (owner):** open.

## D4. Effects and idempotency from the system's own description, and the lockfile

**Why.** Principle 34. Today an OpenAPI operation is assumed to change things even when it is a GET, and the MCP hints that the guide says are used are not (DESIGN §1). The audit runs nothing, so it cannot ask an MCP server.

- **A. Inference rules plus a lockfile.** OpenAPI: GET, HEAD and OPTIONS read; PUT and DELETE change and are idempotent; POST and PATCH change and are not, unless the operation declares an idempotency key. MCP: `readOnlyHint` reads, `destructiveHint` changes, `idempotentHint` marks idempotent, an absent hint is the worst case. All are shown as *assumed*; a declared `effects:` wins. The audit reads a committed lockfile (`integrations.lock`: spec hash and operations, an MCP server's tool list with annotations, the inferred effects) and `weave mcp list --write` and `weave connect` create it.
- **B. Inference only,** no lockfile: MCP stays at the worst case because the audit cannot connect.
- **C. Declared only:** the owner writes every effect.

**Recommendation: A.** The lockfile is the one device that lets the audit see an MCP server's hints without running it, gives drift detection (D12) and gives `weave connect` something to write. A script whose lockfile is missing or stale gets the worst case and a message that says how to refresh it.

**Decision (owner):** open.

## D5. Large answers and pagination

**Why.** An OpenAPI answer is cut at 2,000 characters today, which makes data-extraction integrations unusable; `http` has a `max_bytes` but returns text only.

- **A. A configurable cap that says it truncated, and answers larger than the cap saved to the workspace** (35's `workspace`), returning the path, the size and a preview; **bounded pagination** (a `Link` header, a cursor field, a page or offset parameter; `max_pages`) as an option of `http` and the OpenAPI kind through `HttpSupport`.
- **B. The cap only;** pagination stays in the script, with a loop of `use` calls.
- **C. Neither;** the cap is configurable and that is all.

**Recommendation: A in two steps:** the cap, the truncation marker and save-to-workspace in 36a; pagination in 36b. A script can already loop, but a tool that returns "page 1 of an unknown number" invites an agent to loop without bound, so the bound belongs in the tool.

**Decision (owner):** open.

## D6. The MCP client: transports, versions, what it reads

**Why.** The client speaks one protocol version from 2024, reaches only local stdio servers, and reads tools only; most hosted MCP servers are remote and need authorization (DESIGN §1).

- **A. Stdio and streamable HTTP;** version negotiation (offer the latest the client knows, accept the server's counter, fail with a clear message); tools with paging and `isError`; the server's annotations; per-call timeouts; crash recovery of a stdio server. Resources, prompts and sampling are out (D18).
- **B. Fixes to the stdio client only** (errors, paging, timeouts), no remote transport.
- **C. Everything the specification defines.**

**Recommendation: A.** Remote servers are where the ecosystem is; resources and prompts are separate features and have no use yet in the verticals. **The current specification must be read when 36b starts** (what the transports and the authorization require); nothing here is taken from memory.

**Decision (owner):** open.

## D7. An MCP server or a command as a program: isolation, arguments, secrets

**Why.** A script's `mcp { cmd: "npx ..." }` runs an arbitrary program with the user's privileges; `cmd` is split on whitespace and no environment can be passed, so servers that need a key cannot be used. Principle 37.

- **A. The same rules as `sandbox` (35-D5, 35-D8):** an `isolation:` line is **required** (`process`, `container`, or a registered backend); the server runs through `SandboxBackend` (for a container, `docker run -i`); `args` is a list; `env` takes values from the secret store, resolved when the server starts (the one place a secret is not resolved per call, and never logged); a `workspace:` mount and the `network` setting as for `sandbox`.
- **B. Add `env` and `args` only,** and leave the program on the host with an audit rule.
- **C. Leave as is.**

**Recommendation: A.** It closes a real gap (no secrets, no quoting) and applies one honest isolation model to everything that starts a process. A server run at `none` or `process` is flagged by LA31 (35-D19, extended in D14).

**Decision (owner):** open.

## D8. Authentication: OAuth and signed requests

**Why.** Most SaaS cannot be reached with a key in a header. The library has only the `InvokerCredentials` interface; the host implements any flow (DESIGN §1).

- **A. Reference implementations on the shared HTTP layer:** OAuth 2 client credentials (service accounts), the refresh-token grant with the token kept in the encrypted secret store, and a **device-code sign-in command** (`weave secret login <name>`) for a person; tokens refresh before expiry; the scope is named in the audit note; a token appears in no log, trace, journal or capture.
- **B. Client credentials and refresh only,** no sign-in command (the host obtains the first token).
- **C. Leave it to the host,** as now.

Signed requests (AWS Signature V4) and client certificates (mTLS) are **out**: a consumer supplies a `CredentialSource` (D18).

**Recommendation: A.** Without a way to sign in, "easy" stops at the first SaaS. The implementation lives in the tools module with the JDK and Jackson only (principle 8), uses `HttpSupport` (so `NetPolicy` applies to the token endpoint), and is tested against a scripted token server.

**Decision (owner):** open.

## D9. Typed command adapters

**Why.** `sandbox` (35-D6) takes a program and a free argument list, so an agent builds its own command line within an allow-list. A command the owner wants to offer, `ffmpeg` or `git` or a converter, is better offered as a tool with named, typed, validated parameters.

- **A. A `command` kind on the sandbox backend:** a template and typed parameters (`path` in or out of the workspace, `int` with a range, `enum`, `string` with a pattern, `flag`), each value a **single argument, never split or re-parsed**, an output reading (`text`, `json`, `lines`), an exit-code mapping, an effect and idempotency declared or inferred (a `path(out)` writes), isolation required as for `sandbox`.
- **B. Named templates inside `sandbox`.**
- **C. Nothing;** a script uses `sandbox` and trusts the agent's arguments.

**Recommendation: A.** One tool per command gives the model a per-command schema, makes injection through an argument impossible by construction, and gives the audit an exact description. It needs 35's `sandbox` and so lands after 35d.

**Decision (owner):** open.

## D10. `weave connect`: authoring an integration

**Why.** The ease of integration is the moat; today a tool declaration is written by hand.

- **A. Deterministic first.** From an OpenAPI file or an MCP server: list the operations, let the person choose them, write the tool declaration, infer the effects (marked assumed), write the lockfile (D4), write test fakes from the spec's examples, run `weave check` and `weave audit`. **A model-assisted mode** for a system with no spec: from a command's help, documentation or sample files, a model drafts a `command` adapter or an `http` tool; **code verifies the draft against the samples** in a fake and the draft is never emitted if it fails; a person approves every effect classification.
- **B. Deterministic only.**
- **C. Model-assisted only.**

**Recommendation: A, in two steps:** the deterministic part in 36d with the lockfile, the model-assisted part after it in the same sub-phase if the first works. It is principle 36 applied to authoring: a model drafts, code proves, a person approves.

**Decision (owner):** open.

## D11. Recording and replaying tool results

**Why.** `capture` records models, classifiers, people, tasks and failures, but not the results of tools; so a real run's external calls cannot be replayed in a test or a regression.

- **A. Record tool results in `capture`** (matched by tool and a hash of the arguments, secrets and personal data masked first), and replay them in `weave test` and `weave regress`; an unmatched call fails the case; effect tools are never replayed live (already the rule).
- **B. Leave to hand-written `ScriptedTools`.**

**Recommendation: A.** It makes a real integration testable without the system, which is the other half of "easy". Masking uses the existing `Redactor`; a canary secret must appear in no recorded file.

**Decision (owner):** open.

## D12. Health and drift

**Why.** Systems change: an API drops an operation, an MCP server renames a tool. Nothing detects it today (`doctor` checks keys and models).

- **A. The lockfile of D4 checked in two places:** `weave check` compares a script to its lockfile offline; `weave doctor --network` fetches each spec and MCP tool list and reports a difference, and tests each endpoint's reachability without making a call that changes anything.
- **B. `doctor` only.**
- **C. Nothing.**

**Recommendation: A.** The lockfile already exists for D4, so drift detection costs a comparison. `doctor --network` never calls an operation that changes anything.

**Decision (owner):** open.

## D13. The documentation and the code agree

**Why.** The guide says MCP hints are used when read; the code does not call them. Principle 39.

Proposed: (1) wire the hints through the lockfile (D4) and add the missing `weave mcp list`; (2) a **docs test** that runs the guide's table of default effects against `Capabilities`, so a documented default that is not wired fails; (3) a test that an MCP hint in a lockfile changes the audit's effect; (4) the same treatment for every claim the guide makes about tool behaviour that a test can state.

**Recommendation: all four.** The cheapest way to stop this recurring is a test that reads the claim.

**Decision (owner):** open.

## D14. Security rules and documents

**Why.** New things start programs, reach remote servers and hold tokens.

Proposed (numbers continue after 35's LA33): **extend LA31** to an MCP stdio server and a `command` adapter run at `none` or `process`; **LA34:** an integration exposes all operations of a system, or more than a stated number (medium); **LA35:** an integration's lockfile is missing or differs from its declaration (a warning in `weave check`, an error in `weave audit --strict`); the audit note names an OAuth tool's **scopes**; a `docs/security/integrations.md` page (what the guards cover, how a token is stored, why an MCP server is a program); every rule listed in `weave-audit.md` and mapped in `owasp-llm-top-10.md`; matching `weave review` rules (regenerated with `make regen`).

**Recommendation: all of it.**

**Decision (owner):** open.

## D15. Testing: fakes, guards, canaries

**Why.** Principles 36 and 38, and the repository's rule that the default build has no network.

Proposed: **fakes** (a scripted HTTP server for OpenAPI and OAuth with `MockWebServer`; a scripted MCP server, in process and as a stdio program; a fake clock for token expiry and `Retry-After`); **guards tested by breaking them once:** an OpenAPI spec whose server is a loopback or metadata address is refused (SSRF), a timeout fires, a cap says it truncated and saves the rest, retries respect `Retry-After`, a canary token appears in no log, trace, journal, capture or exception, an MCP `isError` becomes a failure, a command parameter containing spaces, `;` or `--flag` stays one argument, a draft adapter that fails its samples is not emitted, a stale lockfile is flagged; **canaries** through OpenAPI and MCP results into the provenance oracle (results are `unchecked`); a **mutation profile** for the new decision-making classes (the inference rules, the OAuth refresh logic, the argument builder, the lockfile comparison) at 85% or more.

**Recommendation: all of it,** with the real-server contract tests tagged `@Tag("integration")`.

**Decision (owner):** open.

## D16. Names

**Why.** Words in a script and in a tool's options are public from the day they ship.

Proposed: kinds `openapi` (the options `spec`, `base_url`, `server`, `operations`, `all`, `max_bytes`, `timeout`, `retries`, and the auth options of `http`), `command`; the `mcp` block gains `args`, `env`, `isolation`, `workspace`, `network`, `url` (for a remote server) and `auth`; commands `weave connect`, `weave mcp list`, `weave secret login`; the file `integrations.lock`; the extras module `ai-agent4j-tools-openapi`. Option names follow `http` and `sandbox` wherever they mean the same thing.

**Recommendation: these.**

**Decision (owner):** open.

## D17. Visibility: the ecosystem and the time-to-first-safe-call benchmark

**Why.** Principle 40, and the standing rule that docs ship with the code.

Proposed: the whole documentation pass of 35-D20, again nothing deferred (the guide's tool and `mcp` sections, the wiki, the READMEs of the new module, `llms.txt`, the extension's hover text and snippets, CTK scenarios, the skills, a tutorial chapter, a starter that connects a system, release notes, `docs/API_COMPATIBILITY.md` for D2); and the **benchmark:** a fixed set of five systems (a REST API with OAuth against a scripted server, a local MCP server, a remote MCP server where one is available, a command-line tool, a PDF through `documents`), each connected with `weave connect`, measuring the manual edits needed, whether the audit is clean, whether tests were generated and whether the first call succeeded; the results are published with the method.

**Recommendation: all of it.**

**Decision (owner):** open.

## D18. What stays out of this phase

Proposed: spreadsheet and XML formats; an adapter catalog or registry; GraphQL, gRPC and SOAP adapters; MCP resources, prompts and sampling; queues and event streams (35-D18); AWS Signature V4 and client certificates (a consumer supplies a `CredentialSource`); a graphical connection wizard; anything in the VS Code extension beyond hover text, snippets and the changelog (35-D22); and a global default for isolation (35-D5).

**Recommendation: confirm the list.**

**Decision (owner):** open.
