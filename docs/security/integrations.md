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
- **`isolation: container`** runs the server in a Docker container (`image:` is required): no capabilities, a read-only file system with a small `/tmp`, the `workspace:` folder the only mount, no network unless `network: open`, and the same limits as the [sandbox](sandbox.md). A secret in `env:` is handed to the runtime by name, never on its command line. This is the level to use for a server you did not write. It shares the host's kernel, so it is not a boundary against a kernel exploit.
- **`isolation: none`** says you accept running it on the host; the same rules apply and the audit still reports it.
- **No `isolation:` line** is how every script behaved before; it still runs, with this process's whole environment, and `weave check` warns and the audit reports it.

Secrets for a server go in `env:` as `env.NAME` or `secret.NAME`; a value that looks like a credential and is not a reference is a load error. The audit lists them by name, never by value. The server is started when the run starts, so one that cannot list its tools is a load error, and a server that crashes is started again a few times and then reported; a call that was in flight when it crashed is not repeated.

**Annotations are untrusted.** The specification says a client must treat a tool's annotations (`readOnlyHint` and the rest) as untrusted unless it trusts the server. llm4j never acts on them by itself: a tool nobody described is taken to change things, and `effects:` on the block is the only thing that says otherwise. `weave mcp list` shows what the annotations suggest, labelled as the server's word, for you to approve and write into the script. The tools an MCP server brings go through approval and the policy checks like any other tool, so `approve: all` covers them.

**What this does not cover.** A server started with `npx` or a similar launcher downloads and runs a package. llm4j does not check where it came from (no provenance, no signatures). What limits the damage is isolation, the pin that notices a changed tool list, and treating every result as untrusted.

## A remote MCP server

`mcp Remote { transport: "http" url: "https://..." auth: invoker.token list_auth: env.LIST_TOKEN }` reaches a server over Streamable HTTP, in both protocol eras. Every request passes the same address rules as an `http` tool, a reply is bounded in size, and the token is sent only over https (or to this machine). `auth:` follows the rules of [credentials](#credentials-are-the-callers): asked for on every request, never stored, refreshed or logged by llm4j, and a refused token is asked for once more and then fails as `credential_rejected`. The tools are listed when the engine starts, when nobody is running the workflow, so a server whose `auth:` is `invoker.NAME` also needs a `list_auth:` credential used only for that listing. A remote server's tool list, annotations and results are as untrusted as a local one's.

## A typed command

`use: command` is a command line you wrote with typed holes (`run: "convert {input} -resize {percent}% {output}"`, `param.input: "path in"`). The model fills the holes and never writes the command. The template is split into words once when the script loads; each `{name}` is filled inside its own word and the value is never split, re-read or passed through a shell or through the `{variable}` replacement of the script, so a value holding a space, `;`, `--flag`, `$(...)` or a newline is one argument. A path is held inside the workspace (no `..`, no absolute path, no symlink out), a number inside its range, an enum inside its list. Isolation is required (`container`, or `process` with `network: open`, which the audit flags as LA31); there is no `none`. A command always counts as changing something, so it is journaled and is expected to carry `approve:`.

## Writing the declarations: `weave connect`

`weave connect` reads a description or a server and writes the declarations, with these rules: it proposes and **a person approves** (a model's or a server's classification of an effect is only a proposal); a change is shown as a diff before it is written; nothing that fails `weave check` or has a new high audit finding is written; **a secret is never written**, only a reference (`env.`, `secret.`, `invoker.`), and a value given where a reference belongs is refused; a description, a help text and a server's answer are untrusted input (the text a model reads is given to it as data, and the isolation, image, network, workspace, base address and credential of a drafted tool come from the person's flags, never from the model). A draft made with a model must also answer the person's own samples, and a draft that still fails after its repairs is never written.

## Drift and reachability

`weave check` compares a local description with each `pin:` offline. `weave connect openapi|mcp <script> --refresh --check` reads the live description or starts the server (under its declared isolation), prints what changed and exits 1 on drift; it never writes and runs with the credentials it is given, which is why it is an explicit command. `weave doctor --network` makes one TLS connection to each host of your integrations and nothing else: no request, no credential.

## Pins

`pin:` on an `openapi` tool holds a short hash of that operation's signature: the method, the path, each parameter (name, location, whether it is required, its schema) and the request body. `weave check` compares it with a local description and warns when it differs (LA35); `weave check --strict` and `weave audit --strict` turn the warning into an error. A pin on an `mcp` block covers the server's tool list (names, input schemas, annotations), and **a run refuses to start the server when the list no longer matches**: the pin is the mitigation for a package that changed after you reviewed it. A parameter named `title` or `description` counts: only annotation keywords are ignored, not the names under `properties`. The pin ignores descriptions, examples, the order of keys and every operation nobody uses, so rewording documentation raises no alarm. A pin proves the signature is the one you reviewed; it says nothing about what the server does with it.

## What this page does not cover

An MCP server's own behaviour; whether a remote service is trustworthy; OAuth flows (the application that runs the workflow does those); and the supply chain of an MCP package.
