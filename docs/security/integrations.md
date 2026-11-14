# Securing integrations

An integration is a way for a workflow to read from, or act in, a system that already exists: an API, an MCP server, a command, a file. This page says what llm4j guards on every integration, what it leaves to you, and where it cannot help. It is the security side of [the OpenAPI tool](../../src/tools/extras/ai-agent4j-tools-openapi/README.md) and of the `mcp` block in the [Loom guide](../../src/loom/ai-agent4j-loom/LOOM_GUIDE.md).

## What every outbound call passes

An `http` tool, an `openapi` tool and a remote request of any other kind go through the same layer (`HttpSupport` with a `NetPolicy`):

- **Address rules.** A loopback, private, link-local (cloud metadata) or unspecified address is refused, and so is a redirect to one. Only https is allowed unless the declaration says `allow_http: true`. A host the declaration names is the author's own choice; a host a *description* names is not: an OpenAPI `servers` entry is untrusted input, so unless you write `base_url:` it may not be a loopback or private address.
- **A timeout** on every request, and a limit on the size of an answer. An answer over the limit is cut and says so, or saved whole to a folder you named (`save_dir:`) under a name made from its content, never from anything the server sent.
- **Retries that know the difference** between "never sent" and "may have been delivered". A request that may have been delivered is repeated only when it is safe to repeat (a read, or an effect you declared `idempotency: true` or `on_unknown: retry`).
- **Redaction.** A credential never appears in a result, a trace, an audit line or an error. The challenge a server sends with a refusal is shown with secrets removed.

## One tool per operation

An `openapi` tool with `operation:` is one operation of a description: its arguments are that operation's own parameters, typed from the description, and the model never writes a path or a method. Each tool has its own `effect:`, its own `approve:` and its own audit entry, so a description that has both `GET /pets/{id}` and `DELETE /pets/{id}` gives an agent only the one you declared. A tool with no `operation:` exposes the whole API (as the older tool did); `weave audit` reports it as LA34.

The description is read by llm4j, not by the OpenAPI parser: the parser would fetch a remote description and every external `$ref` on its own, with no address rules. llm4j reads them through the guarded layer, pastes them in, refuses a reference that leaves the description's folder or nests too deep, and only then hands the parser a document with nothing left to fetch.

## Credentials are the caller's

A token is handed in by the application that runs the workflow (`invoker.NAME`) or comes from the environment or the secret store (`env.NAME`, `secret.NAME`). llm4j asks for it on every request, so whatever refresh the application does is picked up, and never stores, refreshes or logs it. It is sent only over https (or to this machine). When the server refuses it, llm4j asks once for a fresh one and retries; if that is refused too, or the server says a scope is missing, the call fails with `credential_rejected`, a failure the application can tell apart, carrying the server's `WWW-Authenticate` challenge so it can obtain a new or wider token and resume the run.

A credential that looks like one (a header such as `Authorization` or `api_key`) is never offered to a model as an argument; it belongs in `auth_header:` or `auth_query:`.

## An MCP server is a program

`mcp Files { cmd: "npx" args: ["-y", "files-server"] }` starts a program with your permissions. Give the block an `isolation:` line:

- **`isolation: process`** starts the server with only the environment you give it (`env:`, and the names in `env_inherit:`), one argument per `args:` entry (never split), and its error stream captured, redacted and bounded rather than printed. It is still a child process with your files and network; `weave audit` reports it (LA31).
- **`isolation: none`** says you accept running it on the host; the same rules apply and the audit still reports it.
- **No `isolation:` line** is how every script behaved before; it still runs, with this process's whole environment, and `weave check` warns and the audit reports it.

Secrets for a server go in `env:` as `env.NAME` or `secret.NAME`; a value that looks like a credential and is not a reference is a load error. The audit lists them by name, never by value. The server is started when the run starts, so one that cannot list its tools is a load error, and a server that crashes is started again a few times and then reported; a call that was in flight when it crashed is not repeated.

**Annotations are untrusted.** The specification says a client must treat a tool's annotations (`readOnlyHint` and the rest) as untrusted unless it trusts the server. llm4j never acts on them by itself: a tool nobody described is taken to change things, and `effects:` on the block is the only thing that says otherwise. `weave mcp list` shows what the annotations suggest, labelled as the server's word, for you to approve and write into the script. The tools an MCP server brings go through approval and the policy checks like any other tool, so `approve: all` covers them.

**What this does not cover.** A server started with `npx` or a similar launcher downloads and runs a package. llm4j does not check where it came from (no provenance, no signatures). What limits the damage is isolation, the pin that notices a changed tool list, and treating every result as untrusted.

## Pins

`pin:` on an `openapi` tool holds a short hash of that operation's signature: the method, the path, each parameter (name, location, whether it is required, its schema) and the request body. `weave check` compares it with a local description and warns when it differs (LA35); `weave check --strict` turns the warning into an error. The pin ignores descriptions, examples, the order of keys and every operation nobody uses, so rewording documentation raises no alarm. A pin proves the signature is the one you reviewed; it says nothing about what the server does with it.

## What this page does not cover

An MCP server's own behaviour; whether a remote service is trustworthy; OAuth flows (the application that runs the workflow does those); and the supply chain of an MCP package.
