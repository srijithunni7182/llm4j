# CTK Core Components

This package contains the core data models and interfaces for the Loom Conformance Test Kit (CTK).

## Data Models

### ExecutionTrace
Represents the complete execution trace of a Loom workflow, capturing the ordered sequence of statement executions.

**Fields:**
- `scriptName`: Name of the .loom script that was executed
- `workflowName`: Name of the workflow that was executed
- `steps`: Ordered list of execution steps

### TraceStep
Represents a single step in an execution trace, capturing the execution of a single Loom statement.

**Fields:**
- `kind`: Statement type (delegate, handoff, broadcast, note, call, parallel, observe)
- `agentName`: Name of the agent executing this step (nullable)
- `payload`: Input payload for this step (nullable)
- `outputVariable`: Variable name where output is stored (nullable)
- `outputValue`: Actual output value (nullable, not compared in CTK)
- `subSteps`: Nested steps for parallel/call statements (nullable)
- `timestamp`: ISO-8601 timestamp of step execution (nullable)

### ConformanceResult
Represents the result of a single CTK conformance test case.

**Fields:**
- `testName`: Name of the test case
- `passed`: True if the actual trace matched the expected trace structurally
- `differences`: List of human-readable difference descriptions (empty when passed is true)

## Interfaces

### ConformanceRunner
Interface for executing CTK conformance tests against a Loom runtime.

**Methods:**
- `run(Path scriptPath, Path tracePath, MockAgentServer mocks)`: Executes a single conformance test case
- `runAll(Path ctkDir, MockAgentServer mocks)`: Executes all conformance test cases in a directory

### MockAgentServer
Interface for providing mock agent responses during CTK test execution.

**Methods:**
- `getResponse(String agentName, String payload)`: Returns a deterministic response for the given agent and payload

## Implementations

### FixtureMockAgentServer
Implementation of MockAgentServer that loads fixture responses from JSON files.

**Constructor:**
- `FixtureMockAgentServer(Path mocksDir)`: Loads fixtures from JSON files in the specified directory
- `FixtureMockAgentServer(Map<String, Map<String, String>> fixtures)`: Creates server with pre-loaded fixtures

**Fixture JSON Format:**
```json
{
  "AgentName": {
    "payload1": "response1",
    "payload2": "response2"
  }
}
```

**Features:**
- Immutable during test runs - all state loaded at construction time
- Supports multiple fixture files that are merged together
- Provides default responses for missing agents/payloads
- Deep defensive copying to prevent external modifications

## Usage Example

```java
// Load fixtures from directory
Path mocksDir = Path.of("ctk/mocks");
MockAgentServer mocks = new FixtureMockAgentServer(mocksDir);

// Get a response
String response = mocks.getResponse("ExampleAgent", "Hello, world!");
```

## Requirements Validation

These components validate the following requirements:
- **Requirements 8.2, 9.1, 9.4, 9.6**: CTK data models and mock server
- **Requirements 22.1, 22.2, 22.3**: Execution trace schema validity
