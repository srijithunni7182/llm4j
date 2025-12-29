# RAG Support

RAG (Retrieval-Augmented Generation) enhances your agents with document-based context retrieval using vector similarity search.

## Overview

RAG allows agents to:

- Retrieve relevant context from a document collection
- Answer questions based on specific documents
- Provide citations and sources
- Handle large knowledge bases efficiently

## Architecture

```
Document → Chunking → Embedding → Vector Store
                                        ↓
Question → Embedding → Similarity Search → Top-K Chunks
                                                ↓
                                    Augmented Prompt → Agent → Answer
```

## Quick Start

### 1. Create Embedding Provider

```java
import io.github.llm4j.agent.rag.embedding.*;
import io.github.llm4j.config.LLMConfig;

LLMConfig config = LLMConfig.builder()
    .apiKey(System.getenv("GOOGLE_API_KEY"))
    .defaultModel("gemini-1.5-flash")
    .build();

EmbeddingProvider embeddingProvider = new GeminiEmbeddingProvider(config);
// Uses Gemini text-embedding-004 model (768 dimensions)
```

### 2. Create Vector Store

```java
import io.github.llm4j.agent.rag.store.*;

VectorStore vectorStore = new InMemoryVectorStore();
// Suitable for small to medium datasets (up to ~10K documents)
```

### 3. Process Documents

```java
import io.github.llm4j.agent.rag.document.*;

// Create document
Document doc = Document.builder()
    .id("user-manual-v1")
    .content("Your document content here...")
    .addMetadata("source", "user_manual.pdf")
    .addMetadata("version", "1.0")
    .build();

// Chunk the document
FixedSizeChunkingStrategy chunker = new FixedSizeChunkingStrategy(
    500,  // chunk size in characters
    50    // overlap between chunks
);
List<DocumentChunk> chunks = chunker.chunk(doc);

// Rebuild document with chunks
doc = Document.builder()
    .id(doc.getId())
    .content(doc.getContent())
    .metadata(doc.getMetadata())
    .chunks(chunks)
    .build();
```

### 4. Create RAG Agent

```java
import io.github.llm4j.agent.rag.*;

// Create base agent
ReActAgent baseAgent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new CalculatorTool())
    .build();

// Create RAG agent
RAGAgent ragAgent = RAGAgent.builder()
    .agent(baseAgent)
    .vectorStore(vectorStore)
    .embeddingProvider(embeddingProvider)
    .topK(3)  // Retrieve top 3 most relevant chunks
    .includeMetadata(true)  // Include source info in context
    .build();
```

### 5. Add Documents and Query

```java
// Add documents
ragAgent.addDocument(doc);

// Query with context
AgentResult result = ragAgent.run("What does the manual say about installation?");
System.out.println(result.getFinalAnswer());
```

## Document Chunking Strategies

### Fixed-Size Chunking

Best for general-purpose use:

```java
// Basic chunking (no overlap)
FixedSizeChunkingStrategy chunker = new FixedSizeChunkingStrategy(500);

// With overlap (recommended)
FixedSizeChunkingStrategy chunker = new FixedSizeChunkingStrategy(
    500,  // chunk size
    50    // overlap - helps preserve context at boundaries
);
```

**Recommended Settings:**

- **Chunk Size**: 300-500 characters for general text
- **Overlap**: 10-20% of chunk size
- **Larger chunks**: Better for context, but less precise retrieval
- **Smaller chunks**: More precise, but may lose context

## Vector Store Options

### In-Memory Vector Store

Suitable for development and small datasets:

```java
VectorStore vectorStore = new InMemoryVectorStore();
// - Fast for small datasets (<10K documents)
// - Uses cosine similarity
// - Supports metadata filtering
// - Data lost on restart
```

### Production Options

For larger datasets, consider:

- **PostgreSQL with pgvector**: Persistent storage, good for medium datasets
- **Dedicated vector DBs**: Pinecone, Weaviate, Milvus for large-scale production

## Metadata Filtering

Filter search results by metadata:

```java
// Add documents with metadata
Document doc1 = Document.builder()
    .id("doc1")
    .content("...")
    .addMetadata("category", "technical")
    .addMetadata("year", 2024)
    .build();

// Search with filters (when using VectorStore directly)
Map<String, Object> filters = Map.of("category", "technical");
List<SearchResult> results = vectorStore.search(queryEmbedding, 5, filters);
```

## Advanced Usage

### Custom Context Formatting

The RAG agent automatically formats retrieved context:

```
Use the following context to answer the question. If the context doesn't contain relevant information, say so.

Context:
---
[1] First relevant chunk...
    (Source: doc1, Relevance: 0.95)

[2] Second relevant chunk...
    (Source: doc2, Relevance: 0.87)
---

Question: Your question here
```

### Combining RAG with Personas

```java
RAGAgent ragAgent = RAGAgent.builder()
    .agent(ReActAgent.builder()
        .llmClient(client)
        .persona(PersonaLibrary.researchScientist())  // Evidence-based persona
        .build())
    .vectorStore(vectorStore)
    .embeddingProvider(embeddingProvider)
    .topK(5)
    .build();
```

## Best Practices

1. **Chunk Size**: Start with 500 characters, adjust based on your content
2. **Overlap**: Use 10-20% overlap to preserve context
3. **Metadata**: Add rich metadata for better filtering and citations
4. **Top-K**: Start with 3-5 chunks, increase if needed
5. **Document Quality**: Clean and structure documents before adding
6. **Testing**: Test retrieval quality with sample questions

## Performance Considerations

- **Embedding Generation**: Batch process documents when possible
- **Vector Store Size**: In-memory store suitable for <10K documents
- **Search Speed**: Linear scan in in-memory store, consider indexing for larger datasets
- **Memory Usage**: ~3KB per 768-dim embedding

## See Also

- [Creating Custom Tools](Creating-Custom-Tools.md) - Building custom tools
- [Agent Personas](Agent-Personas.md) - Configuring agent behavior
- [Knowledge Graphs](Knowledge-Graphs.md) - Structured knowledge representation
