# Gemini ReAct Java - RAG Addons

This library provides powerful add-on capabilities for [gemini-react-java](../gemini-react-java), focusing on **Local RAG (Retrieval Augmented Generation)** and **Persistent Vector Stores**.

It is separated from the core library to keep the main dependency lightweight.

## Features

### 1. Local Embeddings (No API Costs)

Generate text embeddings locally on your JVM without calling external APIs (like OpenAI or Google).

- **ONNX Runtime**: High-performance inference (Recommended).
- **Deep Java Library (DJL)**: Engine-agnostic support (PyTorch, TensorFlow, etc.).

### 2. Persistent Vector Stores

Store your embeddings permanently for production use cases.

- **PostgreSQL (pgvector)**: Use your existing SQL database for vector search.
- **Pinecone**: Managed cloud vector database.

## Installation

Add this dependency alongside the core library:

```xml
<dependency>
    <groupId>io.github.llm4j</groupId>
    <artifactId>gemini-react-java-rag-addons</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Usage

### Local Embeddings (ONNX)

```java
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;

// Initialize with model and tokenizer
var provider = new OnnxEmbeddingProvider(
    "/path/to/all-MiniLM-L6-v2.onnx",
    "/path/to/tokenizer.json"
);

float[] vector = provider.embed("Hello world");
```

### PostgreSQL Vector Store

```java
import io.github.llm4j.agent.rag.store.PGVectorStore;

// Requires a Postgres DB with 'vector' extension
var store = new PGVectorStore(
    "jdbc:postgresql://localhost:5432/mydb",
    "user",
    "password",
    "embeddings_table",
    384 // Dimension
);

store.add("doc1", vector, Map.of("category", "news"));
```

### Pinecone Vector Store

```java
import io.github.llm4j.agent.rag.store.PineconeVectorStore;

var store = new PineconeVectorStore(
    "YOUR_API_KEY",
    "your-index-name"
);
```

## Requirements

- **Java 17+**
- **Maven 3.8+**
- For Postgres: PostgreSQL instance with `pgvector` extension installed.
