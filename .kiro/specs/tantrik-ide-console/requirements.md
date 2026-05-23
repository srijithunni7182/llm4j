# Requirements Document

## Introduction

The Tantrik IDE Console is a full IDE-like web application that replaces the existing minimal Tantrik Console MVP. It provides a professional, dark-themed development environment for authoring, managing, and executing Loom workflow scripts against the Tantrik orchestration engine. The console exposes a multi-panel layout with a collapsible file tree, a Monaco-based Loom script editor, a live execution trace view, an Engram knowledge graph visualization, a per-agent token consumption dashboard, and a natural-language chat panel that generates Loom scripts from project ideas. Both the React/Vite frontend and the Spring Boot backend are significantly expanded to support all panels.

---

## Glossary

- **IDE_Console**: The complete Tantrik IDE Console web application (frontend + backend).
- **Frontend**: The React/Vite single-page application served to the browser.
- **Backend**: The Spring Boot server that exposes the REST and SSE API.
- **File_Tree**: The collapsible left-sidebar panel listing Loom workflow files on the server.
- **Editor**: The Monaco-based code editor panel for viewing and editing Loom scripts.
- **Execution_Panel**: The panel that displays live SSE trace events for a running workflow.
- **Token_Dashboard**: The panel that shows per-agent and per-run token consumption breakdowns.
- **Engram_Panel**: The panel that visualises the Engram knowledge graph (nodes and edges).
- **Chat_Panel**: The natural-language prompt panel that generates Loom scripts via an LLM.
- **Loom_Script**: A `.loom` source file written in the Loom DSL.
- **TantrikExecutor**: The server-side Java class that parses and executes a Loom_Script.
- **TantrikTrace**: A single execution trace record emitted by TantrikExecutor (phase, agent, tokens, squeezed, compressionRatio, timestamp).
- **EngramEngine**: The server-side Java class that manages the Engram knowledge/memory graph.
- **MemoryObject**: A single node in the Engram knowledge graph (content, tier, importance, topicKey).
- **RunSummary**: The server-side model representing a completed or in-progress workflow run.
- **SSE_Stream**: The Server-Sent Events stream at `GET /api/runs/{runId}/stream`.
- **Loom_Generator**: The server-side service that converts a natural-language prompt into a Loom_Script using an LLM.
- **Panel_Layout**: The resizable, multi-panel IDE layout rendered by the Frontend.
- **Dark_Theme**: The default visual theme using a dark background palette.

---

## Requirements

### Requirement 1: IDE Panel Layout

**User Story:** As a developer, I want a multi-panel IDE layout with resizable panes, so that I can arrange the workspace to fit my workflow.

#### Acceptance Criteria

1. THE Frontend SHALL render a Panel_Layout composed of at minimum four independently resizable panes: File_Tree (left sidebar), Editor (center), Execution_Panel (right or bottom), and a secondary panel area for Token_Dashboard, Engram_Panel, and Chat_Panel.
2. WHEN a user drags a pane divider, THE Frontend SHALL resize the adjacent panes in real time without page reload.
3. THE Frontend SHALL apply the Dark_Theme by default, using a background color no lighter than `#1e1e1e` and foreground text no darker than `#cccccc`.
4. THE Frontend SHALL render a top navigation bar containing the application title "Tantrik IDE", global run controls (Start Run, Stop Run), and a tab bar for switching the secondary panel between Token_Dashboard, Engram_Panel, and Chat_Panel.
5. WHEN the viewport width is less than 900 pixels, THE Frontend SHALL collapse the File_Tree automatically and display a toggle button to expand it.
6. THE Frontend SHALL persist panel size preferences in browser `localStorage` so that sizes are restored on page reload.

---

### Requirement 2: Collapsible File Tree

**User Story:** As a developer, I want a collapsible file tree panel showing available Loom workflow files, so that I can browse and open scripts without leaving the IDE.

#### Acceptance Criteria

1. THE Backend SHALL expose `GET /api/files` returning a JSON array of file descriptors, each containing `path` (relative path), `name` (filename), and `lastModified` (ISO-8601 timestamp).
2. THE Backend SHALL serve Loom_Script files from a configurable root directory (default: `./loom-scripts`), scanning recursively for files with the `.loom` extension.
3. THE Backend SHALL expose `GET /api/files/{encodedPath}` returning the raw text content of the requested Loom_Script file.
4. THE Backend SHALL expose `PUT /api/files/{encodedPath}` accepting a plain-text request body and persisting the updated Loom_Script content to disk.
5. WHEN a user clicks a file in the File_Tree, THE Frontend SHALL load the file content via `GET /api/files/{encodedPath}` and display it in the Editor.
6. WHEN a user clicks the collapse toggle on the File_Tree, THE Frontend SHALL animate the sidebar to zero width and display an expand icon in its place.
7. THE Frontend SHALL display a visual indicator (dot or asterisk) next to a filename in the File_Tree WHEN the Editor contains unsaved changes for that file.
8. IF `GET /api/files` returns an HTTP error, THEN THE Frontend SHALL display an inline error message within the File_Tree panel without crashing the application.

---

### Requirement 3: Loom Script Editor

**User Story:** As a developer, I want a full-featured code editor for Loom scripts, so that I can write and edit workflows with syntax highlighting and keyboard shortcuts.

#### Acceptance Criteria

1. THE Editor SHALL use the Monaco Editor library to render Loom_Script content with syntax highlighting for the Loom DSL keywords (`agent`, `workflow`, `delegate`, `parallel`, `loop`, `handoff`, `broadcast`, `observe`, `guardrail`, `note`, `alt`).
2. THE Editor SHALL support standard keyboard shortcuts: save (`Ctrl+S` / `Cmd+S`), undo (`Ctrl+Z`), redo (`Ctrl+Y` / `Ctrl+Shift+Z`), find (`Ctrl+F`), and format document (`Shift+Alt+F`).
3. WHEN the user triggers save (`Ctrl+S` / `Cmd+S`), THE Editor SHALL call `PUT /api/files/{encodedPath}` with the current content and display a transient "Saved" notification for 2 seconds.
4. THE Editor SHALL display line numbers, a minimap, and bracket matching by default.
5. WHEN a Loom_Script is loaded into the Editor, THE Editor SHALL parse the script client-side and display a list of detected agent names and workflow names in a status bar below the editor.
6. IF `PUT /api/files/{encodedPath}` returns an HTTP error, THEN THE Editor SHALL display an error notification containing the HTTP status code and retain the unsaved content.

---

### Requirement 4: Workflow Execution and Live Trace

**User Story:** As a developer, I want to start a workflow run from the IDE and watch live execution traces, so that I can observe agent behavior in real time.

#### Acceptance Criteria

1. WHEN the user clicks "Start Run", THE Frontend SHALL call `POST /api/runs` with the current Editor script content, the selected workflow name, and a `mockMode` flag, then open the SSE_Stream for the returned `runId`.
2. THE Execution_Panel SHALL display each incoming SSE event as a row containing: timestamp, event type badge, agent name, message, execution tier badge (LOCAL / CLOUD / SYSTEM), and token estimate.
3. WHEN a `RUN_COMPLETED` event is received, THE Execution_Panel SHALL display a green "Completed" status banner and close the SSE connection.
4. WHEN a `RUN_FAILED` event is received, THE Execution_Panel SHALL display a red "Failed" status banner with the error message and close the SSE connection.
5. THE Execution_Panel SHALL support auto-scroll to the latest event, with a "Pause scroll" toggle that freezes the view at the current position.
6. THE Backend SHALL expose `DELETE /api/runs/{runId}` that signals the running TantrikExecutor to stop and sets the RunSummary status to `CANCELLED`.
7. WHEN the user clicks "Stop Run", THE Frontend SHALL call `DELETE /api/runs/{runId}` and display a "Cancelled" status in the Execution_Panel.
8. THE Backend SHALL retain the last 50 RunSummary records in memory and expose them via the existing `GET /api/runs` endpoint so the Frontend can display a run history list.

---

### Requirement 5: Token Consumption Dashboard

**User Story:** As a developer, I want a per-agent and per-run token consumption breakdown, so that I can understand and optimise the cost of my workflows.

#### Acceptance Criteria

1. THE Backend SHALL aggregate token data from TantrikTrace records and expose `GET /api/runs/{runId}/tokens` returning a JSON object with: `totalInputTokens` (integer), `agentBreakdown` (array of `{agentName, inputTokens, squeezedCount, avgCompressionRatio}`), and `runDurationMs` (long).
2. THE Token_Dashboard SHALL display a horizontal bar chart showing input token counts per agent for the selected run, with agent names on the Y-axis and token counts on the X-axis.
3. THE Token_Dashboard SHALL display a summary row showing total tokens, number of squeezed turns, and average compression ratio for the selected run.
4. WHEN a run is in progress, THE Token_Dashboard SHALL update the chart in real time as new TantrikTrace events arrive via the SSE_Stream, with a refresh interval no greater than 1 second.
5. THE Token_Dashboard SHALL allow the user to select any run from the run history list and display its token breakdown.
6. WHERE the `squeezed` flag is true on a TantrikTrace, THE Token_Dashboard SHALL render the corresponding bar segment in a distinct color (e.g., amber) to indicate context compression occurred.

---

### Requirement 6: Engram Knowledge Graph Visualization

**User Story:** As a developer, I want to visualize the Engram knowledge graph, so that I can inspect what the agents have learned and stored in memory.

#### Acceptance Criteria

1. THE Backend SHALL expose `GET /api/engram/nodes` returning a JSON array of node descriptors, each containing: `id` (string), `content` (string, truncated to 200 characters), `tier` (WORKING / EPISODIC / SEMANTIC), `importance` (double 0–1), and `topicKey` (string).
2. THE Backend SHALL expose `GET /api/engram/nodes/{id}` returning the full content of a single MemoryObject without truncation.
3. THE Backend SHALL expose `DELETE /api/engram/nodes/{id}` to remove a MemoryObject from the EngramEngine store.
4. THE Engram_Panel SHALL render the knowledge graph as an interactive force-directed graph where each node is a circle sized proportionally to its `importance` value and colored by `tier` (WORKING = blue, EPISODIC = green, SEMANTIC = orange).
5. WHEN the user clicks a node in the Engram_Panel, THE Frontend SHALL display a detail drawer showing the full node content, tier, importance, and topicKey.
6. THE Engram_Panel SHALL provide a search input that filters visible nodes to those whose `content` or `topicKey` contains the search string, with filtering applied within 300 milliseconds of the last keystroke.
7. THE Engram_Panel SHALL provide a "Refresh" button that calls `GET /api/engram/nodes` and re-renders the graph.
8. WHEN the user clicks "Delete" in the node detail drawer, THE Frontend SHALL call `DELETE /api/engram/nodes/{id}` and remove the node from the graph without a full page reload.

---

### Requirement 7: Natural Language to Loom Script Generation

**User Story:** As a developer, I want to describe a project idea in natural language and have the IDE generate a Loom script for me, so that I can bootstrap workflows quickly.

#### Acceptance Criteria

1. THE Backend SHALL expose `POST /api/generate/loom` accepting a JSON body `{"prompt": "<natural language description>", "mockMode": <boolean>}` and returning `{"script": "<generated Loom DSL text>", "workflowName": "<detected primary workflow name>"}`.
2. THE Loom_Generator SHALL construct a system prompt instructing the LLM to produce a syntactically valid Loom_Script containing at least one `agent` block and one `workflow` block.
3. WHEN `mockMode` is `true`, THE Loom_Generator SHALL return a deterministic template Loom_Script without calling an external LLM, so that the feature is usable without API keys.
4. WHEN the user submits a prompt in the Chat_Panel, THE Frontend SHALL call `POST /api/generate/loom`, display a loading spinner for the duration of the request, and on success load the returned script into the Editor.
5. THE Chat_Panel SHALL retain a scrollable history of all submitted prompts and their generation outcomes (success or error message) within the current browser session.
6. IF `POST /api/generate/loom` returns an HTTP error, THEN THE Frontend SHALL display the error message in the Chat_Panel history without clearing the prompt input.
7. THE Chat_Panel SHALL provide a "Use in Editor" button next to each successful generation in the history, allowing the user to load a previously generated script into the Editor at any time.

---

### Requirement 8: Backend API Expansion

**User Story:** As a developer, I want a well-structured and consistent REST API, so that the frontend and any future clients can reliably interact with all IDE features.

#### Acceptance Criteria

1. THE Backend SHALL return all API responses with `Content-Type: application/json` and include a top-level `error` field (string) in all 4xx and 5xx responses.
2. THE Backend SHALL enable CORS for `http://localhost:5173` and `http://localhost:3000` by default, with the allowed origins configurable via the `tantrik.console.cors.origins` application property.
3. THE Backend SHALL expose `GET /api/health` returning `{"status": "UP", "version": "<application version>"}` with HTTP 200.
4. WHEN a request body fails JSON deserialization, THE Backend SHALL return HTTP 400 with an `error` field describing the malformed field.
5. THE Backend SHALL log all unhandled exceptions at ERROR level including the request URI, HTTP method, and exception message, without exposing stack traces in the HTTP response body.
6. THE Backend SHALL support a `tantrik.console.loom-scripts.dir` application property that overrides the default Loom script root directory, allowing deployment-time configuration without code changes.

---

### Requirement 9: Frontend Application Shell

**User Story:** As a developer, I want a polished, production-quality frontend shell, so that the IDE feels professional and is easy to navigate.

#### Acceptance Criteria

1. THE Frontend SHALL be built with React 18 and Vite, using TypeScript for all new source files.
2. THE Frontend SHALL use a component library (Radix UI primitives or equivalent) for accessible dialog, tooltip, and dropdown components, ensuring all interactive elements have ARIA labels.
3. THE Frontend SHALL display a loading skeleton in each panel while its initial data fetch is in progress.
4. THE Frontend SHALL display a global toast notification system for transient messages (save confirmations, errors, run status changes) that auto-dismisses after 4 seconds.
5. WHEN the browser loses network connectivity, THE Frontend SHALL display a persistent "Offline" banner and disable the Start Run and Generate buttons until connectivity is restored.
6. THE Frontend SHALL include a keyboard shortcut reference accessible via `?` key or a help icon, listing all available shortcuts.
7. THE Frontend SHALL be deployable as a static build (`npm run build`) that can be served from the Spring Boot server's `static` resources directory.
