# Why AI Agent4J?

In a world full of heavy AI frameworks, **AI Agent4J** (formerly Gemini ReAct Java) is built on a different philosophy. If you've looked at LangChain4j or Spring AI and felt they were "too much" for your project, this is for you.

## 🚀 The Core Philosophy: Zero Magic

Most AI frameworks try to hide the LLM behind complex abstractions. **AI Agent4J** gives you full control.

1. **Lightweight (< 200KB)**: No massive dependency trees. We don't pull in Spring Boot, Netty, or Hibernate unless you explicitly ask for the Addons.
2. **Pure, Typed Java**: No complex XML or annotation-driven magic. It's just clean, object-oriented Java code that your IDE (and an LLM) can understand instantly.
3. **Multi-Provider by Design**: While we started with Gemini, the architecture is provider-agnostic. Switching from Gemini Pro to a local Llama model via `RoutingLLMClient` is a one-liner.

---

## 🆚 Comparison with Other Frameworks

| Feature | **AI Agent4J** | LangChain4j | Spring AI |
| :--- | :--- | :--- | :--- |
| **Startup Time** | Near-instant | Moderate | Slower (Spring Overhead) |
| **Binary Size** | Tiny (<200KB) | Large | Large |
| **Learning Curve** | 15 minutes | Days | Moderate (if you know Spring) |
| **Transparency** | High (Pure ReAct loop) | Lower (Complex Chains) | Moderate |
| **Deterministic Planning** | Optional (ToolRegistry) | Low | Low |
| **Local-First RAG** | Yes (ONNX Addon) | Yes | Yes (via Spring ecosystem) |

---

## 🧠 Unique Autonomous Foundations

We aren't just a wrapper for Chat APIs. We've built the "Foundation Blocks" for real digital assistants:

### 1. Agent-to-Agent Delegation
Managers can spawn constrained sub-agents. This is the **Manager/Worker** pattern done right, preventing context explosion and keeping costs low by using cheap models for simple tasks.

### 2. Proactive scheduling
Most agents are reactive. Ours can be proactive. Using the `AgentScheduler`, an agent can decide: *"I'll check the logs again in 2 hours and let you know if there's an error."*

### 3. Integrated Semantic Memory
We treat memory as a first-class citizen. With `SemanticMemoryConfig`, you get a production-ready RAG pipeline (pgvector/ONNX) wired up in one line of code.

---

## 🎯 When to use it?

- **Microservices**: When you need a tiny footprint and fast startup.
- **Embedded Agents**: When you want to add AI to an existing legacy Java app without redesigning the architecture.
- **Performance Critical**: When you need tens of thousands of agents running concurrently without memory overhead.
- **Learning AI**: When you want to see exactly how a ReAct loop works under the hood.

AI Agent4J isn't trying to be the "kitchen sink" of AI. It's trying to be the **industrial-grade engine** that powers your specific AI vision.
