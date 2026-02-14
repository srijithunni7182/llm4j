# AI Agent4J - RAG Addons

This library provides powerful add-on capabilities for [AI Agent4J](../ai-agent4j), focusing on **Local RAG (Retrieval Augmented Generation)** and **Persistent Vector Stores**.

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
    <artifactId>ai-agent4j-addons</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Prerequisites & Setup

### 1. Local Embeddings (ONNX)

To run embeddings locally, you need the model file (`.onnx` or `.ort`) and its tokenizer configuration.

**Step 1: Download Models**
We provide a script in the core repository to download tested models (e.g., `all-MiniLM-L6-v2`):

```bash
# From the project root
bash ai-agent4j/scripts/setup_test_models.sh
```

**Step 2: Verify Files**
Ensure you have the following directory structure:

```text
models/
  └── onnx/
      ├── model.onnx (The model weights)
      └── tokenizer.json (The tokenizer configuration)
```

### 2. Deep Java Library (DJL)

DJL is engine-agnostic and allows you to use models from PyTorch, TensorFlow, etc. without conversion.

**Step 1: Get a Model**
You can point DJL to a directory containing your model files (e.g., specific PyTorch `.pt` files).
The same `scripts/setup_test_models.sh` downloads compatible ONNX models, but for DJL you might often use it to load models that ONNX doesn't support or if you prefer a different engine.

### 3. PostgreSQL (pgvector)

You need a PostgreSQL database with the `vector` extension installed.

**Option A: Using Docker (Recommended)**
Run the official image which comes pre-configured:

```bash
docker run -d --name vectordb -p 5432:5432 -e POSTGRES_PASSWORD=secret pgvector/pgvector:pg16
```

**Option B: Manual Installation**
If using an existing Postgres instance:

1. Install the extension (varies by OS).
2. Enable it in your database:

    ```sql
    CREATE EXTENSION vector;
    ```

### 3. Pinecone (Cloud)

1. Sign up at [app.pinecone.io](https://app.pinecone.io).
2. Create an Index:
    - **Dimensions**: Must match your embedding model (e.g., 384 for MiniLM, 768 for Gemini).
    - **Metric**: Cosine.
3. Get your API Key.

---

## Usage Guide

### Using Local Embeddings

```java
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;

// 1. Initialize provider with paths to your downloaded model
var provider = new OnnxEmbeddingProvider(
    "models/onnx/model.onnx",
    "models/onnx/tokenizer.json"
);

// 2. Generate embedding
float[] vector = provider.embed("The quick brown fox jumps over the lazy dog");
System.out.println("Vector dimension: " + vector.length);
```

### Using Local Embeddings (DJL)

DJL is great if you want flexibility with underlying engines (PyTorch, TensorFlow, etc.).

```java
import io.github.llm4j.agent.rag.embedding.DjlEmbeddingProvider;

// 1. Initialize with model path (creates a default BERT translator)
var provider = new DjlEmbeddingProvider(
    "file:///path/to/your/model_directory/"
);

// 2. Generate embedding
float[] vector = provider.embed("The quick brown fox jumps over the lazy dog");
```

### Using PostgreSQL Store

```java
import io.github.llm4j.agent.rag.store.PGVectorStore;

// 1. Connect to DB (Table will be created automatically if missing)
var store = new PGVectorStore(
    "jdbc:postgresql://localhost:5432/postgres",
    "postgres",
    "secret",
    "document_embeddings", // Table name
    384                    // Vector dimension (must match your model)
);

// 2. Add Data
store.add("doc_1", vector, Map.of("source", "wiki", "page", 12));

// 3. Search
var results = store.search(queryVector, 5);
```

### Using Pinecone Store

```java
import io.github.llm4j.agent.rag.store.PineconeVectorStore;

var store = new PineconeVectorStore(
    "YOUR_PINECONE_API_KEY",
    "my-index"
);

// Operations are the same (add, search, delete)
store.add("doc_1", vector, Map.of("category", "test"));
```
