# Shared Brain Architecture

The **Shared Brain** is the core cognitive engine of Hexamind Hub. It allows individual agents to trascend their isolated context windows and operate as a cohesive "Hive Mind".

## 🧠 Components

The Shared Brain consists of three integrated layers:

### 1. Vector Memory (RAG)

* **What it is**: A semantic storage system for unstructured text (thoughts, arguments, critiques).
* **How it works**:
  * Every time an agent generates a thought, it is embedded using Google's `text-embedding-004` model.
  * The embedding vector (1536 dimensions) is stored in an in-memory `VectorStore`.
  * **Retrieval**: Agents can query this store to find *conceptually similar* past thoughts, even if the keywords don't match exactly.
* **Benefit**: Allows agents to recall context from 100 rounds ago or from other parallel sessions.

### 2. Knowledge Graph (KG)

* **What it is**: A structured network of entities and relationships (Triples).
* **Structure**: `Subject` -> `Predicate` -> `Object` (e.g., "Solar Energy" -> "reduces" -> "Carbon Footprint").
* **Extraction**:
  * The `MultiAgentOrchestrator` uses a specialized LLM call to analyze "ANALYSIS" and "ARGUMENT" thoughts.
  * It extracts factual claims and adds them to the graph.
* **Benefit**: Enables structured reasoning. Agents can traverse the graph to find indirect connections that vector search might miss.

### 3. Persistence Layer

* **What it is**: An embedded H2 Database (`/tmp/hexamind_db`).
* **Function**:
  * Periodically archives the in-memory/in-context knowledge to disk.
  * Allows the system to "wake up" effectively with its memories intact after a restart.
* **Schema**:
  * `PUBLIC.KNOWLEDGE_TRIPLE`: Stores graph edges.
  * `PUBLIC.VECTOR_ENTRY`: Stores embeddings and metadata.

## 🔄 The Feedback Loop

1. **Agent A** generates a thought.
2. **Orchestrator** indexes it into Vector Store AND extracts Triples into Knowledge Graph.
3. **Agent B** (in the next round) queries the Shared Brain.
4. **Agent B** receives context from Agent A's thought + related knowledge nodes.
5. **Agent B** generates a more informed response.

This loop drives the collective intelligence of the platform.
