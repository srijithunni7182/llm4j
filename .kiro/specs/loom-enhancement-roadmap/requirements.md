# Requirements Document

## Introduction

The Loom Enhancement Roadmap extends the Loom DSL ecosystem with three independently deliverable but mutually reinforcing components:

1. **VS Code Extension (`vscode-loom`)** — first-class authoring support for `.loom` and `.loot` files via syntax highlighting, a Language Server Protocol (LSP) backend, and a navigable Workflow Outline tree view.
2. **Conformance Test Kit (CTK)** — a canonical suite of `.loom` test scripts, expected execution traces, mock agent fixtures, and a Java-based runner that validates any Loom runtime implementation.
3. **Python Runtime (`loom4py`)** — a faithful port of the Java Loom runtime (Lexer, Parser, AST nodes, HarnessExecutor) to Python, enabling `.loom` script execution in Python AI/ML environments.

All three components are additive. No breaking changes are made to the existing Java reference implementation (`ai-agent4j-loom`).

---

## Glossary

- **Loom_DSL**: The domain-specific language for neuro-symbolic orchestration, stored in `.loom` files.
- **Loot_File**: A `.loot` file that maps logical tool names to runtime implementations.
- **VS_Code_Extension**: The `vscode-loom` TypeScript extension that runs inside VS Code.
- **Language_Server**: The LSP-compliant server process started by the VS Code Extension that provides language intelligence for `.loom` files.
- **Workflow_Outline**: The VS Code tree view panel that displays agents, workflows, schedules, and routing policies defined in the active `.loom` file.
- **CTK**: The Conformance Test Kit — the canonical set of scripts, traces, mocks, and runner that validates runtime behavior.
- **CTK_Runner**: The Java process that executes canonical `.loom` scripts against a target runtime and compares the resulting execution trace to the expected trace.
- **Mock_Agent_Server**: The fixture-backed server used by the CTK to provide deterministic responses in place of real LLM providers.
- **Execution_Trace**: A JSON document capturing the ordered sequence of statement executions produced by a runtime when running a workflow.
- **ConformanceResult**: The data record returned by the CTK_Runner for a single test case, containing a pass/fail flag and a list of human-readable differences.
- **loom4py**: The Python runtime package that ports the Java Loom Lexer, Parser, AST, and HarnessExecutor.
- **Lexer**: The component that tokenizes raw `.loom` source text into an ordered list of `Token` objects.
- **Parser**: The recursive-descent component that converts a token stream into a `LoomScript` AST.
- **LoomScript**: The root AST node containing all agents, workflows, schedules, routing policies, imports, MCP servers, and audit configuration parsed from a `.loom` file.
- **HarnessExecutor**: The component that walks the `LoomScript` AST and executes each statement, dispatching LLM calls and managing variable context.
- **OutlineNode**: A single node in the Workflow_Outline tree, representing an agent, workflow, schedule, or routing policy.
- **Diagnostic**: A VS Code language diagnostic object carrying a severity level, message, and source range.
- **LexError**: An exception raised by the Lexer when it encounters an unrecognized character.
- **ParseError**: An exception raised by the Parser when it encounters a structural or semantic error.
- **LoomRuntimeError**: An exception raised by the HarnessExecutor for runtime failures such as missing LLM clients.
- **HandoffSignal**: An internal signal raised by a `handoff` statement to terminate the current execution branch.
- **VariableContext**: The scoped key-value store that holds variable bindings during workflow execution.
- **ToolRegistry**: The Python component that maps logical tool names to registered tool instances.
- **Token**: A lexical unit produced by the Lexer, carrying a type, value, line number, and column offset.
- **TokenType**: The enumeration of all valid token categories (keywords, symbols, literals, EOF, etc.).

---

## Requirements

### Requirement 1: VS Code Language Registration

**User Story:** As a Loom script author, I want VS Code to recognize `.loom` and `.loot` files as distinct languages, so that I get appropriate syntax highlighting and editor features without manual configuration.

#### Acceptance Criteria

1. THE VS_Code_Extension SHALL register `loom` as a VS Code language ID with `.loom` as its file extension.
2. THE VS_Code_Extension SHALL register `loot` as a VS Code language ID with `.loot` as its file extension.
3. THE VS_Code_Extension SHALL associate a TextMate grammar with the `loom` language ID for syntax highlighting.
4. THE VS_Code_Extension SHALL associate a TextMate grammar with the `loot` language ID for syntax highlighting.
5. THE VS_Code_Extension SHALL associate a language configuration file (brackets, comments, folding rules) with each registered language ID.
6. WHEN VS Code opens a file with the `.loom` extension, THE VS_Code_Extension SHALL activate automatically.
7. WHEN VS Code opens a file with the `.loot` extension, THE VS_Code_Extension SHALL activate automatically.

---

### Requirement 2: Language Server Lifecycle Management

**User Story:** As a Loom script author, I want the language server to start automatically when I open a `.loom` file and stop when I close VS Code, so that language intelligence is always available without manual intervention.

#### Acceptance Criteria

1. WHEN the VS_Code_Extension activates, THE VS_Code_Extension SHALL start the Language_Server process.
2. WHILE the Language_Server process is running, THE VS_Code_Extension SHALL maintain the LSP client connection to it.
3. WHEN VS Code deactivates the extension, THE VS_Code_Extension SHALL stop the Language_Server process and release all associated resources.
4. IF the Language_Server process exits unexpectedly, THEN THE VS_Code_Extension SHALL attempt to restart it and report the failure as a VS Code notification if restart fails.

---

### Requirement 3: LSP Diagnostics — Syntax Errors

**User Story:** As a Loom script author, I want syntax errors in my `.loom` files to be highlighted in the editor as I type, so that I can fix mistakes before running the workflow.

#### Acceptance Criteria

1. WHEN a `.loom` document is opened or changed, THE Language_Server SHALL parse the document and produce `Diagnostic` objects for all syntax errors.
2. WHEN a syntax error `Diagnostic` is produced, THE Language_Server SHALL set its severity to `Error` and include the offending source range.
3. WHEN the Lexer encounters an unrecognized character, THE Language_Server SHALL report a `Diagnostic` with severity `Error` at the character's line and column.
4. WHEN the document contains no syntax errors, THE Language_Server SHALL clear all previously reported `Diagnostic` objects for that document.
5. WHEN a `.loom` document changes, THE Language_Server SHALL debounce re-parsing by at least 300 milliseconds before triggering a full re-parse.

---

### Requirement 4: LSP Diagnostics — Undefined Agent References

**User Story:** As a Loom script author, I want undefined agent references to be flagged as warnings in the editor, so that I can catch missing agent definitions during authoring without blocking incremental work.

#### Acceptance Criteria

1. WHEN a `delegate`, `handoff`, or `broadcast` statement references an agent name not defined in the script (after import resolution), THE Language_Server SHALL produce a `Diagnostic` with severity `Warning` at the reference location.
2. WHEN an undefined agent reference `Diagnostic` is produced, THE Language_Server SHALL include the undefined agent name in the diagnostic message.
3. WHEN a previously undefined agent is defined (e.g., by adding an `agent` block or resolving an import), THE Language_Server SHALL remove the corresponding warning `Diagnostic`.

---

### Requirement 5: LSP — Hover, Go-to-Definition, and Completion

**User Story:** As a Loom script author, I want hover documentation, go-to-definition navigation, and keyword completion in my `.loom` files, so that I can author scripts efficiently without memorizing the full syntax.

#### Acceptance Criteria

1. WHEN a hover request is received over an agent or workflow identifier, THE Language_Server SHALL return a `Hover` response containing the definition summary for that identifier.
2. WHEN a go-to-definition request is received on an agent or workflow reference, THE Language_Server SHALL return the `Location` of the corresponding definition within the same script (or resolved import).
3. IF no definition is found for a go-to-definition request, THEN THE Language_Server SHALL return `null`.
4. WHEN a completion request is received, THE Language_Server SHALL return `CompletionItem` objects for all Loom keywords and all agent/workflow identifiers defined in the current script.

---

### Requirement 6: Workflow Outline Tree View

**User Story:** As a Loom script author, I want a navigable tree view of all agents, workflows, schedules, and routing policies in the active `.loom` file, so that I can quickly understand the script structure and jump to any definition.

#### Acceptance Criteria

1. THE VS_Code_Extension SHALL register a `WorkflowOutline` tree view panel in the VS Code sidebar.
2. WHEN a `.loom` file is saved, THE Workflow_Outline SHALL re-parse the document and refresh the tree.
3. THE Workflow_Outline SHALL display `OutlineNode` items organized in the hierarchy: Agents → Workflows → Schedules → Routing Policies.
4. WHEN a user clicks an `OutlineNode` in the Workflow_Outline, THE VS_Code_Extension SHALL navigate the editor to the source range of that node's definition.
5. WHEN the active editor changes to a non-`.loom` file, THE Workflow_Outline SHALL clear its tree content.

---

### Requirement 7: Run Workflow Command

**User Story:** As a Loom script author, I want a "Run Workflow" command in VS Code that invokes the Loom CLI, so that I can execute the active script without leaving the editor.

#### Acceptance Criteria

1. THE VS_Code_Extension SHALL register a "Run Workflow" command accessible from the VS Code command palette.
2. WHEN the "Run Workflow" command is invoked, THE VS_Code_Extension SHALL invoke the `weave run` CLI using `child_process.spawn` with the script path passed as an argument array (not a shell string).
3. WHEN the `weave run` process produces output, THE VS_Code_Extension SHALL display that output in a dedicated VS Code output panel.
4. IF the script path contains characters that could cause shell injection, THEN THE VS_Code_Extension SHALL pass the path as a discrete argument element and SHALL NOT interpolate it into a shell command string.

---

### Requirement 8: CTK Canonical Test Scripts and Fixtures

**User Story:** As a Loom runtime implementer, I want a canonical set of `.loom` test scripts with corresponding mock fixtures, so that I can validate my runtime's behavior against a well-defined behavioral contract.

#### Acceptance Criteria

1. THE CTK SHALL provide canonical `.loom` test scripts in the `ctk/scripts/` directory covering all statement types: `delegate`, `handoff`, `broadcast`, `parallel`, `loop`, `alt`, `call`, `guardrail`, `observe`, and `schedule`.
2. THE CTK SHALL provide mock agent fixture files in the `ctk/mocks/` directory that supply deterministic responses for all agents referenced in the canonical scripts.
3. THE CTK SHALL provide expected execution trace files in `ctk/traces/` in JSON format conforming to the `ExecutionTrace` schema for each canonical script.
4. THE CTK mock fixture files SHALL NOT contain real API keys or personally identifiable information.

---

### Requirement 9: CTK Runner Execution

**User Story:** As a Loom runtime implementer, I want the CTK Runner to execute canonical scripts against my runtime and report pass/fail results, so that I can verify conformance automatically in CI.

#### Acceptance Criteria

1. WHEN the CTK_Runner is invoked with a script path, trace path, and Mock_Agent_Server, THE CTK_Runner SHALL execute the script against the target runtime and capture the resulting Execution_Trace as JSON.
2. WHEN the CTK_Runner captures an Execution_Trace, THE CTK_Runner SHALL compare it against the expected trace using the trace comparison algorithm.
3. WHEN all canonical test cases pass, THE CTK_Runner SHALL exit with a zero exit code.
4. WHEN one or more canonical test cases fail, THE CTK_Runner SHALL exit with a non-zero exit code and print a summary report listing each failing test and its differences.
5. THE CTK_Runner SHALL support executing all test cases in a directory via a `runAll` invocation.
6. THE CTK_Runner SHALL NOT mutate the Mock_Agent_Server state during or after a test run.

---

### Requirement 10: CTK Trace Comparison

**User Story:** As a Loom runtime implementer, I want the CTK to compare execution traces structurally (not by LLM output value), so that conformance tests are deterministic regardless of which LLM provider is used.

#### Acceptance Criteria

1. WHEN comparing an actual Execution_Trace to an expected Execution_Trace, THE CTK_Runner SHALL verify that both traces reference the same `workflowName`.
2. WHEN comparing traces, THE CTK_Runner SHALL verify that the number of steps in the actual trace equals the number of steps in the expected trace.
3. WHEN comparing individual steps, THE CTK_Runner SHALL verify that the `kind` field of each step matches between actual and expected.
4. WHEN comparing individual steps, THE CTK_Runner SHALL verify that the `agentName` field of each step matches between actual and expected.
5. WHEN comparing individual steps, THE CTK_Runner SHALL verify that the `outputVariable` field of each step matches between actual and expected.
6. THE CTK_Runner SHALL NOT compare the `outputValue` field of any step, as LLM response content is non-deterministic.
7. WHEN traces differ, THE CTK_Runner SHALL populate the `ConformanceResult.differences` list with human-readable descriptions of each mismatch.
8. WHEN traces are structurally equivalent, THE CTK_Runner SHALL return a `ConformanceResult` with `passed` equal to `true` and an empty `differences` list.
9. WHEN a trace is compared against itself, THE CTK_Runner SHALL return a `ConformanceResult` with `passed` equal to `true`.

---

### Requirement 11: loom4py Lexer

**User Story:** As a Python developer, I want the loom4py Lexer to tokenize `.loom` source text into a typed token stream, so that the Parser can build an AST without dealing with raw characters.

#### Acceptance Criteria

1. WHEN `tokenize()` is called on any string input, THE Lexer SHALL return a non-empty list whose last element has `TokenType.EOF`.
2. WHEN `tokenize()` is called on an empty string, THE Lexer SHALL return a list containing exactly one token with `TokenType.EOF`.
3. WHEN the Lexer encounters a recognized keyword from the `KEYWORDS` map, THE Lexer SHALL produce a token with the corresponding `TokenType` rather than `IDENTIFIER`.
4. WHEN the Lexer encounters an identifier that is not in the `KEYWORDS` map, THE Lexer SHALL produce a token with `TokenType.IDENTIFIER`.
5. WHEN the Lexer encounters a string literal delimited by double quotes, THE Lexer SHALL produce a `STRING_LITERAL` token whose value has the surrounding quotes stripped.
6. WHEN the Lexer encounters a `//` sequence, THE Lexer SHALL skip all characters until the end of the current line and produce no token for the comment.
7. WHEN the Lexer encounters a `-` character not followed by `>`, THE Lexer SHALL raise a `LexError` with the line number and column.
8. WHEN the Lexer encounters any character that is not part of a valid token pattern, THE Lexer SHALL raise a `LexError` with the offending character, line number, and column.
9. THE Lexer SHALL consume every character in the source string exactly once.
10. WHEN `tokenize()` completes successfully, THE Lexer SHALL have assigned correct line numbers to all tokens, incrementing the line counter on each newline character.

---

### Requirement 12: loom4py Parser

**User Story:** As a Python developer, I want the loom4py Parser to convert a token stream into a `LoomScript` AST, so that the HarnessExecutor can walk the tree and execute the workflow.

#### Acceptance Criteria

1. WHEN `parse_script()` is called with a token list whose last token is `EOF`, THE Parser SHALL return a `LoomScript` containing all top-level declarations found in the token stream.
2. THE Parser SHALL parse `agent`, `workflow`, `routing`, `schedule`, `mcp`, `audit`, and `import` top-level declarations into their corresponding AST node types.
3. WHEN the Parser encounters an `import` declaration, THE Parser SHALL resolve the imported file, tokenize it, and merge its declarations into the current `LoomScript`.
4. WHEN import resolution detects a circular dependency (File A → File B → File A), THE Parser SHALL raise a `ParseError` with a message identifying the circular import path.
5. WHEN `parse_script()` completes successfully, THE Parser SHALL have consumed all tokens up to and including `EOF`.
6. WHEN the Parser encounters an unexpected token at the top level, THE Parser SHALL raise a `ParseError` identifying the unexpected token and its source location.
7. THE Parser SHALL parse all statement types within workflow bodies: `delegate`, `handoff`, `broadcast`, `parallel`, `loop`, `alt`, `call`, `guardrail`, `observe`, and `note`.

---

### Requirement 13: loom4py AST Nodes

**User Story:** As a Python developer, I want the loom4py AST to faithfully represent all Loom DSL constructs as Python dataclasses, so that the executor and tooling can inspect and traverse the script structure.

#### Acceptance Criteria

1. THE loom4py AST SHALL define an `AgentDef` dataclass with fields: `name`, `model`, `system` (optional), `tools` (list), `skills` (list), `persona` (optional), `routing` (optional), and `output_schema` (optional).
2. THE loom4py AST SHALL define a `WorkflowDef` dataclass with fields: `name`, `params` (list), and `body` (list of statements).
3. THE loom4py AST SHALL define a `DelegateStmt` dataclass with fields: `payload`, `agent_name`, `output_var`, `retry_count` (default 0), and `on_failure` (list of statements, default empty).
4. THE loom4py AST SHALL define a `LoomScript` dataclass with fields: `agents` (list), `workflows` (list), `schedules` (list), `routing_policies` (list), and `imports` (list).
5. THE loom4py AST SHALL define dataclass nodes for all remaining statement types: `HandoffStmt`, `BroadcastStmt`, `ParallelBlock`, `LoopStmt`, `AltStmt`, `CallStmt`, `GuardrailStmt`, `ObserveStmt`, and `NoteStmt`.

---

### Requirement 14: loom4py HarnessExecutor — Initialization and Shutdown

**User Story:** As a Python developer, I want the loom4py HarnessExecutor to manage LLM client lifecycle explicitly, so that resources are acquired before execution and released cleanly after.

#### Acceptance Criteria

1. WHEN `HarnessExecutor.__init__()` is called, THE HarnessExecutor SHALL store the provided `LoomScript`, `ToolRegistry`, and `LLMClientFactory` without initiating any LLM connections.
2. WHEN `initialize()` is called, THE HarnessExecutor SHALL instantiate LLM clients for all agents defined in the `LoomScript` using the `LLMClientFactory`.
3. IF `execute_workflow()` is called before `initialize()`, THEN THE HarnessExecutor SHALL raise a `LoomRuntimeError` indicating that initialization has not been performed.
4. IF the `LLMClientFactory` returns `None` for a requested model, THEN THE HarnessExecutor SHALL raise a `LoomRuntimeError` with a message identifying the missing model.
5. WHEN `shutdown()` is called, THE HarnessExecutor SHALL release all LLM client connections and free associated resources.

---

### Requirement 15: loom4py HarnessExecutor — Workflow Execution

**User Story:** As a Python developer, I want the loom4py HarnessExecutor to execute workflow statements in order and dispatch LLM calls correctly, so that `.loom` scripts produce the same behavior in Python as in the Java reference runtime.

#### Acceptance Criteria

1. WHEN `execute_workflow()` is called with a valid `workflow_name` and `initial_context`, THE HarnessExecutor SHALL execute all statements in the workflow body in sequential order.
2. WHEN a `delegate` statement is executed, THE HarnessExecutor SHALL dispatch the payload to the named agent's LLM client and store the response in the specified `output_var` in the `VariableContext`.
3. WHEN a `broadcast` statement is executed, THE HarnessExecutor SHALL dispatch the payload to all agents in the script and store each response in the specified output variable.
4. WHEN a `handoff` statement is executed, THE HarnessExecutor SHALL raise a `HandoffSignal` to terminate the current execution branch.
5. WHEN a `parallel` block is executed, THE HarnessExecutor SHALL execute all contained statements concurrently and SHALL NOT proceed to the next statement until all parallel branches have completed.
6. WHEN a `call` statement is executed, THE HarnessExecutor SHALL execute the named sub-workflow in an isolated `VariableContext` and SHALL write only the declared output variable back to the parent `VariableContext`.
7. WHEN a `delegate` statement with `retry N` exhausts all N+1 attempts, THE HarnessExecutor SHALL execute the `on_failure` block if one is present.
8. IF a `delegate` statement with `retry N` exhausts all attempts and no `on_failure` block is present, THEN THE HarnessExecutor SHALL propagate the exception to the caller.
9. WHEN `execute_workflow()` is called with a `workflow_name` that does not exist in the `LoomScript`, THE HarnessExecutor SHALL raise a `LoomRuntimeError` identifying the missing workflow.

---

### Requirement 16: loom4py Variable Context and Scoping

**User Story:** As a Loom script author, I want variable bindings in sub-workflow calls to be isolated from the parent scope, so that sub-workflows cannot accidentally overwrite parent variables.

#### Acceptance Criteria

1. WHEN a `call` sub-workflow begins execution, THE HarnessExecutor SHALL create a new `VariableContext` initialized with the sub-workflow's declared parameters bound to the values passed by the caller.
2. WHEN a `call` sub-workflow completes, THE HarnessExecutor SHALL write only the declared output variable from the sub-workflow's `VariableContext` back into the parent `VariableContext`.
3. WHILE a `call` sub-workflow is executing, THE HarnessExecutor SHALL NOT allow writes to the sub-workflow's internal variables to affect any variable in the parent `VariableContext` other than the declared output variable.

---

### Requirement 17: loom4py Security — Tool Registry and API Keys

**User Story:** As a Python developer, I want the loom4py runtime to use an explicit tool registration API and environment-variable-sourced API keys, so that the runtime does not execute arbitrary code or expose credentials.

#### Acceptance Criteria

1. THE ToolRegistry SHALL require tools to be registered explicitly via a `register(name, instance)` API call before they can be invoked by the HarnessExecutor.
2. THE ToolRegistry SHALL NOT load tool implementations dynamically from `.loot` file paths via `importlib` or equivalent dynamic import mechanisms.
3. THE HarnessExecutor SHALL source LLM API keys exclusively from environment variables.
4. THE HarnessExecutor SHALL NOT accept LLM API keys embedded in `.loom` script content or in `pyproject.toml`.

---

### Requirement 18: loom4py Parser — Pretty Printer and Round-Trip

**User Story:** As a Loom tooling developer, I want a pretty printer that serializes a `LoomScript` AST back to valid `.loom` source text, so that tooling can regenerate scripts and round-trip tests can validate parser correctness.

#### Acceptance Criteria

1. THE loom4py AST module SHALL provide a `PrettyPrinter` that serializes any `LoomScript` AST into a valid `.loom` source string.
2. WHEN a valid `.loom` source string is tokenized and parsed into a `LoomScript`, then serialized by the `PrettyPrinter`, then tokenized and parsed again, THE Parser SHALL produce a `LoomScript` that is structurally equivalent to the original.
3. WHEN the `PrettyPrinter` serializes a `LoomScript`, THE PrettyPrinter SHALL produce output that the Lexer can tokenize without raising a `LexError`.

---

### Requirement 19: CTK — Java Runtime Conformance Gate

**User Story:** As a Java runtime maintainer, I want the CTK to serve as a release gate for the Java runtime, so that no Java runtime release ships with regressions in behavioral conformance.

#### Acceptance Criteria

1. WHEN the CTK_Runner is executed against the Java reference runtime using all canonical scripts in `ctk/scripts/`, THE CTK_Runner SHALL report all test cases as passing before a Java runtime release is permitted.
2. THE CTK_Runner SHALL support a `--parallel` flag that executes independent test cases concurrently to reduce CI execution time.

---

### Requirement 20: loom4py — CTK Parity with Java Runtime

**User Story:** As a Python developer, I want loom4py to pass the same CTK test suite as the Java runtime, so that I can trust that Python and Java runtimes produce equivalent behavior for any given `.loom` script.

#### Acceptance Criteria

1. WHEN the CTK_Runner (or a Python-native equivalent) is executed against loom4py using all canonical scripts in `ctk/scripts/`, THE CTK_Runner SHALL report all test cases as passing.
2. THE loom4py Lexer SHALL implement character-by-character tokenization (not regex-based) to maintain O(n) complexity and structural parity with the Java Lexer.
3. THE loom4py Parser SHALL implement a hand-written recursive descent algorithm mirroring `LoomParser.java`, without using a parser generator.

---

### Requirement 21: Error Handling — Lexer and Parser Errors in VS Code

**User Story:** As a Loom script author, I want all lexer and parser errors to surface as editor diagnostics rather than crashing the language server, so that I can continue editing even when my script has errors.

#### Acceptance Criteria

1. WHEN the Language_Server catches a `LexError` during document parsing, THE Language_Server SHALL convert it to a `Diagnostic` with severity `Error` and SHALL NOT terminate the Language_Server process.
2. WHEN the Language_Server catches a `ParseError` for a circular import, THE Language_Server SHALL report a `Diagnostic` with severity `Error` on the offending `import` line.
3. WHEN the Language_Server catches a `ParseError` for an undefined agent reference, THE Language_Server SHALL report a `Diagnostic` with severity `Warning` (not `Error`) to allow incremental authoring.

---

### Requirement 22: Execution Trace Schema Validity

**User Story:** As a CTK consumer, I want all execution traces to conform to the `ExecutionTrace` schema, so that trace comparison and tooling can rely on a consistent data shape.

#### Acceptance Criteria

1. THE HarnessExecutor (Java and Python) SHALL produce Execution_Trace JSON documents where the `steps` array is non-empty for any executed workflow.
2. THE HarnessExecutor SHALL produce Execution_Trace JSON documents where every step's `kind` field is one of: `delegate`, `handoff`, `broadcast`, `note`, `call`, `parallel`, or `observe`.
3. WHEN a step's `kind` is `delegate`, `broadcast`, or `call`, THE HarnessExecutor SHALL include a non-null `outputVariable` field in that step's trace entry.
