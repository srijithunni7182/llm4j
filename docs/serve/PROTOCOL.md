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
{ "protocol": 1, "weave": "6.0.0", "methods": ["loom/check", "loom/graph", …], "roots": ["…"] }
```

A client refuses a server whose `protocol` differs from the one it speaks and tells the user to reinstall. The protocol is **additive**: unknown request fields are ignored; unknown methods get a typed error; new fields appear in answers within version 1.

## Requests

Every method takes `file` (absolute or relative to the working directory; it must be inside a root) and may take `text` (the unsaved buffer, used instead of the file's content, imports still read from disk or their own buffers) and `version` (any number; the answer carries it back as `requestVersion` so a client can drop an answer for an older document).

| Method | Extra params | Answer |
|---|---|---|
| `loom/check` | | `{ file, status, result, stderr?, cached, requestVersion }` where `result` is the JSON of `weave check --format json --no-env`; `status` 0 or 2 |
| `loom/graph` | `workflow?` | as above, `result` is the JSON of `weave graph` |
| `loom/review` | `tier` (`full` default, or `instant`: the validity lens only), `lenses?`, `minSeverity?`, `prices?`, `assume?` (list of `name=value`) | as above, `result` is the JSON of `weave review --format json --no-env` |
| `loom/explainRule` | `rule` | `{ rule, text }` the text of `weave review --explain RULE` |
| `loom/cancel` | `id` | (notification or request) withdraws request `id`: if it has not started it is answered `cancelled`; if it is running its answer is dropped and replaced by `cancelled` |
| `loom/shutdown` | | `{}` and the server exits with status 0 |

`cached` says whether the answer came from the cache. Reserved for later phases: `loom/symbols`, `loom/applyFix`, `loom/test`, `loom/generate`, `loom/debug.*`.

## Errors

`{ "error": { "code": …, "message": "…", "data": { "kind": "…" } } }`

| code | kind | when |
|---|---|---|
| -32700 | `parse-error` | the frame is not JSON |
| -32600 | `invalid-request` / `bad-frame` | no `method`; a frame that cannot be read |
| -32601 | `unknown-method` | no such method |
| -32602 | `invalid-params` | a required param is missing |
| -32001 | `outside-workspace` | the file (or the price file) is not inside a root, after resolving `..` and links |
| -32002 | `timeout` | the request took longer than `--timeout-ms` (default 10 000); the work is abandoned |
| -32003 | `cancelled` | withdrawn by `loom/cancel` |
| -32004 | `engine-failure` | the command could not produce an answer (the message is its error) |
| -32603 | `internal` | a bug; the server carries on |

A script that has errors is **an answer**, not an error: `loom/check` returns `status: 2` with the problems in `result`.

## The cache

An answer is kept under the request (method, file, options) together with a digest of every file it was made from: the script and its imports, `review.properties`, `review.baseline.json`, the price file and the whole `prompts/` folder (so adding a prompt counts). The next identical request re-digests those files (and uses the buffer's text for the file the client sent) and answers from the cache only if every digest is the same. Memory is bounded (`--memory-mb`, default 64), least recently used first.

## Running it by hand

```
weave serve --stdio [--timeout-ms 10000] [--memory-mb 64]
```
