# Reference

**Who this is for:** a developer who knows what they want and needs the exact name, value or default. For how the pieces fit, read [The Java guide](JAVA_GUIDE.md). The reference grows with each part of the A2A support; this version covers the Java calling side.

## Task states

`io.github.llm4j.a2a.TaskState`:

| State | Terminal | Meaning |
|---|---|---|
| `WORKING` | no | Accepted or running (includes the protocol's "submitted") |
| `INPUT_REQUIRED` | no | The remote needs an answer from you |
| `AUTH_REQUIRED` | no | The remote needs you to arrange authorization out of band |
| `COMPLETED` | yes | Finished successfully |
| `FAILED` | yes | Finished with an error |
| `CANCELED` | yes | Stopped on request |
| `REJECTED` | yes | The agent declined the task |
| `UNKNOWN` | no | A state llm4j does not recognise; **never** treated as completed |

A finished task takes no more messages. `INPUT_REQUIRED` and `AUTH_REQUIRED` are the states `RemoteAgent.reply` accepts.

## Types in `io.github.llm4j.a2a`

| Type | What it is |
|---|---|
| `AgentCardView` | A card without a protocol version in its shape: name, description, skills, endpoints in order of preference, default input media types, extensions, `SignatureStatus`, authentication scheme names |
| `A2AMessage`, `Part` (`Text`, `Data`, `FileOffered`), `Artifact` | Messages and their content |
| `RemoteTask`, `SendResult` (`TaskResult`, `MessageResult`), `SendOptions` | Tasks and the answer to a send |
| `A2ATransport` | The one way out of the process; `fetchCard`, `send`, `get`, `cancel`, `recentTasks`, `supports`, `supportedExtensions` |
| `RemoteAgent` | `builder(cardAddress, transport, channel)`, then `send`, `reply`, `get`, `cancel` |
| `EndpointGuard` | Chooses the endpoint and refuses one off the declared host |
| `AgentChannel`, `ChannelMessage` (`Question`, `AuthorizationNeeded`, `FileOffered.Link`, `FileOffered.Inline`), `ChannelResponse` (`Reply`, `Defer`, `Refuse`, `Received`, `Ignore`), `ChannelContext` | What the consumer implements and what it exchanges |
| `ExchangeGuard`, `ExchangeGuard.Waiter`, `ExchangeGuard.ReplyValidator` | The bounds around every channel call |
| `ReceivedFileSink`, `ReceivedFile` | Received files |
| `A2AJsonRpc10` | The A2A 1.0 JSON-RPC translation; the only class with wire names |
| `A2AException` | The base of every error below |

On JDK 21 and later a `switch` over `ChannelMessage` or `ChannelResponse` needs no default branch. The project builds for Java 17, where pattern matching in `switch` is a preview feature, so its own code uses `instanceof`.

## Serving types

| Type | What it is |
|---|---|
| `A2AHandler` | `new A2AHandler(TaskRunners)`; `handle(CallerIdentity, A2ARequest)` returns a `CompletionStage<A2AResponse>`; page size 1 to 100, default 50 |
| `CallerIdentity` | One non-blank scope key |
| `A2ARequest` | `Send`, `GetTask`, `ListTasks`, `CancelTask` |
| `A2AResponse` | `TaskResponse`, `MessageResponse`, `TaskPage` (its `nextPageToken` is never null; empty on the last page) |
| `TaskRunner`, `TaskRunner.OwnedTask`, `TaskRunners` | What requests map onto, and which runner serves a scope |
| `AgentCardBuilder` | Builds the card; streaming, push notifications and the extended card default to false |
| `AgentTaskRunner`, `TaskStore` | Exposes plain `ReActAgent`s; needs `confirmAgentsHaveLimits()` |
| `A2AJsonRpc10` | Also: `parseRequest`, `writeResponse`, `writeError`, `writeCard` |

## Loom: settings and defaults

| Declaration | Setting | Required | Default |
|---|---|---|---|
| `remote Name { }` | `card` (an `https` address) | yes | |
| | `agent_channel` (a declared channel) | yes | |
| | `auth` (`secret.NAME` or `env.NAME`; never a literal) | no | no credential |
| | `timeout` (a duration such as `2h`) | no | 1h |
| | `require_signed_card` (`true` or `false`) | no | false |
| `agent_channel Name { }` | `audience` (`staff`, `caller` or `automatic`) | yes | |
| | `max_questions` (1 or more) | no | 3 |
| | `timeout` | no | 1h |
| `ask Channel "text" -> var` | `expecting { }`, `retry`, `backoff`, `timeout`, `on_failure { }` | no | |
| `delegate "text" to Remote -> var` | `expecting { }`, `retry`, `backoff`, `timeout`, `on_failure { }`, `attach` | no | |

There is no setting for an A2A version and none for `pii:`. A step that calls a remote may not carry a token `budget`.

## Loom: what a call to a remote binds

| Variable | Holds |
|---|---|
| `result` | The joined text; with `expecting { }`, one object checked against the shape |
| `result_data` | The structured parts, as a list |
| `result_files` | The files the channel accepted: `path`, `kind`, `mediaType`, `size`, `sha256` |

## Loom: suspension and the host

| Name | Meaning |
|---|---|
| `RunSuspended.Reason.REMOTE` | A remote task is not finished; the run holds no thread. `resumeAt` is the step's deadline |
| `RemoteTasks.wiring(transport, channels)` | Goes into `LoomHost.Builder.customizer`; gives every run its transport and its channels |
| `RemoteTasks.taskChanged(runId, taskId)` | Resumes the run waiting on that task; a repeat or stale call changes nothing |
| `HarnessExecutor.setA2ATransport`, `setAgentChannel` | The same wiring for a single executor |

## Loom: trace events

| Event | When |
|---|---|
| `remote` (`TraceEvent.REMOTE`) | `data.kind` is `send`, `state`, `found` (an earlier send was found after a crash), `cancel`, `cancel_failed`, `done`. Remote text is never put here unshortened |
| `exchange` (`TraceEvent.EXCHANGE`) | `data.kind` is `question`, `authorization`, `file`, `ask` or `reply`, with the channel, the direction, a digest (never the text) and the idempotency key |

## Loom: limits

| Limit | Value |
|---|---|
| Largest reply from a channel | 64 KiB |
| Questions per run and step | the channel's `max_questions` |
| Received files | the script's `media { max_bytes }` and `max_items` |

## Errors

All are unchecked subclasses of `A2AException` (itself an `LLMException`); callers never see a wire code.

| Exception | When |
|---|---|
| `InvalidParams` | A request is not valid: a mismatching context and task, a page size out of range, a context id the agent cannot honour (never replaced) |
| `MethodNotFound` | The method is not one the helper maps |
| `Refused` | llm4j refused before sending: off-host card, no supported endpoint, failed or missing signature, required extension, file type, a task not waiting on you. Retrying cannot change the outcome |
| `TaskNotFound` | The remote has no such task, or you may not see it |
| `TaskNotCancelable` | The task cannot be canceled in its state |
| `UnsupportedOperation` | The remote does not support the operation, or the task is finished |
| `ContentTypeNotSupported` | The remote does not accept a content type you sent |
| `VersionNotSupported` | The remote does not speak the version the transport sent |
| `ExtensionSupportRequired` | The remote requires an extension you did not declare |
| `Malformed` | A response could not be understood |
| `ChannelFailure` | A channel broke its contract, or the question limit, timeout or reply size was exceeded |

## Limits and defaults

| Limit | Where set | Default |
|---|---|---|
| Questions per run and step | `ExchangeGuard(maxQuestions, ...)` | none: you pass it |
| Time for one channel call | `ExchangeGuard(..., timeout, ...)` | none: you pass it |
| Reply size in bytes | `ExchangeGuard(..., maxReplyBytes, ...)` | none: you pass it |
| Bytes per received file | `MediaPolicy.maxBytesPerItem` | 20 MiB |
| Received files per task | `ReceivedFileSink(..., maxItems)` | none: you pass it |
| Page size of a list | `A2AHandler` | 50 (1 to 100) |

The Loom settings (`max_questions`, `timeout`, `media { max_bytes }`) and their defaults are added with the Loom support.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [The Java guide](JAVA_GUIDE.md)
