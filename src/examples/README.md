# Examples

This directory contains showcase applications and examples demonstrating the capabilities of the ai-agent4j framework.

## Applications

### [getviral](./getviral/) ⚡ — the full-stack showcase
One idea in, a ready-to-post pack for X, Instagram Reels and YouTube out. Twelve agents orchestrated by
Loom, prompts written live by an orchestrator agent, web research with cited sources, tools on free public
REST APIs, local RAG (addons), creator memory (Engram), an eval4j quality gate, generated images and a
rendered Reel, a Showrunner that signs off every artifact, and approval-gated Instagram publishing. A multi-user website that deploys to Cloud Run and
runs locally with `./launch.sh`, with no API key needed.

### [gmail-mcp-app](./gmail-mcp-app/)
Gmail integration using Model Context Protocol (MCP).

### [hexamind-hub](./hexamind-hub/)
Multi-agent collaboration hub demonstrating advanced agent orchestration patterns.

### [kingini](./kingini/)
Example application showcasing specific ai-agent4j features.

### [nirmaan-yantra](./nirmaan-yantra/)
Construction/building-themed example application.

## Running Examples

Each example application has its own README with specific setup and run instructions. Generally:

```bash
cd <example-name>
mvn clean install
mvn exec:java -Dexec.mainClass=<MainClass>
```

## Contributing Examples

We welcome new examples! When contributing:

1. Create a new directory under `src/examples/`
2. Include a comprehensive README.md
3. Ensure the example is well-documented and runnable
4. Add any necessary dependencies to the example's pom.xml
5. Follow the coding standards in [CONTRIBUTING.md](../../CONTRIBUTING.md)

## Example Structure

Each example should follow this structure:
```
example-name/
├── src/
│   ├── main/java/
│   └── test/java/
├── pom.xml
├── README.md
└── .gitignore
```
