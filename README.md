# llm4j: The Pure Java AI Stack

> **Build intelligent, reasoning applications from the ground up.**

**llm4j** is a monorepo dedicated to exploring the future of AI engineering in Java. Unlike Python-heavy ecosystems or heavy abstractions, this project proves that you can build sophisticated, production-ready AI solutions using pure, idiomatic Java.

It provides a complete stack: from a low-level Gemini client to a high-level ReAct agent framework, and fully fledged multi-agent applications.

---

## 🏗️ The Core: [Gemini ReAct Java](gemini-react-java/)

The heart of this repository is **gemini-react-java**, a lightweight yet powerful library designed for the Google Gemini API.

* **Zero Magic**: No confusing "magic" abstractions. Just clean, typed Java code.
* **ReAct Agents**: Implements the **Re**asoning + **Act**ing paradigm, allowing agents to solve complex problems by thinking and using tools.
* **Tooling**: Includes ready-to-use tools (Calculator, Web Search) and an **OpenAPI Tool** that can turn any REST API into an AI function instantly.
* **Structured Output**: Native support for JSON modes and structured object mapping.

### 🧩 The Extensions: [RAG Addons](gemini-react-java-rag-addons/)

For advanced use-cases, the **RAG Addons** module brings heavy-lifting capabilities while keeping the core light:

* **Local Embeddings**: Run **ONNX** and **DJL** models locally (no API costs).
* **Persistent Storage**: Store vectors in **PostgreSQL (pgvector)** or **Pinecone**.

👉 **[Read the Documentation](gemini-react-java/README.md)**

---

## 🚀 The Showcase: [Hexamind Hub](hexamind-hub/)

**Hexamind Hub** demonstrates what `gemini-react-java` can do. It is a "Digital Boardroom" where 6 specialized AI agents (including a Cynical Skeptic and a Creative Thinker) collaborate to solve your problems.

* **Multi-Agent Orchestration**: See how different personas debate, critique, and build consensus.
* **Real-Time**: Built with Spring Boot and WebSockets for a live, streaming experience.
* **Visual**: A stunning, modern UI to watch the AI thought process unfold.

👉 **[Launch Hexamind Hub](hexamind-hub/README.md)**

---

## 🧪 Incubator: Aviation Chatbot

**Status: 🚧 Under Development**

An experimental project pushing the boundaries of the **OpenAPI Tool**. This chatbot autonomously navigates the AviationStack API to answer real-time questions about flights, delays, and airports, demonstrating how LLMs can master dynamic external data sources.

---

## 💡 Our Philosophy

1. **Java First**: AI isn't just for Python. Java's strong typing, concurrency, and ecosystem make it perfect for building robust AI systems.
2. **Ground Up**: We minimize dependencies. By building our own ReAct loop and provider clients, we gain full control and understanding of the LLM's behavior.
3. **Transparency**: We believe in "glass-box" AI. You should be able to see exactly what your agent is thinking and why it made a decision.

---

MIT License
