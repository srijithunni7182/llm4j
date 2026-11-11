# A2A (Agent2Agent)

**Who this is for:** a Java developer who wants to call another organisation's agent over the A2A protocol with ai-agent4j.

ai-agent4j supports A2A **1.0** as a *facilitator*: a version-free model of cards, tasks and messages, a `RemoteAgent`, and the interfaces your code implements (`A2ATransport`, `AgentChannel`). It runs no server, HTTP client or poller; you provide the transport, the waiting and the hosting. It is in the package `io.github.llm4j.a2a`.

```java
RemoteAgent supplier = RemoteAgent.builder(cardAddress, transport, channel)
        .credential(SecretRef.of(secrets, "SUPPLIER_KEY"))
        .build();
SendResult result = supplier.send(A2AMessage.user("m-1", List.of(new Part.Text("Quote 500 units."))));
```

Before anything is sent, a call is refused if the card points at a host you did not declare, offers no endpoint your transport supports, carries a failed signature or requires an extension you do not support. Every question, authorization request and file from the remote goes through your channel, inside limits the runtime enforces.

The full documentation is in [docs/a2a](../../../docs/a2a/README.md): [what A2A is](../../../docs/a2a/WHAT_IS_A2A.md), [our approach](../../../docs/a2a/PHILOSOPHY.md), [the Java guide](../../../docs/a2a/JAVA_GUIDE.md), [what you provide](../../../docs/a2a/INTEGRATING.md) and the [reference](../../../docs/a2a/REFERENCE.md).
