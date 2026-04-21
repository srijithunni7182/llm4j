# Examples

This directory contains showcase applications and examples demonstrating the capabilities of the ai-agent4j framework.

## Applications

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

1. Create a new directory under `examples/`
2. Include a comprehensive README.md
3. Ensure the example is well-documented and runnable
4. Add any necessary dependencies to the example's pom.xml
5. Follow the coding standards in [CONTRIBUTING.md](../CONTRIBUTING.md)

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
