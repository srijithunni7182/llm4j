# Version Matrix

This repository uses the following canonical Maven coordinates for broad adoption:

- Group ID: `io.github.srijithunni7182`
- Current aligned version: `6.2.0` (what changed: [RELEASE_NOTES_6_2_0.md](RELEASE_NOTES_6_2_0.md))

## Core Libraries

| Module | Maven Coordinate |
|---|---|
| ai-agent4j | `io.github.srijithunni7182:ai-agent4j:6.2.0` |
| ai-agent4j-addons | `io.github.srijithunni7182:ai-agent4j-addons:6.2.0` |
| ai-agent4j-tools | `io.github.srijithunni7182:ai-agent4j-tools:6.2.0` |
| ai-agent4j-loom | `io.github.srijithunni7182:ai-agent4j-loom:6.2.0` |
| loom-inspect-core, loom-inspect, loom-fabric | `io.github.srijithunni7182:loom-inspect-core:6.2.0` (also `loom-inspect`, `loom-fabric`): the live run page and the Fabric server |
| llm4j-spring-boot-starter | `io.github.srijithunni7182:llm4j-spring-boot-starter:6.2.0` (Spring Boot 3.2 and later; Loom, tools, addons and spring-jdbc are optional) |
| eval4j | `io.github.srijithunni7182:eval4j:6.2.0` |
| eval4j-report | `io.github.srijithunni7182:eval4j-report:6.2.0` (reads eval4j run bundles; depends on eval4j only for the optional test-JVM listener) |

## Applications (internal modules in this repository)

These apps now depend on the core libraries above at `6.2.0`:

- `hexamind-hub`
- `nirmaan-yantra/nirmaan-yantra-server`
- `kingini`
- `gmail-mcp-app`

## Java Baseline

- Java baseline is standardized to **Java 17+** across modules and docs.
