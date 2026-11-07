/**
 * A2A (Agent2Agent) support: a protocol-version-free model of agent cards, tasks and messages, the
 * {@link io.github.llm4j.a2a.A2ATransport} a consumer implements, a {@link
 * io.github.llm4j.a2a.RemoteAgent} for calling other agents, and the {@link
 * io.github.llm4j.a2a.AgentChannel} that answers what comes back. llm4j supplies meaning and
 * safety; the consumer supplies transport, waiting, hosting and storage. Nothing here runs a
 * server, an HTTP client or a poller.
 */
package io.github.llm4j.a2a;
