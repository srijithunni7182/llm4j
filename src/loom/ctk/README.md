# Loom Conformance Test Kit (CTK)

Conformance Test Kit for Loom runtimes. Validates behavioral parity across Java and Python implementations.

## Overview

The CTK provides a canonical suite of `.loom` test scripts, expected execution traces, and mock agent fixtures that define the behavioral contract for any compliant Loom runtime implementation.

## Structure

- **`scripts/`** — Canonical `.loom` test scripts covering all statement types
- **`traces/`** — Expected execution trace JSON files for each test script
- **`mocks/`** — Mock agent fixture JSON files providing deterministic responses (for a task, what it returns for given arguments)
- **`src/main/java/`** — CTK runner implementation
- **`src/test/java/`** — Unit and property tests for the CTK

## Task steps (`run`)

A `run Name(arg = value) -> result` statement runs a deterministic task, not an agent. Its trace step has `"kind": "task"`, a `taskName`, and a `payload` that is the
arguments as **sorted-key, compact JSON** (`{"amount":40,"order":"A-1"}`), the same text in every runtime. Unlike a model's payload it is deterministic, so
the comparator checks it, together with the task name and the output variable. `scripts/task_basic.loom` and `scripts/task_rejected.loom` are the canonical
scenarios; `mocks/task_basic.json` maps each task's name and canonical arguments to the result map it returns (`outcome`, `reason`, `value`, data), which a runtime's
test tasks implement. The Java runtime runs them in `TaskCtkConformanceTest` (module `ai-agent4j-loom`), because this kit's own runner is still a stub.

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

Apache License 2.0
