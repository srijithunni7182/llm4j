# Version Matrix

This repository uses the following canonical Maven coordinates for broad adoption:

- Group ID: `io.github.srijithunni7182`
- Current aligned version: `5.0`

## Core Libraries

| Module | Maven Coordinate |
|---|---|
| ai-agent4j | `io.github.srijithunni7182:ai-agent4j:5.0` |
| ai-agent4j-addons | `io.github.srijithunni7182:ai-agent4j-addons:5.0` |
| ai-agent4j-loom | `io.github.srijithunni7182:ai-agent4j-loom:5.0` |

## Applications (internal modules in this repository)

These apps now depend on the core libraries above at `5.0`:

- `hexamind-hub`
- `nirmaan-yantra/nirmaan-yantra-server`
- `kingini`
- `gmail-mcp-app`

## Java Baseline

- Java baseline is standardized to **Java 17+** across modules and docs.
