# A2A starter: the code around llm4j that you provide

**What this is for:** llm4j helps you call and serve A2A agents but runs **no server, no poller and no authentication**. Those are yours, and they are the parts that most need care. This starter shows where each one goes, in a few small classes that use only the JDK. Read [What you provide](../../../docs/a2a/INTEGRATING.md) first; this is its code.

**It compiles and need not run.** It is not published and is built only with the extended profile (`mvn -Pextended -pl src/examples/a2a-starter test`). A test parses its script, so a change to the grammar cannot break it silently. It is a map of decisions, not a working service: every place that needs a decision of yours is marked `YOUR DECISION`.

| Class | What it shows |
|---|---|
| `StarterTransport` | How a call reaches a remote: https only, no redirects, a bound on the response, an address check that refuses loopback and private addresses, the credential resolved per request for its own host |
| `StarterChannel` | Who answers a remote's question (you supply it), that authorization is refused by default, and that a link or file offered is ignored until you decide |
| `StarterServer` | Mounting `A2AHandler`: serving the card with its cache headers, authenticating, parsing with `A2AJsonRpc10`, holding a request that must wait, writing errors |
| `TenantRunners` | One runner per scope key, and why filtering by scope is not isolation |
| `MemoryTaskStore` | Where tasks live, and why a durable one is yours to write |
| `StarterApp` | The pieces put together for one script |
| `src/main/resources/supplier.loom` | A script that calls a remote supplier and exposes one workflow |

## What it does not do

- **No authentication.** `StarterServer` reads a bearer token and uses it as the scope key. That proves nothing: replace it with a real check of who is calling, and put TLS in front.
- **No isolation between callers.** `StarterApp` uses one runner for every scope. Callers who must not see each other's data need a host each, with its own journal, files, memory, secrets and budgets.
- **No durable tasks.** `MemoryTaskStore` loses everything on restart.
- **No waiting.** Nothing here polls, streams or receives a push. The host calls `RemoteTasks.taskChanged` when it learns a task changed; see [the Loom guide](../../../docs/a2a/LOOM.md).
- **No card signing or verification.** llm4j does no cryptography.
- **Not run as a service.** It was compiled and its script parsed. The helper calls its server makes (the version check, the id of a request that cannot be parsed) are the ones llm4j's server half was run with against the A2A project's compatibility kit.

## The script

`supplier.loom` is not run through `weave check` as a separate scenario, because its card address is an example and its channels are supplied by the host. It does load cleanly with the other scripts of the repository (`RepositoryScriptsTest`), and the starter's own test parses it.
