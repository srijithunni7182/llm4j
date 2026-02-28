# Semantic Memory

`ai-agent4j` provides a built-in **Semantic Memory** layer, giving your agents the ability to remember facts about users across conversations using dense vector embeddings and a pluggable vector store.

## How It Works

1. The agent uses a `MemoryManagementTool` to write factual observations into a `SemanticMemoryService`.
2. On every new `run()` call, the agent automatically embeds the user's question and retrieves the most semantically similar stored facts.
3. These facts are prepended to the agent's context window as personal knowledge.

## Quick Start

### In-Memory (Zero Dependencies, Development Only)

```java
SemanticMemoryConfig config = SemanticMemoryConfig.inMemory("user-123");

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .semanticMemoryConfig(config) // auto-wires MemoryManagementTool too
    .build();
```

> **Note**: In-memory mode uses zero-vector embeddings — similarity search is non-semantic. Use for functional testing only.

---

### Cloud Embeddings (Gemini) + In-Memory Store

Best for prototyping with real semantic accuracy but no database setup.

```java
SemanticMemoryConfig config = SemanticMemoryConfig.builder()
    .userId("user-123")
    .geminiApiKey(System.getenv("GOOGLE_API_KEY"))
    // optional: .geminiEmbeddingModel("text-embedding-004")
    .build();

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .semanticMemoryConfig(config)
    .build();
```

---

### ONNX Local Embeddings + pgvector (Production)

Full offline capability. Requires the `ai-agent4j-addons` dependency.

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-addons</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
SemanticMemoryConfig config = SemanticMemoryConfig.builder()
    .userId("user-123")
    .onnxModelPath("/path/to/all-MiniLM-L6-v2.onnx")
    .onnxTokenizerPath("/path/to/tokenizer.json")
    .pgUrl("jdbc:postgresql://localhost:5432/agentdb")
    .pgUser("postgres")
    .pgPassword("secret")
    .pgTable("agent_memories")   // optional, default: "agent_memories"
    .pgDimension(384)            // must match model output
    .topK(5)                     // how many facts to recall per query
    .similarityThreshold(0.7f)   // minimum cosine similarity (0.0 to 1.0)
    .build();

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .semanticMemoryConfig(config)
    .build();
```

## pgvector Setup

```sql
-- Enable the vector extension (once per DB)
CREATE EXTENSION IF NOT EXISTS vector;

-- The table is created automatically on first use, but you can pre-create it
CREATE TABLE IF NOT EXISTS agent_memories (
    id TEXT PRIMARY KEY,
    embedding vector(384),
    metadata JSONB
);

-- Optional: HNSW index for high-speed retrieval
CREATE INDEX IF NOT EXISTS agent_memories_idx
ON agent_memories USING hnsw (embedding vector_cosine_ops);
```

## Recommended ONNX Model

[all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) exports cleanly to ONNX and produces 384-dimensional embeddings — a good balance of speed and quality.

```bash
pip install sentence-transformers
python -c "
from sentence_transformers import SentenceTransformer
model = SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')
model.save('all-MiniLM-L6-v2')
# Then export to ONNX using optimum-cli or torch.onnx.export
"
```

## Manual Usage (Without Config)

If you need finer control, you can wire components manually:

```java
// Wire up components manually
OnnxEmbeddingProvider embedder = new OnnxEmbeddingProvider("model.onnx", "tokenizer.json");
InMemoryVectorStore store = new InMemoryVectorStore();   // or new PGVectorStore(...)
SemanticMemoryService memory = new SemanticMemoryService(embedder, store, "user-123");

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .semanticMemory(memory)                      // sets the recall service
    .addTool(new MemoryManagementTool(memory))   // enables the agent to save facts
    .build();
```

## How Facts Get Saved

The `MemoryManagementTool` is automatically registered when you call `.semanticMemoryConfig()`.
The LLM will invoke it with a `fact` argument whenever it detects durable information:

```
User: "By the way, I'm vegetarian."
Agent Thought: I should save this preference for future reference.
Agent Action: save_memory_fact
Agent Input: {"fact": "The user is vegetarian."}
Agent Observation: Successfully saved fact into long-term memory: The user is vegetarian.
```

On the next conversation (even a different JVM session if using pgvector), when the user asks:
> *"Can you suggest a restaurant near me?"*

The agent will automatically retrieve and prepend: `- The user is vegetarian.` to its context.
