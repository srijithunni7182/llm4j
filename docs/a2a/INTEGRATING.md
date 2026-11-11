# What you provide

**Who this is for:** the developer or operator who wires llm4j's A2A support into an application. llm4j runs nothing, so the running parts are yours. This page lists each duty, what llm4j already does for you, and what it refuses to do on your behalf. Sections for serving (mounting a handler, scopes, tenants) arrive with the handler.

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

## Waiting

llm4j never waits, polls or sleeps. You choose how to follow a task: ask again from time to time, hold a stream open, or register a webhook. If you use push notifications, they are delivered at least once, so a repeat or a stale one must change nothing, and the webhook's own security (authenticating the caller, answering with a success status) is yours.

## Where files live

Received files go under `received/<run id>/` in the folder you give `ReceivedFileSink`. Keep that folder as durable as the record of the run that uses it: a resumed run checks the files again.

## Operations

TLS, rate limits, scaling, monitoring and the identity provider are yours.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [The Java guide](JAVA_GUIDE.md) · [Reference](REFERENCE.md)
