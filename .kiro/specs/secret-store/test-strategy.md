# Secret store: test strategy

Companion to [design.md](design.md). Requirement IDs (`STO`, `CRY`, `REF`, `INT`, `LOOM`, `CLI`, `SEC`, `DOC`) are defined there; [verification.md](verification.md) maps each to tests and records the evidence.

## 1. Principles

1. **No network, no real keys.** HTTP is exercised against OkHttp's `MockWebServer` (already a test dependency) and scripted clients. Test "secrets" are obvious fakes such as `test-key-0001`. Nothing in the repository may contain a real credential, and a test enforces that (section 3, HARD).
2. **The request is the oracle for "on the fly".** For each provider a test captures the HTTP request the mock server received and asserts which header carried the key, then **changes the secret in the store and sends a second request**, which must carry the new value with no rebuild of the client.
3. **Crypto is tested adversarially.** Wrong key, flipped bits in every part of the file (header fields, salt, nonce, ciphertext, tag), truncation, a lowered iteration count, an unknown version, an empty file, a file that is not JSON, a changed-on-disk conflict, a symlinked or unreadable file.
4. **Leak tests assert absence.** Wherever a secret could appear (`toString`, exception messages, log output, trace events, audit events, run specs, the CLI's output), the test greps for the secret and fails if it is there. These tests are written to *fail* against the old behaviour.
5. **Compatibility is a requirement.** The existing suites of every touched module stay green; `apiKey(String)`, `env.NAME` and every constructor that takes a `String` keep working and are tested.
6. **Negative controls** (verification section 3): each important guarantee is broken on purpose once to prove its test can fail.

## 2. Test levels

| Level | Where | What |
|---|---|---|
| L1 Store unit | `ai-agent4j` `io.github.llm4j.secret.*Test` | names, metadata, host matching, in-memory store, env store, chained store, `SecretRef`, exceptions |
| L2 Crypto and file | `EncryptedFileSecretStoreTest`, `MasterKeyTest` | format, AAD binding, tamper matrix, atomic write, change detection, reload, rekey, close and zeroing, permissions |
| L3 Provider integration | `ai-agent4j` `...provider.*SecretTest`, `...config.LLMConfigSecretTest` | every provider on `MockWebServer`: header, per-request resolution, rotation, host binding, missing secret, error text scrubbed |
| L4 Tools and others | `...agent.tools.*SecretTest`, addons tests | search tools, OpenAPI tool, REST skill registry, semantic memory, vector stores |
| L5 Loom language and executor | `loom/ai-agent4j-loom` `...loom.secret.*Test` | parse `secret.NAME`, validation messages, executor store, provider resolution and host binding, tool options, built-in provider lookup order, audit LA15 |
| L6 CLI | `...loom.secret.SecretsCommandTest` | every `weave secrets` command, `--secrets` on `run`/`check`, no value on the command line, no value in output, run spec has no secrets path |
| L7 Hardening | `SecretLeakTest`, `RepositoryHasNoSecretsTest` | repository scan for credential-shaped strings; leak greps |
| L8 Docs | `SecretDocsTest` | documented examples parse and pass `weave check`; links resolve; required statements present |
| L9 Regression | whole modules | `mvn verify` of `ai-agent4j`, `ai-agent4j-addons`, `ai-agent4j-loom`; compile of the reactor |

## 3. Techniques per concern

**Header and rotation (INT-01..04).** For Gemini, Claude and the four Sarvam providers: enqueue a mock response, call, take the recorded request, assert the credential header and that the URL contains no key; `store.put(name, newValue)`; call again; assert the second request carries the new value. The `String` overload must behave exactly as before.

**Host binding (SEC-02).** A secret with `allowedHosts = {"api.example.com"}` used against `localhost:<port>` is refused with `SecretAccessDeniedException` (mapped to `AuthenticationException` by providers) and **no request is sent** (`mockWebServer.getRequestCount() == 0`). Pattern tests: exact host, `*.example.com` matches `a.example.com` and not `example.com` or `evilexample.com`, case-insensitive, IPv6 and port stripped.

**Crypto matrix (CRY-02..05).** Property-style: for every byte position of a small file, flip one bit and assert `open` fails with `SecretStoreException` (never anything else, never a partial store). Round trip for random names and values including empty values, Unicode, and values containing quotes and newlines. 1,000 puts produce 1,000 distinct nonces. A file written with 600 000 iterations whose header is edited to 1 000 fails authentication. Crash safety: a failure injected between the temporary write and the move leaves the previous file intact and readable. Change detection: a second store instance writes; the first store's `put` fails with `SecretStoreException`, and after `reload()` it succeeds.

**Permissions (CRY-06).** Where the platform is POSIX: a 0644 file opens (with one logged warning) by default and is refused with `requirePrivateFile()`; a 0600 file opens without warning. The library creates the temporary file with owner-only permissions. Skipped (with a stated reason) on non-POSIX file systems.

**Leaks (SEC-01).** A logging appender captures everything logged during provider failures (HTTP 401/500 bodies that echo the key, an embedding failure, a refused host). Exception messages, `toString` of `LLMConfig`, `SecretRef`, `ProviderSpec`, stores, and Loom trace/audit events are searched for the secret.

**Loom (LOOM-01..06).** Parse tests for `secret.NAME` in provider and tool options (and that a plain string where a secret is required still errors as before); validation tests for each message; an end-to-end run with a scripted `MockWebServer` provider declared in the script; a rotation between two runs; the built-in lookup order (secret before environment); LA15 positive and negative cases.

**CLI (CLI-01..04, SEC-05).** Drive `WeaveCLI` through its command line API with injected console and streams: `set` reads from stdin/console and the value never appears in output or arguments; `list` shows names only; `import-env` copies from an injected environment; wrong key fails cleanly; `--secrets` works on `check` and `run`; the saved run spec contains no secrets path or key source.

**Repository hygiene (HARD).** A test walks tracked files (excluding `target`, `.git`, binary files and an explicit, reviewed allowlist of obvious fakes) for Google (`AIza...`), Anthropic (`sk-ant-...`), OpenAI-style (`sk-...`) and AWS (`AKIA...`) shapes of realistic length and fails on a match. It would have caught the three committed test keys.

**Docs (DOC).** Loom examples in the new documentation parse and pass `weave check` with a test store; links resolve; required statements (consumer owns the path, no default location, never pass a secret on the command line) are present.

## 4. Test data

- `FakeKeys` constants (`test-key-0001`, ...) and a `MockWebServer` helper that records requests.
- Encrypted files are created inside JUnit `@TempDir`s with a fast `kdfIterations(100_000)` where the iteration count is not the subject of the test (the default 600 000 is exercised by dedicated tests).
- A log appender helper (`LogCapture`) for leak tests.

## 5. Exit criteria

All requirement IDs in verification.md are `Verified`; every touched module's full suite passes; no test is skipped or disabled except the documented POSIX-only ones on non-POSIX systems; the repository scan passes; negative controls fail their tests.
