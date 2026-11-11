# The Java guide: calling a remote agent

**Who this is for:** a Java developer using ai-agent4j who wants to call an A2A agent that someone else runs. You need to know what a [task and a message are](WHAT_IS_A2A.md); you do not need Loom. Serving your own agent is covered when the handler ships.

## The pieces, and who writes which

| Piece | Written by | What it is |
|---|---|---|
| `RemoteAgent` | llm4j | Checks the card, chooses the endpoint, refuses what must not be sent, makes one call per operation |
| `A2ATransport` | **you** | Moves requests and responses; speaks the binding and version the remote uses; resolves credentials per host; validates addresses |
| `A2AJsonRpc10` | llm4j (optional) | Builds and parses A2A 1.0 over JSON-RPC so your transport only moves bytes |
| `AgentChannel` | **you** | Answers what comes back that needs a decision: a question, a request for authorization, a file or a link |
| `ExchangeGuard` | llm4j | Wraps every channel call: question limit, timeout, reply size, reply validation |
| `ReceivedFileSink` | llm4j | Writes a received file under `received/<run id>/` in your media root, checking size and type |

## Call an agent

<!-- compiles -->
```java
package docs.a2a;

import io.github.llm4j.a2a.A2AMessage;
import io.github.llm4j.a2a.A2ATransport;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.Part;
import io.github.llm4j.a2a.RemoteAgent;
import io.github.llm4j.a2a.RemoteTask;
import io.github.llm4j.a2a.SendResult;
import io.github.llm4j.a2a.TaskState;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import java.util.List;

public final class CallASupplier {

    private final RemoteAgent supplier;

    public CallASupplier(A2ATransport transport, SecretStore secrets) {
        AgentChannel channel =
                (context, message) -> {
                    if (message instanceof ChannelMessage.Question question) {
                        // who answers is your decision: a person, a service, a rule
                        return new ChannelResponse.Reply("Within 30 days.", null);
                    }
                    return new ChannelResponse.Refuse("this desk only answers questions");
                };
        this.supplier =
                RemoteAgent.builder(
                                "https://supplier.example.com/.well-known/agent-card.json",
                                transport,
                                channel)
                        .credential(SecretRef.of(secrets, "SUPPLIER_KEY"))
                        .build();
    }

    /** Starts the work and returns at once; it never waits. */
    public String start(String part) {
        A2AMessage goal =
                A2AMessage.user("quote-" + part, List.of(new Part.Text("Quote 500 units of " + part)));
        SendResult result = supplier.send(goal);
        if (result instanceof SendResult.MessageResult answer) {
            return "answered at once: " + answer.message().text();
        }
        return ((SendResult.TaskResult) result).task().id();
    }

    /** Looks at a task once. Whoever calls this decides when to look again. */
    public String look(String taskId) {
        RemoteTask task = supplier.get(taskId);
        if (task.state() == TaskState.INPUT_REQUIRED) {
            return "needs an answer: " + task.statusMessage().text();
        }
        return task.state().isTerminal() ? "finished: " + task.state() : "still " + task.state();
    }
}
```

Three things to notice.

- **Nothing waits.** `send`, `get`, `reply` and `cancel` each make one call and return. When to ask again (a timer, a stream, a webhook) is yours: see [What you provide](INTEGRATING.md).
- **A send may return a message and no task.** Handle `SendResult.MessageResult`.
- **The credential is a reference.** You pass a `SecretRef`; llm4j never resolves it. Your transport resolves it for the host the endpoint names, so a key is never sent to another host.

## What `RemoteAgent` refuses before sending

Each refusal is an `A2AException.Refused` that says what to do, and nothing has left the process when it is thrown.

| Refused | Because |
|---|---|
| The card names an address on another host than the one you declared | A card cannot redirect a call; redirects, private addresses and TLS are your transport's |
| No endpoint the transport supports | The message lists the bindings and versions the card offered |
| A signature that failed verification (always), or one that is not verified when you set `requireSignedCard(true)` | llm4j does no cryptography; your transport verifies and reports `VERIFIED`, `FAILED`, `NOT_SIGNED` or `NOT_CHECKED` |
| A required extension your transport does not list in `supportedExtensions()` | The remote would reject the call anyway |
| A file whose type the card does not accept | If the card declares none, the file is sent and the remote may refuse it |
| `reply` on a task that is not waiting on you | A finished task takes no more messages; a refinement is a new `send` naming the same context |

## Answer a question

When a task is `INPUT_REQUIRED` or `AUTH_REQUIRED`, hand the question to your channel through an `ExchangeGuard`, which holds the bounds whatever the channel does: the number of questions, the time, the size of the reply, and your check of its shape. Then send the reply with `RemoteAgent.reply`. An authorization request goes to the channel as its own kind of message, and your reply to it is only a message: the credential is arranged out of band and never travels in a reply.

A file or link offered by the remote arrives as a `ChannelMessage.FileOffered`. llm4j never follows a link; your channel decides, validating the address against server-side request forgery. To accept a file, write it through `ChannelContext.receiveFile`, which checks the size and decides the type from the bytes.

## Test it without a network

Write a hand-made `A2ATransport` that records calls and answers from a script, and call `RemoteAgent` against it. The repository's own tests do this, and also run the real helper against a mock A2A 1.0 agent over loopback.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [What you provide](INTEGRATING.md) · [Reference](REFERENCE.md)
