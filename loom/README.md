<img src="ai-agent4j-loom/loom_logo.png" align="right" width="200" alt="Loom Logo">

# Loom DSL

Loom is a Domain-Specific Language (DSL) for orchestrating multi-agent AI workflows. This directory contains all Loom-related components.

## Components

### [ai-agent4j-loom](./ai-agent4j-loom/)
Java runtime implementation of the Loom DSL. Includes:
- Lexer and Parser for `.loom` files
- AST (Abstract Syntax Tree) representation
- HarnessExecutor for workflow execution
- Integration with ai-agent4j core framework

### [vscode-loom](./vscode-loom/)
VS Code extension providing first-class authoring experience for Loom scripts:
- Syntax highlighting for `.loom` and `.loot` files
- Language Server Protocol (LSP) with diagnostics, hover, and completion
- Workflow Outline tree view for navigation
- Run Workflow command integration

### [ctk](./ctk/)
Conformance Test Kit (CTK) for validating Loom runtime implementations:
- Canonical test scripts covering all Loom statement types
- Mock agent server with fixture-based responses
- Execution trace comparison algorithm
- Test runner for behavioral validation

### [loom4py](./loom4py/) _(Coming Soon)_
Python runtime implementation of the Loom DSL, providing:
- Character-by-character Lexer (no regex)
- Recursive descent Parser
- AST nodes as Python dataclasses
- HarnessExecutor with async support
- CTK validation for behavioral parity with Java runtime

## Getting Started

### Java Runtime
```bash
cd ai-agent4j-loom
mvn clean install
```

### VS Code Extension
```bash
cd vscode-loom
npm install
npm run compile
```

### Conformance Test Kit
```bash
cd ctk
mvn test
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain
```

## Documentation

- [Loom Language Specification](./ai-agent4j-loom/docs/LANGUAGE_SPEC.md) _(if exists)_
- [VS Code Extension Guide](./vscode-loom/README.md)
- [CTK Usage Guide](./ctk/README.md)

## Contributing

See the main [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines.
