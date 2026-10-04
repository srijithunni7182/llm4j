# Secret store: design

Status: implemented; see [verification.md](verification.md) for the evidence. Companion documents: [test-strategy.md](test-strategy.md), [verification.md](verification.md).

## 1. Problem

Every credential in the stack comes from an environment variable (or, in Loom, from `env.NAME`), or is passed to a builder as a plain `String` that the config then keeps for the life of the process:

- A library cannot assume its host controls the process environment. Many hosts (a Spring service, a desktop app, a test harness) need to keep credentials somewhere else, encrypted, and hand them over by name.
- A key held as a `String` in `LLMConfig` is copied into every provider, sits in the heap for the whole run, and can never be rotated without rebuilding the client.
- A Loom script can name *any* environment variable as `api_key:` and any `base_url:`, so an imported script can send an unrelated secret to a host of its choosing.
- Keys have leaked through URLs (`?key=`) and through `Response.toString()` in exception messages.
- Real-looking Google API keys were committed in test files (a separate, already-reported finding; the keys must be rotated by their owner).

## 2. Goals and non-goals

**Goals**
1. A secret store consumers fill **programmatically** or from an **encrypted file**, and hand to providers *by reference*. Providers fetch the secret **when they make a request** and keep nothing.
2. **The consumer chooses everything that matters**: the file path and the master key (passphrase, key file or a variable *they* name). The library has no default path, no default key and no discovery. Protecting the path with an ACL is the consumer's responsibility (the library only offers an opt-in permission check).
3. A secret can be **bound to the hosts it may be sent to**, so a mis-pointed `base_url` cannot exfiltrate it.
4. Works for every credential-taking component: LLM providers (Gemini, Claude, Sarvam), the Gemini embedding provider, search tools, the OpenAPI tool, the REST skill registry, semantic memory, vector stores, and Loom (`secret.NAME` wherever `env.NAME` works).
5. Backwards compatible: `apiKey(String)` and `env.NAME` keep working.

**Non-goals (v1)**
- Not a vault: no network service, no audit server, no access policies beyond host binding. A `SecretStore` is an interface so Vault, AWS Secrets Manager or an OS keystore can be plugged in later.
- No protection against an attacker who can read the process's memory or the master key's source. The store keeps plaintext secrets in memory after the file is opened (it must, to use them).
- Java `String`s cannot be wiped once a value reaches the HTTP layer.
- No multi-process write coordination (a single writer is assumed; a concurrent change is detected, not merged).

## 3. Package `io.github.llm4j.secret` (module `ai-agent4j`, JDK crypto only)

### 3.1 `SecretStore` (STO)

```java
public interface SecretStore extends AutoCloseable {
    String resolve(String name);                       // SecretNotFoundException when absent
    String resolveFor(String name, String host);       // as resolve, and SecretAccessDeniedException unless host is allowed
    boolean contains(String name);
    Set<String> names();                               // names only, sorted
    Optional<SecretMetadata> metadata(String name);

    default void put(String name, char[] value, SecretMetadata metadata);   // writable stores; others: UnsupportedOperationException
    default boolean remove(String name);
    @Override default void close();
}
```

- **Name rule (STO-01):** `[A-Za-z][A-Za-z0-9_-]{0,63}`. It matches environment-variable names (`GEMINI_API_KEY`) and Loom identifiers (`secret.gemini-prod`); a dot is not allowed, so `secret.a.b` is never ambiguous.
- **`SecretMetadata` (STO-02):** `allowedHosts` (a set of exact hosts or `*.suffix` patterns, no scheme or port, matched case-insensitively; `*.example.com` matches subdomains, not the apex; empty = unrestricted), `description`, `updatedAt`. Metadata never contains the value.
- **Stores:**
  - `InMemorySecretStore`: programmatic; values held as `char[]` and zeroed on `remove`/`close`.
  - `EncryptedFileSecretStore`: section 3.3.
  - `EnvSecretStore`: read-only; a name is an environment variable name. For compatibility and `import-env`.
  - `ChainedSecretStore`: read-only, first store that contains the name wins.
- **Exceptions (STO-03):** `SecretException` (runtime base), `SecretNotFoundException`, `SecretAccessDeniedException`, `SecretStoreException` (unreadable or corrupt file, wrong master key, changed on disk). **No message ever contains a secret value;** names, hosts and file paths may appear.

### 3.2 `SecretRef` (REF)

A handle: store + name. `resolve()`, `resolveFor(host)`, `name()`, `toString()` = `secret:<name>`, `equals` by store identity and name, **not `Serializable`**, implements `Supplier<String>`. `SecretRef.of(store, name)` and `SecretRef.literal(String)` (an unstoreable one-shot wrapper for a plain string, `toString` = `secret:(literal)`, used by the `String` convenience overloads).

Providers hold a `SecretRef` and call `resolveFor(host)` **per request** (REF-02). Nothing downstream caches the value, so replacing the secret in the store takes effect on the next request (rotation without restart).

### 3.3 `EncryptedFileSecretStore` (CRY)

`EncryptedFileSecretStore.create(Path, MasterKey)` (fails if the file exists), `.open(Path, MasterKey)` (fails if it does not), `.openOrCreate(Path, MasterKey)`. Options: `kdfIterations(int)` (default 600 000; 100 000 is the minimum accepted), `requirePrivateFile()` (opt-in: refuse a file that group or others can read, POSIX only).

- **`MasterKey` (CRY-01):** `of(char[] passphrase)` (copied), `fromFile(Path)` (first line, trimmed), `fromEnv(String variableName)` (**the consumer names the variable**; there is no default), `from(Supplier<char[]>)`, `destroy()`. A passphrase is stretched with PBKDF2-HMAC-SHA256.
- **Format (CRY-02):** one UTF-8 JSON document:
  `{"format":"llm4j-secrets","version":1,"kdf":{"alg":"PBKDF2WithHmacSHA256","iterations":N,"salt":"<b64>"},"cipher":{"alg":"AES-256-GCM","nonce":"<b64>"},"data":"<b64>"}`.
  `data` is the AES-256-GCM encryption of `{"secrets":{"<name>":{"value":..,"allowedHosts":[..],"description":..,"updatedAt":..}}}`. The header fields (format, version, KDF algorithm, iterations, salt, cipher algorithm) are the GCM **additional authenticated data**, so changing any of them (for example lowering the iteration count) fails authentication. **Names are encrypted too.**
- **Every write** uses a fresh random 96-bit nonce and re-encrypts the whole payload (CRY-03). The salt is fixed per file until `rekey`.
- **Atomic and tamper-evident (CRY-04):** write to a temporary file in the same directory (created with owner-only permissions where POSIX allows), `fsync`, then atomic move. Before writing, the file is re-read and compared with what was loaded; if another process changed it, the write fails with `SecretStoreException` rather than losing that change.
- **Read path (CRY-05):** the file is decrypted at `open`. Each `resolve` first checks the file's size and modification time and re-reads it if it changed, so an externally rotated secret is picked up without a restart.
- **A wrong master key or a damaged file** both surface as `SecretStoreException("cannot decrypt <path>: wrong master key or the file is damaged")`: GCM cannot tell them apart, and the message must not help an attacker.
- **`rekey(MasterKey)`** re-encrypts under a new key with a new salt. **`close()`** zeroes the derived key and the decrypted values; later calls fail with `IllegalStateException`.
- **Thread-safe** (read/write lock).
- **Permissions (CRY-06):** the library does not choose or create the directory and does not require a particular ACL. By default it logs one warning if the file is readable by group/others on a POSIX system; `requirePrivateFile()` makes that an error. Everything else about the path's protection is the consumer's responsibility, and the documentation says so.

## 4. Integration in `ai-agent4j` (INT)

| Component | Change |
|---|---|
| `LLMConfig` | `Builder.apiKey(SecretRef)`; `apiKey(String)` stays (wraps with `SecretRef.literal`). `getApiKey()` resolves on demand; `getApiKey(host)` also enforces host binding; `hasApiKey()` does not resolve; `getApiKeyRef()`. `toString` prints `secret:<name>`; `equals`/`hashCode` never touch the value |
| Providers (Gemini, Claude, 4 Sarvam) | resolve with `getApiKey(host)` for each request, where `host` is the host of the base URL actually used; a missing secret or a refused host becomes `AuthenticationException` (the providers' documented type) |
| `GeminiEmbeddingProvider` | the key moves from the URL (`?key=`) to the `x-goog-api-key` header, is resolved per request, and failures no longer print `Response.toString()` (which contains the URL) |
| `HttpClientWrapper` | any request header value that carries a credential (`x-goog-api-key`, `x-api-key`, `api-subscription-key`, `Authorization`) is scrubbed from error messages and logged bodies |
| `SerpApiSearchTool`, `WebSearchTool` | constructors taking a `SecretRef`; the key is resolved per call (these two APIs only accept it as a query parameter, so their URLs are never logged or put in messages) |
| `OpenAPITool`, `RestSkillRegistry` | builder overloads taking a `SecretRef`, resolved per request |
| `SemanticMemoryConfig` | `geminiApiKey(SecretRef)`, `pgPassword(SecretRef)` |
| `ai-agent4j-addons` | `PGVectorStore` and `PineconeVectorStore` constructors taking a `SecretRef` (PGVector resolves per connection; Pinecone's SDK takes the key at construction, so it resolves once, documented) |

## 5. Loom (LOOM)

- **Syntax (LOOM-01):** `secret.NAME` is accepted wherever `env.NAME` is: `api_key:` of a `provider`, and every credential-like option of a `tool`. `env.NAME` is unchanged.
- **`ToolDef.OptionValue`** gains a kind (`LITERAL`, `ENV`, `SECRET`); `fromEnv()` keeps its meaning and `isReference()` covers both. Messages say "environment variable X is not set" or "secret X is not in the store".
- **Executor (LOOM-02):** `HarnessExecutor.setSecretStore(SecretStore)`. With no store, `secret.X` is a load error that says how to supply one. `weave check` verifies that each referenced secret exists, **without printing or resolving it for use**.
- **Providers declared in a script (LOOM-03):** the executor hands the client a `SecretRef` (resolved per request). A declared `base_url` must be `https`, or `localhost`/loopback; anything else is a load error (the key would travel in clear text). If the secret has `allowedHosts`, the base URL's host must match, checked at load *and* again per request.
- **Built-in providers (LOOM-04):** `DefaultLLMClientFactory` looks for a secret named like the environment variable (`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `SARVAM_API_KEY`) first, then the environment. Migrating is `weave secrets import-env GEMINI_API_KEY`.
- **Generic tool options (LOOM-05):** resolved when the tool is created (documented: a script's tool is built once per run).
- **`weave audit` (LOOM-06):** new finding **LA15**: a provider that sends a credential to a custom `base_url` (MEDIUM for `env.`, which cannot be host-bound; LOW for `secret.`, with advice to set `allowedHosts`).

## 6. CLI (CLI)

`weave secrets <command> --secrets <file> [--secrets-key-file <file> | --secrets-key-env <VARIABLE>]` (the same options as `run`) with the passphrase prompted on the console when neither is given:

| Command | Does |
|---|---|
| `set <NAME> [--allow-host H]... [--description T]` | stores a value read from the console without echo or from stdin; **never from an argument** (it would show in process listings and shell history) |
| `list` | names, allowed hosts and update times; never values |
| `remove <NAME>` | removes one |
| `import-env <VARIABLE>...` | copies environment variables into the store under the same names |
| `rekey` | re-encrypts under a new master key |
| `create` | creates an empty store |

`weave run`, `check`, `resume`, `tick` and `daemon` take `--secrets <file>` plus `--secrets-key-file` or `--secrets-key-env`. **The secrets path and key source are not written to the run's saved spec**, so a stored run does not remember them: resuming needs them again.

## 7. Security properties (SEC)

- **SEC-01** No secret value appears in `toString`, exception messages, logs, trace events, audit events, journals or run specs.
- **SEC-02** A secret is sent only to hosts in its `allowedHosts` (when set); a refusal names the secret and host, never the value.
- **SEC-03** The encrypted file authenticates its own header; tampering, truncation, a wrong key and a lowered iteration count are all detected.
- **SEC-04** Nothing is chosen for the consumer: no default path, key source or key.
- **SEC-05** The CLI never accepts a secret value on the command line.
- **SEC-06** Providers hold no copy of the key beyond the request that uses it (the HTTP layer's strings aside).

## 8. Honest limits

An encrypted file is only as strong as the master key and how it is supplied (a passphrase in a shell history or a key file next to the store defeats it). It stops plaintext keys in files, logs, process listings and models; it does not stop an attacker who can read process memory. The consumer owns the ACL on the file and its directory. Concurrent writers are detected, not coordinated. `weave audit` can see that a secret is used, not where its value came from.

## 9. Deviations from the first design (all in the implementation, none weakening a requirement)

- The CLI uses one set of option names (`--secrets`, `--secrets-key-file`, `--secrets-key-env`) for `weave secrets` and for `run`/`check`/`resume`/`tick`/`daemon`, instead of `--store`/`--key-file`/`--key-env`.
- `SerpApiSearchTool`, `WebSearchTool`, `PGVectorStore` and `PineconeVectorStore` take a `SecretRef` through a static `withSecret(...)` factory: a constructor overload would make existing `new X(null)` calls ambiguous.
- `MasterKey` wipes through a `Runnable` rather than a subclass (the class is final).
- Extra leaks found while integrating were fixed in the same change: `OpenAPITool` logged full URLs (API key in the query) and response bodies; `GeminiEmbeddingProvider` put the key in the URL; three key-shaped test strings and one `ListModels` fallback were committed. A repository scan test (`RepositoryHasNoSecretsTest`) now guards against recurrence. **Keys that were committed must still be rotated: they remain in git history.**
- `weave secrets` entry points take an injectable environment and prompt source (`Prompts`) so no console is needed in tests.
