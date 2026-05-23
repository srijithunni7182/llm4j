# Tantrik Console Server

Spring Boot 3.2 / Java 17 backend for the Tantrik IDE Console. Exposes a REST + SSE API consumed by the React frontend.

---

## Running

```bash
# Development (auto-restart on code changes)
mvn spring-boot:run

# Production JAR
mvn package -DskipTests
java -jar target/tantrik-console-server-0.0.1-SNAPSHOT.jar
```

Server starts on **http://localhost:8090** by default.

---

## Configuration

All settings live in `src/main/resources/application.properties`.

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8090` | HTTP port |
| `tantrik.console.cors.origins` | `http://localhost:5173,http://localhost:3000` | Comma-separated allowed CORS origins |
| `tantrik.console.loom-scripts.dir` | `./loom-scripts` | Root directory scanned for `.loom` files |
| `tantrik.console.engram.storage-path` | `./engram-memory.json` | Engram persistence file (blank = in-memory only) |
| `tantrik.console.llm.model` | `gpt-4o-mini` | LLM model used by the Chat/Generate panel |
| `info.app.version` | `@project.version@` | Version injected from Maven at build time |

---

## API Reference

### Health

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Returns `{"status":"UP","version":"..."}` |

### Workflow Runs

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs` | Start a new workflow run |
| `GET` | `/api/runs` | List all buffered runs (up to 50) |
| `GET` | `/api/runs/{runId}` | Get a single run summary |
| `DELETE` | `/api/runs/{runId}` | Cancel a running workflow |
| `GET` | `/api/runs/{runId}/stream` | SSE event stream for a run |
| `GET` | `/api/runs/{runId}/tokens` | Token consumption breakdown |

#### POST /api/runs — Request body

```json
{
  "script": "agent Foo { ... } workflow Bar { ... }",
  "workflowName": "Bar",
  "mockMode": true,
  "initialContext": {}
}
```

Set `mockMode: true` to run without any LLM API keys — the executor returns deterministic mock responses.

#### SSE Event Types

Events are sent as `run-event` named SSE events. Each event is a JSON object:

```json
{
  "type": "TRACE_PRE_TURN",
  "message": "Agent Foo phase PRE_TURN",
  "executionTier": "LOCAL",
  "timestamp": "2025-05-02T10:30:00Z",
  "metadata": {
    "inputTokensEstimate": 1024,
    "squeezed": false,
    "compressionRatio": 0.0,
    "status": "OK",
    "error": ""
  }
}
```

| Event Type | Meaning |
|------------|---------|
| `RUN_ACCEPTED` | Run queued |
| `RUN_STARTED` | Execution began |
| `TRACE_PRE_TURN` | Agent about to process a turn |
| `TRACE_POST_TURN` | Agent finished a turn |
| `RUN_COMPLETED` | Workflow finished successfully |
| `RUN_FAILED` | Workflow failed with an error |
| `RUN_CANCELLED` | Run was cancelled via DELETE |

### File Management

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/files` | List all `.loom` files in the configured directory |
| `GET` | `/api/files/{encodedPath}` | Read file content (plain text) |
| `PUT` | `/api/files/{encodedPath}` | Write file content (plain text body) |

`encodedPath` is the URL-encoded relative path, e.g. `examples%2Fmain.loom`.

### Engram Knowledge Graph

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/engram/nodes` | List all nodes (content truncated to 200 chars) |
| `GET` | `/api/engram/nodes/{id}` | Get full node content |
| `DELETE` | `/api/engram/nodes/{id}` | Remove a node |

### Loom Script Generation

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/generate/loom` | Generate a Loom script from a natural-language prompt |

#### POST /api/generate/loom — Request / Response

```json
// Request
{ "prompt": "A pipeline that summarises documents", "mockMode": true }

// Response
{ "script": "agent ...\nworkflow ...", "workflowName": "DocumentSummarizer" }
```

---

## Error Responses

All 4xx/5xx responses include an `error` field:

```json
{ "error": "File not found: examples/missing.loom" }
```

| Status | Cause |
|--------|-------|
| 400 | Invalid request body, path traversal attempt |
| 404 | Run, file, or Engram node not found |
| 500 | Unhandled server error (logged at ERROR level) |

---

## Architecture

```
src/main/java/io/github/llm4j/tantrik/console/
├── config/
│   ├── CorsConfig.java              # Configurable CORS origins
│   ├── EngramConfig.java            # EngramEngine Spring bean
│   └── GlobalExceptionHandler.java  # Centralised error handling
├── controller/
│   ├── TantrikConsoleController.java # /api/runs
│   ├── FileController.java           # /api/files
│   ├── EngramController.java         # /api/engram/nodes
│   ├── TokenController.java          # /api/runs/{id}/tokens
│   ├── LoomGenerateController.java   # /api/generate/loom
│   ├── HealthController.java         # /api/health
│   └── SpaController.java            # SPA fallback → index.html
├── service/
│   ├── TantrikRunService.java        # Run lifecycle, ring buffer, cancellation
│   ├── FileService.java              # Safe file I/O
│   ├── EngramService.java            # Engram node management
│   ├── TokenAggregationService.java  # Token stats from trace events
│   └── LoomGenerateService.java      # Mock + LLM script generation
└── model/
    ├── RunSummary.java / RunEvent.java / RunStatus.java / RunRequest.java
    ├── FileDescriptor.java
    ├── EngramNodeDescriptor.java
    ├── TokenBreakdown.java / AgentTokenStat.java
    ├── GenerateLoomRequest.java / GenerateLoomResponse.java
    └── ErrorResponse.java
```

### Key Design Decisions

- **Ring buffer** — `TantrikRunService` keeps the last 50 `RunSummary` records in a `LinkedHashMap` with access-order eviction. No database needed.
- **Cancellation** — `DELETE /api/runs/{id}` sets an `AtomicBoolean` flag checked by the trace monitor thread (≤ 200 ms latency).
- **Engram secondary index** — `EngramService` maintains a `ConcurrentHashMap<String, MemoryObject>` because the underlying `VectorStore` only exposes `removeByContent`. The index enables O(1) get/delete by ID.
- **Token aggregation on demand** — `GET /api/runs/{id}/tokens` scans `RunSummary.events` at request time. No separate storage.
- **SPA routing** — `SpaController` forwards all non-API, non-asset GET requests to `index.html` using a no-dot regex pattern.

---

## Running Tests

```bash
mvn test
```

Test classes:

| Class | Coverage |
|-------|----------|
| `FileServiceTest` | Path traversal rejection, recursive `.loom` discovery |
| `EngramServiceTest` | Content truncation, delete-by-id, shadow filtering |
| `TokenAggregationServiceTest` | Per-agent grouping, duration calc, squeeze counting |
| `LoomGenerateServiceTest` | Mock mode determinism, workflow name parsing |
| `TantrikRunServiceTest` | Ring buffer eviction, cancellation, status transitions |

---

## Building a Production JAR

```bash
# Build UI first (outputs to src/main/resources/static)
cd ../tantrik-console-ui && npm run build && cd ../tantrik-console-server

# Package everything into a single fat JAR
mvn package -DskipTests

# Run
java -jar target/tantrik-console-server-0.0.1-SNAPSHOT.jar
```

The fat JAR includes the compiled React bundle and serves it at `/`.
