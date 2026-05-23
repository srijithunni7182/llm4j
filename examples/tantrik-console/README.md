# Tantrik IDE Console

A full IDE-like web application for authoring, managing, and executing [Loom](../../ai-agent4j/wiki/ReAct-Agent-Guide.md) workflow scripts against the Tantrik orchestration engine.

![Tantrik IDE Console](../../ai-agent4j/images/hero_xai.png)

---

## Overview

The Tantrik IDE Console gives you a professional dark-themed development environment with:

- **Monaco Editor** — syntax-highlighted Loom DSL editing with save shortcuts
- **Live Execution Trace** — real-time SSE stream of agent activity as workflows run
- **Token Dashboard** — per-agent token consumption charts with compression indicators
- **Engram Graph** — interactive force-directed visualisation of the agent knowledge graph
- **Chat / Generate** — natural-language prompt → Loom script generation

---

## Quick Start

### Prerequisites

| Tool | Minimum Version |
|------|----------------|
| Java | 17 |
| Maven | 3.8 |
| Node.js | 18 |
| npm | 9 |

### Option 1 — One-command launch (recommended)

```bash
# From the tantrik-console directory
chmod +x start.sh

# Start with the pre-built UI served by Spring Boot
./start.sh

# Open http://localhost:8090 in your browser
```

### Option 2 — Hot-reload dev mode

```bash
# Start backend + Vite dev server simultaneously
./start.sh --dev

# Backend API:  http://localhost:8090
# Frontend:     http://localhost:5173  (hot-reload)
```

### Option 3 — Rebuild UI then start

```bash
# Rebuild the React bundle into the server's static directory, then start
./start.sh --build-ui
```

### Option 4 — Manual start

```bash
# Terminal 1 — Backend
cd tantrik-console-server
mvn spring-boot:run

# Terminal 2 — Frontend (dev mode)
cd tantrik-console-ui
npm install
npm run dev
```

---

## Project Structure

```
tantrik-console/
├── start.sh                    # Launcher script
├── loom-scripts/               # Pre-canned example workflows
│   └── examples/
│       ├── mock-demo.loom          # No API keys needed
│       ├── research-summarizer.loom
│       ├── code-review.loom
│       ├── content-pipeline.loom
│       └── data-analysis.loom
├── tantrik-console-server/     # Spring Boot backend
│   └── README.md
└── tantrik-console-ui/         # React 18 + TypeScript frontend
    └── README.md
```

---

## Sample Loom Scripts

The `loom-scripts/examples/` directory contains ready-to-run workflows. They are automatically copied into the server's working directory on first launch.

| Script | Description | Mock-safe |
|--------|-------------|-----------|
| `mock-demo.loom` | Simple planner + executor demo | ✅ Yes |
| `research-summarizer.loom` | Research a topic and summarise findings | ✅ Yes |
| `code-review.loom` | Parallel correctness / security / performance review | ✅ Yes |
| `content-pipeline.loom` | Ideate → write → edit → SEO optimise | ✅ Yes |
| `data-analysis.loom` | Analyse data → find patterns → write report | ✅ Yes |

All scripts work with **Mock Mode** enabled (no LLM API keys required). To use real LLMs, configure your provider in `application.properties` and uncheck Mock Mode in the IDE.

---

## Configuration

Edit `tantrik-console-server/src/main/resources/application.properties`:

```properties
# Server port
server.port=8090

# CORS — add your frontend origin if deploying separately
tantrik.console.cors.origins=http://localhost:5173,http://localhost:3000

# Directory scanned for .loom files (relative to server working directory)
tantrik.console.loom-scripts.dir=./loom-scripts

# Engram memory persistence (leave blank for in-memory only)
tantrik.console.engram.storage-path=./engram-memory.json

# LLM model for script generation (used by Chat panel)
tantrik.console.llm.model=gpt-4o-mini
```

---

## Launcher Script Reference

```
Usage: ./start.sh [--dev] [--build-ui] [--help]

  (no flags)    Start the Spring Boot server only.
                Serves the pre-built React UI at http://localhost:8090

  --dev         Start the Spring Boot server AND the Vite dev server.
                Backend: http://localhost:8090
                Frontend (hot-reload): http://localhost:5173

  --build-ui    Rebuild the React UI into the server's static directory,
                then start the Spring Boot server.

  --help        Show this help message.
```

Press **Ctrl+C** to stop all services cleanly.
