# Our approach: a facilitator, not a decision-maker

**Who this is for:** anyone deciding whether, and how, to use llm4j's A2A support, and anyone reviewing a change to it. Every design choice in the A2A work is checked against this page, and a change to a principle is the owner's decision, made here before it is made in code. For what A2A itself is, read [What A2A is](WHAT_IS_A2A.md) first.

## Why llm4j does not host an A2A server

llm4j is a framework for building agents and workflows. A2A, like any protocol, has a part that is about **meaning** (what a card, a task and a message are, and how they map onto an agent or a workflow) and a part that is about **running a service** (a web server, TLS, authentication, storage, scaling, polling loops and webhook receivers). We provide the first and leave the second to the people who use the framework. The reasons:

- **Hosting is full of decisions that belong to the consumer.** Which web stack, which address, which certificate, which identity provider, where task state is kept, how many instances run, how tenants are separated. The specification itself says a server must authenticate every request and authorises according to its own policies. Those policies are the operator's.
- **A framework that owns a public endpoint owns its security for everyone.** Every deployment's attack surface would become ours to patch and to explain. A handler that someone mounts in their own server keeps that responsibility with the people who run it.
- **It would break the promise to stay light.** llm4j avoids framework dependencies, background threads nobody asked for, and static initialisers that do work. A built-in server and polling loop would need all three.
- **People already have servers.** Teams use Spring, Javalin, Quarkus, a gateway or a cloud function. What they lack is the protocol layer, and that is what llm4j gives them.

What llm4j does provide: a version-free model of agents, tasks and messages (principle 9); a card builder that turns a script's declaration into card JSON; a handler that takes an A2A request and returns a response (or a stream of events), which any web server can mount; a `RemoteAgent` for calling other agents; one optional helper that translates A2A 1.0 over JSON-RPC to and from that model; and the interfaces a consumer fills in. In short, **llm4j supplies something to mount, never something to run.**

### What the consumer provides

Because llm4j runs nothing, the people who use it supply the running parts. The framework's documentation has to say each of these plainly, with a worked example, because they are the consumer's duty and not a detail:

- **A transport**, to move requests and responses to the other agent. It speaks whatever binding and version that agent uses, adds any headers, tokens or retries the organisation needs, resolves credentials from the secret store for the right host, validates addresses, and never follows a redirect it should not. llm4j supplies the model and, optionally, a helper that translates A2A 1.0 over JSON-RPC.
- **A channel**, for what comes back that needs a decision: a question, a request for authorization, a link. It decides who answers, and whether to fetch a link and from where. Fetching a link is the consumer's responsibility, including validating the address against server-side request forgery, as the specification requires.
- **The ways of waiting.** llm4j never waits or polls. The consumer chooses to poll, to stream, or to receive push notifications, tells the workflow when a task has changed, and handles a notification that arrives twice or late. A consumer that registers a webhook also owns its security: authenticating the caller and answering with a success status.
- **To serve:** mount the handler on a route and a well-known card address, authenticate every request, decide what each caller may do, and tell the handler who the caller is as one scope key, and say which host serves that scope. The handler never accepts a request without an identity and shows a task only to its own scope.
- **When a caller wants to be answered only when the task finishes or pauses** (the specification's default for a send), the handler gives the host a future or a callback to complete, and the host decides how to wait, for how long, and what to do on a timeout. llm4j never holds a request open itself.
- **Isolation.** If tenants need their data kept apart, give each its own host: its own journal or database, its own folder for files, and its own memory, knowledge stores, secrets and budgets. Everything a host shares is shared by every scope served by it, including what an agent remembers, which can surface in another caller's answer.
- **Where tasks and files live.** A workflow's journal is durable, and the folder for received files has to survive with it, because a resumed run checks the files again. For a plain agent exposed from Java, the consumer supplies the task store.
- **Cards:** serving the card with sensible caching headers, signing it if the organisation wants that, and verifying a signature on a card it receives, through a hook llm4j provides.
- **Operations:** TLS, rate limits, scaling, monitoring and the identity provider.

## Our philosophy: a facilitator, not a decision-maker

llm4j makes it possible to expose and to call agents over A2A. It does not decide how a particular organisation should do either. These principles are the test for every choice made during the work:

1. **Mechanism, not policy, for plumbing.** For transport, storage, waiting, authentication, fetching a link and who answers a question, llm4j defines an interface and the consumer implements it. We ship **no default transport**, no polling loop, no Spring adapter and no ready-made tool wrapper for remote agents. A consumer's own implementation can add any header, token or retry rule it needs.
2. **Opinionated only about safety.** Where a choice protects people from a failure they cannot easily see, llm4j decides and fails closed. The safety rules in this work are fixed, and a consumer cannot turn them off:
   - A credential is a reference to a secret and never text. It is resolved by the consumer's code for the host it is bound to.
   - A card cannot redirect a call. Before anything is sent, the card's endpoint must be on the host the script declared.
   - Everything from outside is untrusted: card text, replies, questions, links, files and incoming messages. Replies are validated against a declared shape before the workflow sees them.
   - Credentials never travel through a reply. A request for authorization is its own kind of message to the channel, and the consumer's code obtains the credential out of band.
   - llm4j never follows a link it is given. A link goes to the channel, which decides. A file that comes back through the channel is checked like any media: the type is decided from its bytes, the size is capped while it is written, and SVG is refused.
   - Every exchange is bounded: the number of questions, the time allowed, and the size of messages. The bound is enforced by the runtime and not by consumer code, because a bound that depends on the consumer's code is not a bound.
   - An exchange is an effect. It is journaled, and a resume or retry never sends the same reply twice.
   - A failed check denies. An unknown value, a missing setting or an unreachable card never means "allow".
3. **The script declares what; the host decides who, where and how.** A script says that a remote agent is called (`remote`), that a workflow is exposed (`expose`), and who answers questions in terms the audit can read (`agent_channel`, with an audience, a question limit and a timeout). The host decides the address, the login and the implementation. This keeps the facts a reviewer needs in the script, which is where `weave check`, `weave audit` and the graph can see them.
4. **What leaves or enters is visible.** Nothing crosses the boundary invisibly. A remote agent is a different kind of declaration from a local agent, because it differs in the ways that matter: it is opaque, its reply is untrusted, its cost is outside the budgets, and it can take hours.
5. **Honest about what cannot be checked.** A remote agent's card is fetched at run time and checked then. `weave check` says so in a warning and does not pretend it validated anything. The same honesty applies to a channel: the script declares the audience, and a reviewer reads the implementation once, as with a tool's declared reach.
6. **Additive, and nothing existing changes.** `human_prompt`, `approve`, the person channel (console, Telegram) and `LoomHost` keep working exactly as they do. A2A arrives as new declarations and new types.
7. **No ownership of waiting.** llm4j does not poll, sleep or schedule on behalf of a consumer. It returns the state of a task, and whoever drives the call decides when to look again. In Loom, a durable run pauses and the host resumes it. This holds when the protocol asks a server to wait: where a caller wants an answer only when the task finishes or pauses, the handler hands the host something to complete and does not block a thread itself.
8. **Smallest footprint.** The model and the handler interface go in the core with no new dependencies. Loom's adapter depends on the core, never the other way round. No HTTP server or client library is added for A2A.
9. **Version-agnostic.** llm4j's own model is normalised and carries no protocol version. It holds only what llm4j's behaviour needs, and the consumer's transport translates between it and the wire for whatever version and binding the other agent speaks.
   - **What the model holds.** For an agent: its name, description, skills, the endpoints it offers (address, binding, version) and the input types it accepts. For a task: its id, its context id and one state from a small fixed set (working, input needed, authorization needed, completed, failed, canceled, rejected, or unknown). For messages and artifacts: text and structured data.
   - **Nothing above the transport knows a version.** That covers the Loom syntax, `agent_channel`, the journal, the audit rules and the card check. A script never states an A2A version, and the `remote` block has no version setting.
   - **One place knows one version.** The single optional helper that translates A2A 1.0 over JSON-RPC is the only code in llm4j that knows a wire name. A consumer who speaks another version or binding writes their own translation and uses everything else unchanged. We do not add `if (version == X)` branches to shared code.
   - **Fail closed on what is not understood.** If the card offers no interface the transport can speak, or marks an extension as required that the transport does not support, the call is refused before anything is sent, with a message that names what was offered. A state that is not recognised becomes *unknown*: it is never treated as completed, it is reported to the consumer, and if it lasts until the timeout the step fails. Unknown fields in incoming data are ignored, so a newer agent's extra fields do not break a caller.
10. **Tenant-agnostic.** A workflow, and the host that runs it, know nothing about tenants. llm4j enforces the scope boundary it is given: the consumer tells the handler who the caller is as a single scope key, and a task is visible only to that key. How strongly tenants are isolated is the consumer's decision and duty. A consumer that needs data isolation gives each tenant its own host, with its own journal, files, memory, secrets and budgets. Filtering by scope inside one shared host is defence in depth and is never described as isolation.
11. **The ecosystem moves together.** Loom is a language with an editor extension, a compatibility kit, an inspection and Fabric stack, test and evaluation tools, a tutorial, starters, skills and many documents, and each of them describes or lists the language. A construct is not finished until every one of them knows it, and they are updated in the same change as the construct and not afterwards. Where a test catches a miss it is kept; where nothing does yet, the maintainer reviews each change against the list of surfaces.

**What we will not do**, so nobody has to ask: run a server; ship an HTTP client or poller for A2A; follow every protocol version in our own types; provide an identity or login system; operate a registry or directory of agents; wrap a remote agent as a tool; or add a framework adapter. Each of these is a reasonable thing for a consumer to build on top.

**When in doubt**, ask in this order: Does this make llm4j's own types or syntax depend on a protocol version? If so, move it into the translation. Does this move a decision about the consumer's environment into llm4j? If so, make it an interface. Does this protect against a failure the consumer cannot easily see? If so, llm4j decides and fails closed. Can a reviewer see it in the script? If not, it needs a declaration.

A change to these principles is the owner's decision and is made here, in this page, before it is made in code.

## Next

[A2A support in llm4j](README.md) · [What A2A is](WHAT_IS_A2A.md) · [What you provide](INTEGRATING.md)
