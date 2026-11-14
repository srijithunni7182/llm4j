# ai-agent4j-tools-openapi

> 🧰 **Browse every ready-made tool, with what it can do for an agent, in the [Tools catalog](../../../../docs/TOOLS_CATALOG.md).**

The `openapi` tool kind: one operation of an OpenAPI 3 description as one tool, called through the same HTTP layer as the
`http` tool (address rules, timeouts, size caps, retries that know "never sent" from "may have been delivered").
It replaces the OpenAPI tool that used to live in the core, which had none of those guards. `weave` bundles it, so
`use: openapi` works out of the box.

It brings [`swagger-parser`](https://github.com/swagger-api/swagger-parser) (Apache 2.0) and its own dependencies. The
parser never fetches anything itself: this module reads the description and each external `$ref` through the guarded
layer and hands the parser a document with nothing left to fetch.

## From a Loom script

One declared tool per operation, each with its own effect. One description describes both a `GET` and a `POST`, and they
do different things, so they are two tools:

```text
tool Pet_get { use: openapi  spec: "specs/petstore.json"  operation: "getPetById"  effect: reads }
tool Pet_add { use: openapi  spec: "specs/petstore.json"  operation: "addPet"  effect: changes }
agent Shop { tools: [Pet_get, Pet_add]  approve: [Pet_add] }
```

An agent gets only the operations you declared. Each tool's arguments are that operation's own parameters, typed from the
description (`petId` is a whole number, `status` is one of the listed values), plus `body` for an operation that takes
one. An argument the operation does not have is refused before any request is made.

A tool with no `operation:` exposes **every** operation, as the older tool did (arguments `endpoint`, `method`,
`parameters`, `body`). It keeps working so scripts written before this module load and run; `weave audit` reports it as
LA34, and it goes away at the next major version.

## From Java

```java
Tool pet = new OpenApiKind().create("Pet", Map.of(
        "spec", "specs/petstore.json", "operation", "getPetById",
        "base_url", "https://petstore3.swagger.io/api/v3"), Path.of("."));
String answer = pet.execute(Map.of("petId", 1));
```

`new OpenApiKind().check(options, baseDir)` says what is wrong with the options without touching the network or the files.

## Options and arguments

| Option | Meaning |
|---|---|
| `spec` | **Required.** The description: a path (beside the script, or absolute) or an https address; JSON or YAML, OpenAPI 3 |
| `operation` | The `operationId` of the one operation this tool calls. Without it the tool exposes every operation |
| `base_url` | The address the calls go to. Wins over the description's `servers`. A host you name here is your own choice: a local server is reachable |
| `server` | One of the description's `servers`, instead of the first |
| `auth_header` | The header a credential goes in (with `auth_value`) |
| `auth_query` | The query parameter a credential goes in (with `auth_value`) |
| `auth_value` | The credential: `env.NAME`, `secret.NAME`, or `invoker.NAME` (the person who runs the workflow, asked for on every request) |
| `pin` | A hash of this operation's signature (`sha256-…`), as `weave connect` writes it; `weave check` warns when the description no longer matches |
| `max_bytes` | What one answer shows the model, default 64k, at most 4m; a longer answer is cut and says so |
| `save_dir` | A folder (inside the script's) that keeps an answer that is long or not text, whole; the tool returns its path, size, type and the start of it |
| `save_max_bytes` | The most `save_dir` keeps, default 16m, at most 128m |
| `timeout` | Time for one request, default 15s |
| `retries` | How often a request may be repeated, default 2 |
| `idempotency` | Send the journal's key as `Idempotency-Key`, so repeating an effect is safe |
| `on_unknown` | What a resumed run does with an effect whose outcome is not known: `skip` (default) or `retry` |
| `hosts` | The hosts a request may go to, when not just the one |
| `allow_http` | Allow plain http (https is the default) |
| `allow_private` | Allow private and loopback addresses |
| `follow_redirects` | Follow redirects (each one is checked again) |
| `effects` | Internal: the per-operation effects of the older tool, filled from the script's `effects: { id: reads }` |

## What it reaches, and what the audit sees

- **The address the description names is not trusted.** Unless you write `base_url:`, a `servers` entry that is a loopback,
  private or metadata address is refused. A redirect to one is refused too.
- **A credential asked for on every request, refused once, then typed.** A `401` asks the source for a fresh value and
  tries once more; a second refusal, or a `403` for a missing scope, fails with `credential_rejected` and the server's
  challenge (secrets removed), so the application can obtain a new token and resume.
- **The audit** sees a tool that reads untrusted content, reaches private data when it carries a credential, and sends
  outward only if its declared effect is `changes`. A tool with no declared effect is assumed to change things, and the
  audit says to declare it.
- **Saved files** are named from the tool and a hash of the content, never from anything the server sent.

## Tests

The tests run a real HTTPS server (MockWebServer on a socket) whose certificate comes from a test authority the client
trusts, so a real TLS handshake runs with certificate and host-name checks on. The description and answers they use are
recorded from the real Petstore service (`src/test/resources/fixtures/petstore3`, with a `METADATA.json`).
