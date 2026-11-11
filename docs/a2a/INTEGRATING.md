# What you provide

**Who this is for:** the developer or operator who wires llm4j's A2A support into an application. llm4j runs nothing, so the running parts are yours. This page lists each duty, what llm4j already does for you, and what it refuses to do on your behalf. The serving duties are below.

## The transport

You implement `A2ATransport`; llm4j ships no default. It is the one way anything leaves the process.

| Your duty | What llm4j does |
|---|---|
| Speak the binding and protocol version the remote uses | Gives you `A2AJsonRpc10`, which builds and parses A2A 1.0 over JSON-RPC, so a JSON-RPC transport only moves bytes. For another version or binding you write the mapping and use the rest unchanged |
| Add headers, tokens, retries and timeouts | Passes a `SecretRef` and never resolves it |
| Resolve the credential **for the host the endpoint names** and no other | Checks that the card's endpoint is on the host the script declared |
| Validate addresses: refuse loopback, private and cloud-metadata addresses; never follow a redirect you should not; use TLS | Nothing: these are yours. `NetPolicy` and `HttpSupport` in `ai-agent4j-tools` are what to build on |
| **Bound the size of every response** you read | Nothing: a hostile remote can send a very large body |
| Verify a card's signature if you want to, and report `VERIFIED`, `FAILED`, `NOT_SIGNED` or `NOT_CHECKED` in the card view | Always refuses `FAILED`; refuses anything but `VERIFIED` when the call asks for a signed card; does no cryptography |
| Say which extension URIs you support (`supportedExtensions()`) | Refuses a card that requires an extension you did not list |
| List recent tasks with history (`recentTasks`) | Uses it to look for an earlier send after a crash |

## The channel

You implement `AgentChannel` for what comes back that needs a decision. You decide who answers, and whether and from where a link is fetched. llm4j wraps every call in an `ExchangeGuard`, so these hold whatever your code does: the number of questions, the timeout, the size of a reply, and the check of a structured reply. A channel that throws, returns nothing, or answers with a response that does not fit the message fails the exchange; nothing is dropped silently.

- **Questions.** Reply with text and/or data, defer (`Defer` with a token you complete later), or refuse.
- **Authorization requests.** The reply is only a message. The remote agent receives the credential out of band; never put one in a reply.
- **Links.** llm4j never follows one. If you fetch it, validate the address against server-side request forgery, and write the bytes through `ChannelContext.receiveFile`.
- **Files.** An inline file is offered the same way as a link, so you can refuse it. Accepting means writing the stream; the runtime counts the bytes, stops at the size limit, decides the type from the bytes, refuses SVG and unknown types, and moves the file into place only when it is complete. The sender's name is a label and is never part of a path.

## Serving

| Your duty | What llm4j does |
|---|---|
| Mount the handler on a route, and the card at a well-known address (`/.well-known/agent-card.json` by convention) | `A2AHandler` takes a request and gives a response; `AgentCardBuilder` and `A2AJsonRpc10.writeCard` make the card. They run nothing |
| Serve the card with sensible caching headers, and sign it if your organisation wants that | Gives you an `ETag` derived from the card's content. Signing is yours |
| **Authenticate every request** and decide what each caller may do | Nothing: the specification says a server must authenticate every request and decides by its own policies. Those are yours |
| Tell the handler who the caller is as one scope key, and say which runner serves that scope | Refuses a request with no identity; shows a task only to the scope that created it |
| Hold a request that must wait (the specification's default for a send), for as long as you choose | Returns a stage and holds no thread; you complete or time it out |
| Read the `A2A-Version` the client asks for and refuse what you do not speak (no header means 0.3) | `A2AJsonRpc10.requireSupportedVersion` does the comparison and throws `VersionNotSupported`; reading the header is yours |
| Answer a request that cannot be parsed with its own id | `A2AJsonRpc10.idOf` reads the id; streaming, an extended card and push notification configuration are answered with the errors the specification names, and only an unknown method is `MethodNotFound` |
| Turn an `A2AException` into the protocol's error | `A2AJsonRpc10.writeError` does it, with no internal message in an unexpected failure |
| Keep tenants' data apart if they need that: **one host per tenant** | Filters by scope key inside a host, as defence in depth. Everything a host shares (journal, files, memory, secrets, budgets) is shared by every scope it serves, including what an agent remembers, which can surface in another caller's answer |
| For `AgentTaskRunner`: the per-task agent function, the `TaskStore`, the `Executor`, and restarting or failing unfinished tasks at startup | Runs one `agent.run(...)` per task; refuses to start until you confirm the agents have limits |
| Declare streaming or push notifications only if your host provides them | The card says no to both by default |

## Waiting

llm4j never waits, polls or sleeps. You choose how to follow a task: ask again from time to time, hold a stream open, or register a webhook. If you use push notifications, they are delivered at least once, so a repeat or a stale one must change nothing, and the webhook's own security (authenticating the caller, answering with a success status) is yours.

## Where files live

Received files go under `received/<run id>/` in the folder you give `ReceivedFileSink`. Keep that folder as durable as the record of the run that uses it: a resumed run checks the files again.

## Operations

TLS, rate limits, scaling, monitoring and the identity provider are yours.

## The starter

Every duty above has code in [the starter](../../src/examples/a2a-starter/README.md): a transport, a channel, a serving route, a runner per caller and a task store, each marked where a decision is yours. It compiles in the extended build and its script is parsed by a test; it is a map and not a working service. Its server uses the same helper calls as the one the compatibility kit was run against.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [The Java guide](JAVA_GUIDE.md) · [Reference](REFERENCE.md)
