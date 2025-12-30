# Neural Metrics Visualization

Hexamind Hub provides a real-time window into the cognitive processes of the AI swarm through the **Neural Metrics** dashboard.

## 📊 The Dashboard

Located in the sidebar, this dashboard tracks three key indicators of system intelligence:

### 1. Knowledge Nodes

* **Definition**: The total number of unique entities and relationships (triples) in the Knowledge Graph.
* **Significance**: Represents the *breadth* of the system's structured fact base. A rapidly growing node count indicates the agents are efficiently mining facts from their analysis.

### 2. Memory Vectors

* **Definition**: The number of embedded text chunks validation stored in the Vector Database.
* **Significance**: Represents the *depth* of the system's conversation history. More vectors mean more context is available for retrieval-augmented generation (RAG).

### 3. Cognitive Steps (LLM Calls)

* **Definition**: The cumulative number of distinct LLM inference calls made by the orchestrator and agents.
* **Includes**:
  * Agent Analysis
  * Arguments & Critiques
  * Knowledge Extraction calls
  * Consensus Synthesis
* **Significance**: A measure of the "computational effort" or "thinking" expended on the problem. High cognitive steps usually correlate with more thorough exploration.

## 🔌 API Endpoint

These metrics are exposed via the REST API for external monitoring:

`GET /api/sessions/{sessionId}/stats`

**Response:**

```json
{
  "knowledgeStats": {
    "vectorCount": 42,
    "tripleCount": 15
  },
  "stats": {
    "llm_calls": 37
  }
}
```
