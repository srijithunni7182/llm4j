# Implementation Plan: Loom Enhancement Roadmap

## Overview

This plan implements three independently deliverable components in priority order:

1. **VS Code Extension (`vscode-loom`)** — TypeScript extension providing syntax highlighting, LSP-backed diagnostics, and a Workflow Outline tree view for `.loom` and `.loot` files.
2. **Conformance Test Kit (CTK)** — Java-based runner with canonical `.loom` scripts, expected execution traces, and mock agent fixtures that validate any Loom runtime.
3. **Python Runtime (`loom4py`)** — Python port of the Java Loom Lexer, Parser, AST nodes, and HarnessExecutor.

Each component builds on the existing Java reference implementation (`ai-agent4j-loom`) without modifying it. The CTK is implemented before loom4py so that loom4py can be validated against the canonical test suite.

---

## Tasks

<!-- ================================================================ -->
<!-- COMPONENT 1: VS Code Extension (vscode-loom)                     -->
<!-- ================================================================ -->

- [x] 1. Scaffold the `vscode-loom` extension project
  - Create the `vscode-loom/` directory with `package.json`, `tsconfig.json`, `.vscodeignore`, and `README.md`
  - Configure `package.json` with `name: "vscode-loom"`, `activationEvents: ["onLanguage:loom", "onLanguage:loot"]`, and the `vscode`, `vscode-languageclient`, `vscode-languageserver`, and `vscode-languageserver-textdocument` dependencies at pinned versions
  - Add `contributes.languages` entries for `loom` (`.loom`) and `loot` (`.loot`) language IDs
  - Add `contributes.grammars` entries pointing to TextMate grammar files (to be created in task 2)
  - Add `contributes.views` entry for the `WorkflowOutline` sidebar panel
  - Add `contributes.commands` entry for the `loom.runWorkflow` command
  - _Requirements: 1.1, 1.2, 1.6, 1.7, 7.1_

- [x] 2. Implement TextMate grammars and language configuration
  - [x] 2.1 Create `syntaxes/loom.tmLanguage.json` with scopes for keywords (`agent`, `workflow`, `delegate`, `handoff`, `broadcast`, `parallel`, `loop`, `alt`, `call`, `guardrail`, `observe`, `schedule`, `routing`, `import`, `mcp`, `audit`, `note`, `retry`, `on_failure`), string literals, comments (`//`), identifiers, and operators (`->`, `=`, `==`, `!=`, `<=`, `>=`)
    - _Requirements: 1.3_
  - [x] 2.2 Create `syntaxes/loot.tmLanguage.json` with scopes for `.loot` file constructs
    - _Requirements: 1.4_
  - [x] 2.3 Create `language-configuration.json` for both language IDs with bracket pairs `{}`, `()`, `[]`, comment tokens `//`, and folding markers
    - _Requirements: 1.5_

- [x] 3. Implement the extension activation entry point (`src/extension.ts`)
  - [x] 3.1 Implement `activate(context)` to register language configurations, start the LSP client (pointing to the language server entry point), register the `loom.runWorkflow` command, and register the `WorkflowOutlineProvider` tree view
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 6.1_
  - [x] 3.2 Implement `deactivate()` to stop the LSP client and release all resources
    - _Requirements: 2.3_
  - [x] 3.3 Add LSP client restart logic: if the language server process exits unexpectedly, attempt one restart and show a VS Code error notification if the restart fails
    - _Requirements: 2.4_

- [ ] 4. Implement the Language Server (`src/lsp/server.ts`)
  - [x] 4.1 Set up the `vscode-languageserver` connection, initialize capabilities (`textDocumentSync`, `hoverProvider`, `definitionProvider`, `completionProvider`), and wire `onInitialize`
    - _Requirements: 2.1, 2.2_
  - [x] 4.2 Implement `onDidChangeTextDocument` with 300 ms debounce: tokenize and parse the document, collect all `LexError` and `ParseError` exceptions, convert them to `Diagnostic` objects with severity `Error`, and send via `connection.sendDiagnostics`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 21.1, 21.2_
  - [x] 4.3 Implement undefined-agent-reference detection: after a successful parse, walk all `delegate`/`handoff`/`broadcast` statements and emit a `Diagnostic` with severity `Warning` for each agent name not found in the script's agent definitions
    - _Requirements: 4.1, 4.2, 4.3, 21.3_
  - [x] 4.4 Implement `onHover`: look up the identifier under the cursor in the parsed AST and return a `Hover` with the agent or workflow definition summary
    - _Requirements: 5.1_
  - [x] 4.5 Implement `onDefinition`: resolve the identifier under the cursor to its definition `Location` in the AST; return `null` if not found
    - _Requirements: 5.2, 5.3_
  - [x] 4.6 Implement `onCompletion`: return `CompletionItem` objects for all Loom keywords plus all agent and workflow identifiers defined in the current document
    - _Requirements: 5.4_
  - [ ]* 4.7 Write property test for LSP diagnostics on syntax errors (Property 18: any document with a syntax error produces ≥1 Error diagnostic)
    - **Property 18: LSP Diagnostics for Syntax Errors**
    - **Validates: Requirements 3.1, 3.2, 3.3**
  - [ ]* 4.8 Write property test for LSP no diagnostics on valid documents (Property 19)
    - **Property 19: LSP No Diagnostics for Valid Documents**
    - **Validates: Requirements 3.4**
  - [ ]* 4.9 Write property test for LSP warning on undefined agent references (Property 20)
    - **Property 20: LSP Warning for Undefined Agent References**
    - **Validates: Requirements 4.1, 4.2**
  - [ ]* 4.10 Write property test for LSP completion completeness (Property 22)
    - **Property 22: LSP Completion Includes All Keywords and Identifiers**
    - **Validates: Requirements 5.4**

- [x] 5. Implement the Workflow Outline tree view (`src/views/WorkflowOutlineProvider.ts`)
  - [x] 5.1 Implement `WorkflowOutlineProvider` implementing `vscode.TreeDataProvider<OutlineNode>` with `getTreeItem`, `getChildren`, and `refresh` methods; define the `OutlineNode` interface with `kind`, `name`, and `range` fields
    - _Requirements: 6.1, 6.3_
  - [x] 5.2 Wire the provider to re-parse and refresh on `vscode.workspace.onDidSaveTextDocument` for `.loom` files, and clear the tree when the active editor changes to a non-`.loom` file
    - _Requirements: 6.2, 6.5_
  - [x] 5.3 Implement click-to-navigate: when a user selects an `OutlineNode`, call `vscode.window.showTextDocument` with the node's `range` to move the editor cursor to the definition
    - _Requirements: 6.4_
  - [ ]* 5.4 Write property test for Workflow Outline tree hierarchy ordering (Property 21: agents before workflows before schedules before routing policies)
    - **Property 21: Workflow Outline Tree Hierarchy**
    - **Validates: Requirements 6.3**

- [x] 6. Implement the "Run Workflow" command (`src/commands/runWorkflow.ts`)
  - [x] 6.1 Implement the `loom.runWorkflow` command handler: obtain the active editor's file path, create or reuse a VS Code output channel named "Loom", and invoke `child_process.spawn("weave", ["run", scriptPath])` — passing the path as a discrete array element, never interpolated into a shell string
    - _Requirements: 7.2, 7.4_
  - [x] 6.2 Pipe `stdout` and `stderr` from the spawned process to the output channel; show the output channel when the command is invoked
    - _Requirements: 7.3_
  - [ ]* 6.3 Write property test for Run Workflow path safety (Property 23: paths with shell-special characters are passed as discrete spawn arguments)
    - **Property 23: Run Workflow Command Path Safety**
    - **Validates: Requirements 7.4**

- [x] 7. Checkpoint — VS Code Extension
  - Ensure all TypeScript compiles without errors (`tsc --noEmit`)
  - Ensure all tests pass (`npm test` or equivalent)
  - Ask the user if questions arise before proceeding to the CTK.

<!-- ================================================================ -->
<!-- COMPONENT 2: Conformance Test Kit (CTK)                          -->
<!-- ================================================================ -->

- [x] 8. Scaffold the CTK project structure
  - Create `ctk/` directory with a Maven `pom.xml` declaring dependencies on `ai-agent4j-loom` (local), `jackson-databind` (pinned), and `junit-jupiter` (pinned)
  - Create directory layout: `ctk/scripts/`, `ctk/traces/`, `ctk/mocks/`, `ctk/src/main/java/io/github/loom/ctk/`, `ctk/src/test/java/io/github/loom/ctk/`
  - _Requirements: 8.1, 8.2, 8.3_

- [x] 9. Define CTK data model and interfaces
  - [x] 9.1 Create `ExecutionTrace.java` and `TraceStep.java` Java records/classes matching the `ExecutionTrace` schema: `scriptName`, `workflowName`, `steps` (list of `TraceStep`); `TraceStep` fields: `kind`, `agentName`, `payload`, `outputVariable`, `outputValue`, `subSteps`, `timestamp`
    - _Requirements: 22.1, 22.2, 22.3_
  - [x] 9.2 Create `ConformanceResult.java` record with fields `testName`, `passed`, and `differences` (list of strings)
    - _Requirements: 9.1, 9.4_
  - [x] 9.3 Create `ConformanceRunner.java` interface with `run(Path scriptPath, Path tracePath, MockAgentServer mocks)` and `runAll(Path ctkDir, MockAgentServer mocks)` methods
    - _Requirements: 9.1, 9.5_
  - [x] 9.4 Create `MockAgentServer.java` interface and `FixtureMockAgentServer.java` implementation that loads fixture JSON files from `ctk/mocks/` and returns deterministic responses; the implementation must be immutable during a run
    - _Requirements: 8.2, 9.6_

- [x] 10. Implement the trace comparison algorithm
  - [x] 10.1 Implement `TraceComparator.compareTraces(ExecutionTrace actual, ExecutionTrace expected)` following the pseudocode in the design: check `workflowName` equality, step count equality, then per-step `kind`, `agentName`, and `outputVariable` equality; do NOT compare `outputValue`
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_
  - [ ]* 10.2 Write property test for CTK trace reflexivity (Property 10: `compareTraces(t, t).passed == true` for any valid trace)
    - **Property 10: CTK Trace Reflexivity**
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.8, 10.9**
  - [ ]* 10.3 Write property test for CTK output value independence (Property 11: structurally identical traces differing only in `outputValue` compare as equal)
    - **Property 11: CTK Output Value Independence**
    - **Validates: Requirements 10.6**
  - [ ]* 10.4 Write property test for CTK differences non-empty on mismatch (Property 12: structurally different traces produce `passed=false` and non-empty `differences`)
    - **Property 12: CTK Differences Non-Empty on Mismatch**
    - **Validates: Requirements 10.7**

- [x] 11. Implement the CTK Runner
  - [x] 11.1 Implement `DefaultConformanceRunner.run()`: load the `.loom` script, inject the `MockAgentServer`, execute via the Java `HarnessExecutor`, capture the resulting `ExecutionTrace` as JSON, deserialize it, and call `TraceComparator.compareTraces()`
    - _Requirements: 9.1, 9.2_
  - [x] 11.2 Implement `DefaultConformanceRunner.runAll()`: discover all `*.loom` files in `ctk/scripts/`, match each to its corresponding trace in `ctk/traces/`, run each test case, collect results, and return the list
    - _Requirements: 9.5_
  - [x] 11.3 Implement the CLI entry point (`CtkMain.java`): parse `--parallel` flag, invoke `runAll()` (sequentially or via `ExecutorService` when `--parallel` is set), print a summary report, and exit with code 0 on all-pass or non-zero on any failure
    - _Requirements: 9.3, 9.4, 19.2_
  - [ ]* 11.4 Write property test for CTK mock server immutability (Property 13: `MockAgentServer` state is identical before and after `run()`)
    - **Property 13: CTK Mock Server Immutability**
    - **Validates: Requirements 9.6**

- [x] 12. Create canonical CTK test scripts, traces, and mock fixtures
  - [x] 12.1 Create `ctk/scripts/delegate_basic.loom` — a minimal workflow with a single `delegate` statement; create matching `ctk/mocks/delegate_basic.json` fixture and `ctk/traces/delegate_basic.json` expected trace
    - _Requirements: 8.1, 8.2, 8.3, 8.4_
  - [x] 12.2 Create canonical scripts and fixtures for `handoff`, `broadcast`, `parallel`, `loop`, `alt`, `call`, `guardrail`, `observe`, and `schedule` statement types — one script per statement type, each with a corresponding mock fixture and expected trace
    - _Requirements: 8.1, 8.2, 8.3, 8.4_
  - [x] 12.3 Create `ctk/scripts/retry_on_failure.loom` — a workflow with a `delegate` using `retry 2` and an `on_failure` block; create matching fixture and trace
    - _Requirements: 8.1, 8.3_
  - [x] 12.4 Create `ctk/scripts/call_scope_isolation.loom` — a workflow that calls a sub-workflow and verifies only the output variable is written back; create matching fixture and trace
    - _Requirements: 8.1, 8.3, 16.1, 16.2, 16.3_
  - [x] 12.5 Verify that no mock fixture file in `ctk/mocks/` contains real API keys or PII
    - _Requirements: 8.4_

- [x] 13. Checkpoint — Conformance Test Kit
  - Run `mvn test` in `ctk/` and ensure all unit and property tests pass
  - Run the CTK runner against the Java reference runtime (`mvn exec:java -Dexec.mainClass=...CtkMain`) and verify all canonical scripts pass
  - Ask the user if questions arise before proceeding to loom4py.

<!-- ================================================================ -->
<!-- COMPONENT 3: Python Runtime (loom4py)                            -->
<!-- ================================================================ -->

- [~] 14. Scaffold the `loom4py` Python package
  - Create `loom4py/` directory with `pyproject.toml` (declaring `httpx`, `pydantic`, `hypothesis`, and `pytest` at pinned versions), `README.md`, and the package structure: `loom4py/__init__.py`, `loom4py/lexer.py`, `loom4py/parser.py`, `loom4py/ast.py`, `loom4py/executor.py`, `loom4py/tool_registry.py`, `loom4py/exceptions.py`
  - Create `tests/` directory with `tests/__init__.py`, `tests/test_lexer.py`, `tests/test_parser.py`, `tests/test_executor.py`
  - _Requirements: 20.2, 20.3_

- [ ] 15. Implement `loom4py/exceptions.py` and `loom4py/ast.py`
  - [~] 15.1 Implement `exceptions.py` defining `LexError`, `ParseError`, `LoomRuntimeError`, and `HandoffSignal` (as a `BaseException` subclass for control flow)
    - _Requirements: 11.7, 11.8, 12.6, 14.3, 14.4, 15.4, 15.9_
  - [~] 15.2 Implement `ast.py` with all dataclass AST nodes: `Token`, `TokenType` (enum), `AgentDef`, `WorkflowDef`, `DelegateStmt`, `HandoffStmt`, `BroadcastStmt`, `ParallelBlock`, `LoopStmt`, `AltStmt`, `CallStmt`, `GuardrailStmt`, `ObserveStmt`, `NoteStmt`, `ScheduleDef`, `RoutingPolicyDef`, `McpServerDef`, `AuditConfig`, `SchemaDef`, `LoomScript`
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_
  - [~] 15.3 Implement `PrettyPrinter` class in `ast.py` with a `print(script: LoomScript) -> str` method that serializes the AST back to valid `.loom` source text
    - _Requirements: 18.1, 18.3_

- [ ] 16. Implement `loom4py/lexer.py`
  - [~] 16.1 Implement the `KEYWORDS` map (all Loom keywords → `TokenType`) and `SYMBOL_MAP` (single-character symbols → `TokenType`)
    - _Requirements: 11.3, 11.4_
  - [~] 16.2 Implement `Lexer.__init__(source: str)` and `Lexer.tokenize() -> list[Token]` using character-by-character scanning (no regex) following the pseudocode in the design: handle symbols, `->` arrow, `=`/`==`, comparison operators, `//` comments, string literals (with quote stripping), newlines (line counter increment), whitespace skipping, numbers, and identifiers/keywords; raise `LexError` for unrecognized characters and bare `-`
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9, 11.10, 20.2_
  - [ ]* 16.3 Write property test for Lexer EOF invariant (Property 1: `tokenize()` on any string always returns a non-empty list ending with `EOF`)
    - **Property 1: Lexer EOF Invariant**
    - **Validates: Requirements 11.1, 11.2**
  - [ ]* 16.4 Write property test for Lexer keyword resolution (Property 2: recognized keywords produce keyword `TokenType`; unrecognized identifiers produce `IDENTIFIER`)
    - **Property 2: Lexer Keyword Resolution**
    - **Validates: Requirements 11.3, 11.4**
  - [ ]* 16.5 Write property test for Lexer string literal quote stripping (Property 3: `STRING_LITERAL` token values have no surrounding double quotes)
    - **Property 3: Lexer String Literal Quote Stripping**
    - **Validates: Requirements 11.5**
  - [ ]* 16.6 Write property test for Lexer comment skipping (Property 4: no tokens produced for characters between `//` and end of line)
    - **Property 4: Lexer Comment Skipping**
    - **Validates: Requirements 11.6**
  - [ ]* 16.7 Write property test for Lexer error on unrecognized character (Property 5: any source with an invalid character raises `LexError`)
    - **Property 5: Lexer Error on Unrecognized Character**
    - **Validates: Requirements 11.8, 21.1**
  - [ ]* 16.8 Write property test for Lexer character consumption completeness (Property 6: every character consumed exactly once)
    - **Property 6: Lexer Character Consumption Completeness**
    - **Validates: Requirements 11.9**
  - [ ]* 16.9 Write property test for Lexer line number monotonicity (Property 7: line numbers are non-decreasing and increment by 1 per newline)
    - **Property 7: Lexer Line Number Monotonicity**
    - **Validates: Requirements 11.10**

- [ ] 17. Implement `loom4py/parser.py`
  - [~] 17.1 Implement `LoomParser.__init__(tokens: list[Token])` with helper methods `peek()`, `advance()`, `expect(type)`, `isAtEnd()`, and `match(*types)`
    - _Requirements: 12.1, 12.5_
  - [~] 17.2 Implement `parse_script() -> LoomScript` following the recursive descent pseudocode: dispatch on the current token type to `parse_agent()`, `parse_workflow()`, `parse_routing()`, `parse_schedule()`, `parse_mcp()`, `parse_audit()`, or `parse_import()`; raise `ParseError` on unexpected top-level tokens
    - _Requirements: 12.1, 12.2, 12.5, 12.6_
  - [~] 17.3 Implement `parse_agent()`, `parse_workflow()`, `parse_routing()`, `parse_schedule()`, `parse_mcp()`, `parse_audit()`, and `parse_import()` top-level declaration parsers
    - _Requirements: 12.2, 12.3_
  - [~] 17.4 Implement all statement parsers within workflow bodies: `parse_delegate()`, `parse_handoff()`, `parse_broadcast()`, `parse_parallel()`, `parse_loop()`, `parse_alt()`, `parse_call()`, `parse_guardrail()`, `parse_observe()`, `parse_note()`
    - _Requirements: 12.7_
  - [~] 17.5 Implement circular import detection in `parse_import()`: maintain a `visited_imports` set; raise `ParseError("Circular import detected: <path>")` if a path is encountered twice
    - _Requirements: 12.3, 12.4_
  - [ ]* 17.6 Write property test for Parser round-trip (Property 8: parse → PrettyPrint → parse produces structurally equivalent `LoomScript`)
    - **Property 8: Parser Round-Trip (Parse → Print → Parse)**
    - **Validates: Requirements 18.2, 18.3, 12.1, 12.5**
  - [ ]* 17.7 Write property test for Parser error on invalid token sequence (Property 9: unexpected tokens raise `ParseError` with location info)
    - **Property 9: Parser Error on Invalid Token Sequence**
    - **Validates: Requirements 12.6**

- [~] 18. Implement `loom4py/tool_registry.py`
  - Implement `ToolRegistry` with `register(name: str, instance: object) -> None` and `get(name: str) -> object` methods; `get` raises `LoomRuntimeError` if the tool is not registered; do NOT implement any dynamic `importlib`-based loading
  - _Requirements: 17.1, 17.2_

- [ ] 19. Implement `loom4py/executor.py`
  - [~] 19.1 Implement `VariableContext` with `set(key, value)`, `get(key)`, `create_child() -> VariableContext`, and `merge_output(child, output_var)` methods to support scoped execution
    - _Requirements: 16.1, 16.2, 16.3_
  - [~] 19.2 Implement `HarnessExecutor.__init__(script, tool_registry, llm_client_factory)` storing references without initiating connections; implement `initialize()` to instantiate LLM clients for all agents via the factory, raising `LoomRuntimeError` if the factory returns `None` for any model
    - _Requirements: 14.1, 14.2, 14.4_
  - [~] 19.3 Implement `execute_workflow(workflow_name, initial_context)`: look up the workflow in `self.script.workflows` (raise `LoomRuntimeError` if not found), initialize a `VariableContext` from `initial_context`, and dispatch each statement to its handler; raise `LoomRuntimeError` if `initialize()` has not been called
    - _Requirements: 14.3, 15.1, 15.9_
  - [~] 19.4 Implement statement handlers: `_execute_delegate()` (dispatch to LLM, store in `output_var`, handle `retry` + `on_failure`), `_execute_handoff()` (raise `HandoffSignal`), `_execute_broadcast()` (dispatch to all agents, store responses), `_execute_note()` (no-op or log)
    - _Requirements: 15.2, 15.3, 15.4, 15.7, 15.8, 22.2, 22.3_
  - [~] 19.5 Implement `_execute_parallel()` using `concurrent.futures.ThreadPoolExecutor`: submit all branch statements concurrently and join before proceeding
    - _Requirements: 15.5_
  - [~] 19.6 Implement `_execute_call()`: create a child `VariableContext` initialized with the sub-workflow's parameters, execute the sub-workflow body, then merge only the declared output variable back into the parent context
    - _Requirements: 15.6, 16.1, 16.2, 16.3_
  - [~] 19.7 Implement `shutdown()` to release all LLM client connections
    - _Requirements: 14.5_
  - [~] 19.8 Implement execution trace recording: as each statement executes, append a `TraceStep` to an internal list; expose `get_trace() -> ExecutionTrace` for CTK integration
    - _Requirements: 22.1, 22.2, 22.3_
  - [ ]* 19.9 Write property test for HarnessExecutor call scope isolation (Property 14: no sub-workflow internal variables leak into parent context)
    - **Property 14: HarnessExecutor Call Scope Isolation**
    - **Validates: Requirements 15.6, 16.1, 16.2, 16.3**
  - [ ]* 19.10 Write property test for HarnessExecutor output variable population (Property 15: `delegate`, `broadcast`, and `call` always populate `output_var` in context)
    - **Property 15: HarnessExecutor Output Variable Population**
    - **Validates: Requirements 15.2, 15.3, 22.3**
  - [ ]* 19.11 Write property test for execution trace step kind validity (Property 16: every step `kind` in a produced trace is one of the valid enum values)
    - **Property 16: Execution Trace Step Kind Validity**
    - **Validates: Requirements 22.2**
  - [ ]* 19.12 Write property test for execution trace non-empty steps (Property 17: any completed workflow execution produces a trace with ≥1 step)
    - **Property 17: Execution Trace Non-Empty Steps**
    - **Validates: Requirements 22.1**

- [ ] 20. Validate loom4py against the CTK
  - [~] 20.1 Create `ctk/run_python.py` (or extend `CtkMain.java` with a Python adapter) that executes all canonical CTK scripts against loom4py and compares traces using the same `TraceComparator` logic
    - _Requirements: 20.1_
  - [~] 20.2 Run all canonical CTK scripts against loom4py and fix any behavioral divergences from the Java reference runtime until all CTK tests pass
    - _Requirements: 20.1_

- [~] 21. Final checkpoint — All components
  - Ensure all TypeScript tests pass for `vscode-loom`
  - Ensure all Java tests pass for the CTK (`mvn test`)
  - Ensure all Python tests pass for loom4py (`pytest --tb=short`)
  - Ensure the CTK runner exits with code 0 against both the Java runtime and loom4py
  - Ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Each task references specific requirements for traceability
- The CTK (tasks 8–13) must be completed before loom4py CTK validation (task 20)
- Property tests use `hypothesis` (Python) and `junit-quickcheck` (Java)
- Unit tests and property tests are complementary — both should be present where marked
- The `vscode-loom` extension uses `child_process.spawn` with an argument array (never shell interpolation) for security
- API keys for loom4py must come from environment variables only — never from `.loom` files or `pyproject.toml`
- The loom4py lexer must be character-by-character (no regex) to maintain O(n) complexity and structural parity with the Java reference
