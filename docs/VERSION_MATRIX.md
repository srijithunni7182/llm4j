# Version Matrix

This repository uses the following canonical Maven coordinates for broad adoption:

- Group ID: `io.github.srijithunni7182`
- Current aligned version: `5.0`

## Core Libraries

| Module | Maven Coordinate |
|---|---|
| ai-agent4j | `io.github.srijithunni7182:ai-agent4j:5.0` |
| ai-agent4j-addons | `io.github.srijithunni7182:ai-agent4j-addons:5.0` |
| ai-agent4j-tools | `io.github.srijithunni7182:ai-agent4j-tools:5.0` |
| ai-agent4j-loom | `io.github.srijithunni7182:ai-agent4j-loom:5.0` |
| eval4j | `io.github.srijithunni7182:eval4j:5.0` |
| eval4j-report | `io.github.srijithunni7182:eval4j-report:5.0` (reads eval4j run bundles; depends on eval4j only for the optional test-JVM listener) |

## Applications (internal modules in this repository)

These apps now depend on the core libraries above at `5.0`:

- `hexamind-hub`
- `nirmaan-yantra/nirmaan-yantra-server`
- `kingini`
- `gmail-mcp-app`

## Java Baseline

- Java baseline is standardized to **Java 17+** across modules and docs.
