# Secret Store

API keys do not belong in environment variables you cannot control, in source, or in a script. The secret store lets the program that **uses**
llm4j decide where keys live, and hands each provider and tool a *reference* to a key instead of the key itself. The component fetches the real
value **at the moment it makes a request**, so a rotated key takes effect without a restart and a key never sits in a config object, a log line
or an error message.

```java
SecretStore secrets = EncryptedFileSecretStore.openOrCreate(
        Path.of("/etc/myapp/secrets.store"),            // you choose where
        MasterKey.fromEnv("MYAPP_MASTER_KEY"));         // you choose how it is unlocked

secrets.put("GEMINI_API_KEY", key, SecretMetadata.allowing("generativelanguage.googleapis.com"));

LLMConfig config = LLMConfig.builder()
        .apiKey(SecretRef.of(secrets, "GEMINI_API_KEY"))  // a reference, not the key
        .build();
GoogleProvider provider = new GoogleProvider(config);     // fetches the key for each request
```

## Who is responsible for what

| You (the consumer) | The library |
|---|---|
| Choose the **file path** and keep it, and its directory, ACL-protected | Encrypts the file (AES-256-GCM, key from PBKDF2-HMAC-SHA256); there is **no default location** |
| Choose the **master key source**: a passphrase, a key file, or an environment variable *you name* | Has **no default key source** and never invents one |
| Decide which hosts a secret may go to | Refuses to send a secret to any other host |

The library warns when a store file is readable by group or others on POSIX systems; `Options.requirePrivateFile()` turns that into an error.
It cannot see Windows ACLs or your backup policy: protecting the path is yours.

## The pieces

| Type | Purpose |
|---|---|
| `SecretStore` | `resolve`, `resolveFor(name, host)`, `contains`, `names`, `metadata`, `put`, `remove`, `close` |
| `SecretRef` | A handle: store + name. `toString()` is `secret:NAME`; it is not serializable and never exposes the value |
| `InMemorySecretStore` | Programmatic: `put(name, chars, metadata)` |
| `EncryptedFileSecretStore` | One encrypted file: `create`, `open`, `openOrCreate`, `rekey`, `reload` |
| `EnvSecretStore` | Reads environment variables (the compatibility path), `system()` or `of(Map)` |
| `ChainedSecretStore` | `of(a, b)`: first store that has the name wins |
| `MasterKey` | `of(chars)`, `fromFile(path)`, `fromEnv("VARIABLE")`, `from(supplier)`; read again for each use and wipeable with `destroy()` |
| `SecretMetadata` | `allowedHosts` (exact or `*.example.com`), description, `updatedAt` |

Names are a letter followed by up to 63 letters, digits, `_` or `-`.

## What the file protects

* The whole payload, **names included**, is encrypted: the file reveals neither what you store nor how many secrets there are (only a size).
* The header (format, KDF settings, salt) is authenticated as additional data, so changing the iteration count or salt is detected.
* A fresh nonce is used for every write; writes go to a temp file in the same directory and are moved into place atomically.
* The iteration count defaults to 600 000 and cannot be set below 100 000.

## Using a reference

Every place that took an API key as a string now also takes a `SecretRef`; the string forms still work.

* `LLMConfig.builder().apiKey(SecretRef)` for the Google, Anthropic and Sarvam providers. The key is fetched for **each request**, and the store's
  host binding is checked against the request's host before it is sent. A refused or missing secret fails with an `AuthenticationException`
  without sending anything.
* `GeminiEmbeddingProvider` (key in the `x-goog-api-key` header, never in the URL), `SerpApiSearchTool.withSecret(ref)`,
  `WebSearchTool.withSecret(ref, cx)`, `OpenAPITool` (`apiKeyAuth(name, ref)`, `headerAuth(name, ref)`), `RestSkillRegistry.apiKey(ref)`,
  `SemanticMemoryConfig.geminiApiKey(ref)` / `pgPassword(ref)`, `PGVectorStore.withSecret(...)` (fetched per connection) and
  `PineconeVectorStore.withSecret(...)` (**fetched once**, when the store is created).

The HTTP layer also scrubs credential header values from error bodies and logs, and logs URLs without their query string.

## In Loom

```
provider Box { use: sarvam  api_key: secret.BOX_KEY  base_url: "https://box.example.com" }
tool Search  { use: serpapi api_key: secret.SERP_KEY }
```

`secret.NAME` comes from the store only; `env.NAME` still reads the environment. For the built-in models (`gemini-…`, `claude-…`,
`sarvam/…`) the store is looked up first by the usual variable name (`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `SARVAM_API_KEY`), then the
environment. Hand the store to the executor with `executor.setSecretStore(store)`, or on the command line:

```
weave run flow.loom --secrets /etc/myapp/secrets.store --secrets-key-env MYAPP_MASTER_KEY
```

`--secrets-key-file <file>` reads the master key from a file; with neither key option, `weave` asks for the passphrase on the console. These options
exist on `run`, `check`, `resume`, `tick` and `daemon`. The path and key source are **not** saved in a run's spec, so resuming a run needs them
again. A provider that sends a key to a custom `base_url` must use https (http only for localhost), the secret's allowed hosts must include that
host, and `weave audit` reports it as LA15.

Tool options such as a SerpApi key are resolved when the tool is created, not on every call; a provider's key is fetched on every request.

## Managing a store with `weave secrets`

```
weave secrets create  --secrets keys.store                      # asks for a master key twice
weave secrets set GEMINI_API_KEY --secrets keys.store --allow-host generativelanguage.googleapis.com
weave secrets set TOKEN --stdin --secrets keys.store            # value piped in, never an argument
weave secrets list    --secrets keys.store                      # names, hosts, times: never values
weave secrets import-env SARVAM_API_KEY --secrets keys.store    # copy from the environment
weave secrets rekey   --secrets keys.store
weave secrets remove  GEMINI_API_KEY --secrets keys.store
```

A value is typed without echo or piped with `--stdin`: it is never a command-line argument, so it cannot end up in shell history or the process list.

## Rotating exposed keys

If a key was ever committed to a repository, rotate it at the provider: removing the string from the code does not remove it from history.
The repository now has a test (`RepositoryHasNoSecretsTest`) that fails the build when a credential-shaped string is committed.
