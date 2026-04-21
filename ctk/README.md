# Loom Conformance Test Kit (CTK)

Conformance Test Kit for Loom runtimes. Validates behavioral parity across Java and Python implementations.

## Overview

The CTK provides a canonical suite of `.loom` test scripts, expected execution traces, and mock agent fixtures that define the behavioral contract for any compliant Loom runtime implementation.

## Structure

- **`scripts/`** — Canonical `.loom` test scripts covering all statement types
- **`traces/`** — Expected execution trace JSON files for each test script
- **`mocks/`** — Mock agent fixture JSON files providing deterministic responses
- **`src/main/java/`** — CTK runner implementation
- **`src/test/java/`** — Unit and property tests for the CTK

## Usage

### Running the CTK

```bash
# Build the project
mvn clean package

# Run all conformance tests
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain

# Run tests in parallel
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain -Dexec.args="--parallel"
```

### Running Tests

```bash
mvn test
```

## Requirements

- Java 17 or higher
- Maven 3.6 or higher
- ai-agent4j runtime built and available at `../ai-agent4j/target/ai-agent4j-5.0.jar`

## License

MIT License
