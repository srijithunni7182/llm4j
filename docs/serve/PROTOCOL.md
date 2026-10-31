# `weave serve`: the editor protocol (version 1)

`weave serve --stdio` is one long-lived process that answers an editor's requests from a cache, so check, graph and review while typing do not pay for a process start. It **runs nothing and writes nothing**: no model, tool, task or workflow, no user class, no file, no network. It reads the files named in requests, their imports, and the `review.properties`, `review.baseline.json`, price file and `prompts/` folder beside the script.

It speaks JSON-RPC 2.0 over standard input and output with the framing of the Language Server Protocol, but it is **not** the Language Server Protocol: the methods are Loom's own. Standard output carries frames and nothing else (the server moves everything else it prints to standard error).

## Framing

```
Content-Length: <bytes>\r\n
\r\n
<UTF-8 JSON, exactly that many bytes>
```

A message over 16 MB, a header without `Content-Length`, or a header line over 8 KB is answered with an error of kind `bad-frame` and the server exits with status 1 (the stream cannot be trusted to line up again). Malformed JSON inside a good frame gets a `parse-error` reply and the server carries on. A closed input ends the server with status 0.

## Handshake

`initialize` with `{ "roots": ["/abs/folder", …] }` (default: the working directory) answers

```json
{ "protocol": 1, "weave": "6.2.0", "methods": ["loom/check", "loom/graph", …], "roots": ["…"] }
```

A client refuses a server whose `protocol` differs from the one it speaks and tells the user to reinstall. The protocol is **additive**: unknown request fields are ignored; unknown methods get a typed error; new fields appear in answers within version 1.

## Requests

Every method takes `file` (absolute or relative to the working directory; it must be inside a root) and may take `text` (the unsaved buffer, used instead of the file's content, imports still read from disk or their own buffers) `buffers` (an object, file to text: the other open files with unsaved changes, so an import being edited is read as the editor has it; each must be inside a root) and `version` (any number; the answer carries it back as `requestVersion` so a client can drop an answer for an older document).

| Method | Extra params | Answer |
|---|---|---|
| `loom/check` | | `{ file, status, result, stderr?, cached, requestVersion }` where `result` is the JSON of `weave check --format json --no-env`; `status` 0 or 2 |
| `loom/graph` | `workflow?` | as above, `result` is the JSON of `weave graph`. A `parallel` node carries `attrs.lanes` (`[{label, nodes: [ids]}]`, each lane's steps and the handlers hanging off them), and a `set` node `attrs.value`; see [API_COMPATIBILITY.md](../API_COMPATIBILITY.md) |
| `loom/next` | | as above, `result` is the JSON of `weave next --format json`: `steps`, the ten `stages` (`number`, `id`, `title`, `state` of `done`, `failing`, `not-started`, `skipped` or `stale`, `detail`, `command`), the `primary` stage, `preflight`, `realRun` and `projectRoot`. It is never cached (`cached` is always false): the answer depends on files the cache does not watch |
| `loom/review` | `tier` (`full` default, or `instant`: the validity lens only), `lenses?`, `minSeverity?`, `prices?`, `assume?` (list of `name=value`) | as above, `result` is the JSON of `weave review --format json --no-env` |
| `loom/symbols` | | as above, `result` is the JSON of `weave symbols`: the semantic index of the script and its imports (below) |
| `loom/explainRule` | `rule` | `{ rule, text }` the text of `weave review --explain RULE` |
| `loom/applyFix` | `fingerprints` (the findings to fix), or `safe: true` (every safe fix); or one finding in `fingerprints` with `option` and `answers` (an object) | `{ file, original, text, changed, applied[], withheld[] }`: the script's text after the fixes, each result checked as `weave review --fix` checks it (the script loads, no new high finding). Nothing is written; the editor writes `text`. `fix-refused` when an option cannot be applied |
| `loom/accept` | `fingerprints`, `reason` (required), `scope` (`finding` default, `thing`, `script`, `project`), `on?`, `until?` (a date), `by?`, `confirm?` (the number of findings, required to accept several serious ones at once) | `{ file, existed, original, text, written, lines[] }`: the new text of `review.properties`. The lethal trifecta (`LA01`) is refused with `trifecta-in-script`; a reason with a double quote is refused. Nothing is written |
| `loom/baseline` | `includeSerious?`, `note?` | `{ file, existed, text, recorded, leftOut }`: the text of `review.baseline.json` for today's open findings; serious ones stay open unless `includeSerious`. Nothing is written |
| `loom/ping` | – | answered by the reader thread, so it works however busy the workers are: `{busy, queued, abandoned}`. A client that has had a request time out pings; no answer means the process is stuck and is restarted |
| `loom/cancel` | `id` | (notification or request) withdraws request `id`: if it has not started it is answered `cancelled`; if it is running its answer is dropped and replaced by `cancelled` |
| `loom/shutdown` | | `{}` and the server exits with status 0 |

`cached` says whether the answer came from the cache. Reserved for later phases: `loom/test`, `loom/generate`. Debugging does not use this protocol: it is the Debug Adapter Protocol, spoken by `weave dap` on its own standard input and output (see "Debugging" below), so `loom/debug.*` stays unused.

## The symbols index

`weave symbols FILE` and `loom/symbols` give the same JSON (`version` 1). The lexer and parser are the language's own; a script with a syntax error still gets an answer for the parts that read (the unreadable character, string or block is set aside and the rest read again), `partial` is true and `problems` says what was set aside.

```json
{ "version": 1, "entry": "/abs/main.loom", "partial": false,
  "definitions": [ { "key": "agent:Replier", "kind": "agent", "name": "Replier", "file": "/abs/main.loom",
                     "range": { "start": {"line":0,"character":6}, "end": {"line":0,"character":13} },
                     "extent": { ... the whole block ... }, "detail": "model name", "type": { ... output schema ... } } ],
  "references":  [ { "to": "agent:Replier", "kind": "agent", "role": "delegate-target", "file": "...", "range": { ... }, "resolved": true } ],
  "bindings":    [ { "key": "binding:Main/intent", "name": "intent", "file": "...", "range": { ... }, "scope": "workflow:Main",
                     "role": "result", "producedBy": { "kind": "intentclassify", "range": { ... } },
                     "type": { "kind": "object", "fields": { "kind": { "kind": "enum", "values": ["a", "b", "unsure"] }, "urgent": { "kind": "yesno", "values": ["yes", "no"] } } } } ],
  "scopes":      [ { "workflow": "Main", "file": "...", "params": ["message"], "range": { ... } } ],
  "problems":    [ { "file": "...", "line": 12, "message": "..." } ] }
```

- **Ranges** are 0-based lines and columns in UTF-16 code units (VS Code's own), so an emoji counts two; tabs count one; CRLF does not move a column.
- **Keys** name one thing: `agent:`, `workflow:`, `tool:`, `classifier:`, `persona:`, `provider:`, `decision:`, `mcp:`, `knowledge:`, `routing:`, `prompt:ID`, `file:/abs/path` (an import), `task:Name` (supplied by the host, never defined in the script), `checkpoint:Workflow/name`, `binding:Workflow/name`. A rename is every range with one key.
- **Roles** of a reference: `delegate-target`, `handoff-target`, `broadcast-target`, `tool-use`, `approve`, `mcp-use`, `knowledge-use`, `routing-use`, `persona-use`, `model-provider`, `prompt-use`, `proposer`, `supervise`, `classify`, `decide`, `call`, `run`, `rewind`, `import`, and `use` (a variable read in a condition, a payload or a `{placeholder}`).
- **Types** are `string`, `number`, `boolean`, `yesno`, `enum` (`values`), `object` (`fields`), `list` (`of`) and `unknown`. `unknown` is a real answer: the script does not say, and nothing completes from it. A classifier fills an object with one field per question plus `confidence`, `calibrated`, `backend` and `model`; a `choose` question includes `unsure` only when the classifier escalates below its confidence.

## Errors

`{ "error": { "code": …, "message": "…", "data": { "kind": "…" } } }`

| code | kind | when |
|---|---|---|
| -32700 | `parse-error` | the frame is not JSON |
| -32600 | `invalid-request` / `bad-frame` | no `method`; a frame that cannot be read |
| -32601 | `unknown-method` | no such method |
| -32602 | `invalid-params` | a required param is missing or wrong; also `fix-refused`, `accept-refused`, `trifecta-in-script`, `confirm-count`, `no-such-finding` for a fix or accept that cannot be done (the message says why) |
| -32001 | `outside-workspace` | the file (or the price file) is not inside a root, after resolving `..` and links |
| -32002 | `timeout` | the request took longer than `--timeout-ms` (default 10 000); the work is abandoned |
| -32003 | `cancelled` | withdrawn by `loom/cancel` |
| -32004 | `engine-failure` | the command could not produce an answer (the message is its error) |
| -32603 | `internal` | a bug; the server carries on |

A script that has errors is **an answer**, not an error: `loom/check` returns `status: 2` with the problems in `result`.

## Never stalling

- Replies go through a dedicated writer; a client that stops reading cannot block a worker (replies are dropped, and the client's own timeout restarts the engine).
- Four workers, a queue of 64; beyond that a request is answered `busy` at once.
- A request that passes the time limit is answered `timeout`, and its worker is interrupted.
- A newer request of the same method for the same `file` replaces an older one still in flight (the older is answered `cancelled`).
- If more than four workers have been given up on and never came back, the server exits (status 3) and the client starts a new one.

## The cache

An answer is kept under the request (method, file, options) together with a digest of every file it was made from: the script and its imports, `review.properties`, `review.baseline.json`, the price file and the whole `prompts/` folder (so adding a prompt counts). The next identical request re-digests those files (and uses the buffer's text for the file the client sent) and answers from the cache only if every digest is the same. Memory is bounded (`--memory-mb`, default 64), least recently used first.

## Running it by hand

```
weave serve --stdio [--timeout-ms 10000] [--memory-mb 64]
```

## Debugging

`weave dap` speaks the [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) (`Content-Length` framing) on standard input and output; standard output carries only the protocol. It maps the editor's requests onto the stepper behind `weave step`, so nothing here is debugging logic of its own.

| DAP | Loom |
|---|---|
| `launch` | `script`, `workflow?`, `input?`, `effects` (only `simulate`), `models` (`mock`, `scripted` or `live`), for live `maxCost` with `prices`, or `maxTokens`, and `confirmed: true` (the editor asks the person first), `stopOnEntry?` (default true), `mask?` |
| `setBreakpoints` / `setFunctionBreakpoints` | a line (verified to the nearest statement at or after it), an optional `condition`; a function breakpoint is an agent or classifier name |
| `next` / `stepIn` / `stepOut` / `continue` | over (the whole statement), into, out of the block, to the next breakpoint |
| `stepBack` / `reverseContinue` | the run is replayed from its journal to the statement before; the state is re-derived, exact |
| `stackTrace` / `scopes` / `variables` | the statement, the statements it is inside, the workflow; one scope; each variable with the statement that set it in its value |
| `setVariable` / `evaluate` | an edit that survives going back; a variable path or a condition |
| `loom/why`, `loom/stepBack` | custom requests: where the run is, and stepping back |

One thread stands for the workflow (a `parallel` block's branches are not announced one by one). Events: `initialized`, `stopped` (`entry`, `step`, `breakpoint`), `output` (the model calls and tokens so far, at a breakpoint), `terminated`. A request it cannot do is answered `success: false` with the reason.

## What `weave serve` does not do: generate

`Loom: Generate Workflow...` and `Loom: Change Workflow...` do not use this protocol. They start `weave generate` as a process of their own and read its `--format json` events (one JSON object per line: `stage`, `plan`, `attempt`, `gate`, `result`). Generating calls a model and takes minutes; this server runs no model and answers in milliseconds, and the two are kept apart on purpose.
