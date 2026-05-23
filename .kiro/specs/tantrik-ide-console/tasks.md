# Implementation Plan: Tantrik IDE Console

## Overview

Expand the existing minimal Tantrik Console MVP into a full IDE-like web application. The backend (Spring Boot) gains new controllers for files, Engram nodes, token aggregation, Loom generation, and health, plus cancellation support and a run-history ring buffer. The frontend is migrated from plain JS to React 18 + TypeScript + Vite and gains a multi-panel layout with Monaco editor, live execution trace, token dashboard, Engram graph, and a chat/generate panel.

## Tasks

- [x] 1. Expand backend configuration and cross-cutting infrastructure
  - Add `tantrik.console.cors.origins`, `tantrik.console.loom-scripts.dir`, and `tantrik.console.engram.storage-path` properties to `application.properties`
  - Replace the existing `WebConfig` with a new `CorsConfig` bean that reads allowed origins from the property (default `http://localhost:5173,http://localhost:3000`)
  - Create `GlobalExceptionHandler` (`@ControllerAdvice`) that catches `MethodArgumentNotValidException` (400), `NoSuchElementException` (404), and `Exception` (500), logs URI + method + message at ERROR level, and returns `{"error": "<message>"}` — no stack traces in the response body
  - Add `HealthController` at `GET /api/health` returning `{"status": "UP", "version": "<version>"}` read from `application.properties` or the Maven manifest
  - Add `ErrorResponse` record and ensure all 4xx/5xx responses include the `error` field
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

- [x] 2. Implement file management backend
  - [x] 2.1 Create `FileDescriptor` record (`path`, `name`, `lastModified`)
    - _Requirements: 2.1_
  - [x] 2.2 Create `FileService` that scans the configured root directory recursively for `.loom` files, resolves paths safely (reject path traversal with HTTP 400), and supports read and write operations
    - _Requirements: 2.2, 2.3, 2.4, 8.6_
  - [x] 2.3 Create `FileController` with `GET /api/files`, `GET /api/files/{encodedPath}`, and `PUT /api/files/{encodedPath}` endpoints wired to `FileService`
    - _Requirements: 2.1, 2.3, 2.4_
  - [x] 2.4 Write unit tests for `FileService` path traversal rejection and file listing
    - Test that paths resolving outside the root return HTTP 400
    - Test recursive `.loom` file discovery
    - _Requirements: 2.2_

- [x] 3. Implement Engram node management backend
  - [x] 3.1 Create `EngramNodeDescriptor` record (`id`, `content` truncated to 200 chars, `tier`, `importance`, `topicKey`)
    - _Requirements: 6.1_
  - [x] 3.2 Create `EngramService` that wraps the `EngramEngine` Spring bean, maintains a secondary `ConcurrentHashMap<String, MemoryObject>` index keyed by `id`, and exposes list, get-by-id, and delete-by-id operations
    - _Requirements: 6.1, 6.2, 6.3_
  - [x] 3.3 Create `EngramController` with `GET /api/engram/nodes`, `GET /api/engram/nodes/{id}`, and `DELETE /api/engram/nodes/{id}` endpoints wired to `EngramService`
    - _Requirements: 6.1, 6.2, 6.3_
  - [x] 3.4 Write unit tests for `EngramService` content truncation and delete-by-id
    - Test that listed node content is truncated to 200 characters
    - Test that deleting a node removes it from the secondary index
    - _Requirements: 6.1, 6.3_

- [x] 4. Implement token aggregation backend
  - [x] 4.1 Create `TokenBreakdown` and `AgentTokenStat` records
    - _Requirements: 5.1_
  - [x] 4.2 Create `TokenAggregationService` that iterates `RunSummary.events`, filters `TRACE_PRE_TURN` events, parses agent name from the `message` field, and aggregates `inputTokensEstimate`, `squeezedCount`, and `avgCompressionRatio` per agent
    - _Requirements: 5.1_
  - [x] 4.3 Create `TokenController` with `GET /api/runs/{runId}/tokens` wired to `TokenAggregationService`
    - _Requirements: 5.1_
  - [x] 4.4 Write unit tests for `TokenAggregationService` aggregation logic
    - Test correct per-agent grouping from a list of synthetic `RunEvent` objects
    - Test `runDurationMs` calculation from `startedAt` / `completedAt`
    - _Requirements: 5.1_

- [x] 5. Extend `TantrikRunService` with cancellation and run-history ring buffer
  - Replace the `ConcurrentHashMap<String, RunSummary>` with a `LinkedHashMap`-backed ring buffer capped at 50 entries (access-order eviction)
  - Add a `CancellationToken` flag per run; the trace monitor thread checks it and interrupts itself when set
  - Add `cancelRun(String runId)` method that sets the flag and marks the `RunSummary` status as `CANCELLED`
  - Add `DELETE /api/runs/{runId}` endpoint to `TantrikConsoleController` that delegates to `cancelRun`
  - Ensure `GET /api/runs` returns all buffered runs (up to 50)
  - _Requirements: 4.6, 4.7, 4.8_

- [x] 6. Implement Loom script generation backend
  - [x] 6.1 Create `GenerateLoomRequest` record (`prompt`, `mockMode`) and `GenerateLoomResponse` record (`script`, `workflowName`)
    - _Requirements: 7.1_
  - [x] 6.2 Create `LoomGenerateService` with a mock path (returns a deterministic template Loom script with at least one `agent` block and one `workflow` block when `mockMode=true`) and an LLM path (builds a system prompt and calls the configured LLM client)
    - _Requirements: 7.2, 7.3_
  - [x] 6.3 Create `LoomGenerateController` with `POST /api/generate/loom` wired to `LoomGenerateService`
    - _Requirements: 7.1_
  - [x] 6.4 Write unit tests for `LoomGenerateService` mock mode
    - Test that mock mode returns a non-empty script containing `agent` and `workflow` keywords
    - Test that mock mode never calls the LLM client
    - _Requirements: 7.3_

- [x] 7. Checkpoint — Ensure all backend tests pass
  - Run `mvn test` in `examples/tantrik-console/tantrik-console-server`; ask the user if any failures arise.

- [x] 8. Migrate frontend to React 18 + TypeScript + Vite
  - Update `package.json` to add TypeScript, `@types/react`, `@types/react-dom`, `@monaco-editor/react`, `react-resizable-panels`, `recharts`, `d3-force`, `@types/d3-force`, `zustand`, `@radix-ui/react-toast`, `@radix-ui/react-dialog`, `@radix-ui/react-tooltip`, `@radix-ui/react-tabs`, `@radix-ui/react-sheet` (or equivalent), and `vitest` for testing
  - Add `tsconfig.json` and `vite.config.ts` configured for React + TypeScript
  - Delete `App.jsx` and create `App.tsx` as the new shell entry point (renders `TopBar`, `PanelLayout`, `ToastProvider`, `OfflineBanner`, `KeyboardShortcutModal`)
  - Create `src/types/index.ts` with all TypeScript interfaces: `FileDescriptor`, `RunSummary`, `RunEvent`, `TokenBreakdown`, `AgentTokenStat`, `EngramNode`, `ChatMessage`
  - _Requirements: 9.1, 9.2_

- [x] 9. Implement Zustand stores
  - [x] 9.1 Create `src/stores/useEditorStore.ts` with state: `currentFile`, `content`, `savedContent`, `isDirty` (derived), and actions `setContent`, `setSavedContent`, `loadFile`
    - _Requirements: 3.3, 2.7_
  - [x] 9.2 Create `src/stores/useRunStore.ts` with state: `activeRunId`, `runs`, `events`, and actions `startRun`, `stopRun`, `selectRun`
    - _Requirements: 4.1, 4.7_

- [x] 10. Implement the IDE panel layout shell
  - [x] 10.1 Create `PanelLayout.tsx` using `react-resizable-panels` (`PanelGroup`, `Panel`, `PanelResizeHandle`); persist sizes to `localStorage` under key `tantrik-panel-sizes`; auto-collapse `FileTree` panel when viewport width < 900 px
    - _Requirements: 1.1, 1.2, 1.6_
  - [x] 10.2 Create `TopBar.tsx` with the application title "Tantrik IDE", Start Run / Stop Run buttons wired to `useRunStore`, and a tab bar (Radix UI `Tabs`) for switching the secondary panel between Token Dashboard, Engram Panel, and Chat Panel
    - _Requirements: 1.4_
  - [x] 10.3 Apply the Dark_Theme globally via CSS variables: background ≤ `#1e1e1e`, foreground ≥ `#cccccc`; add responsive collapse toggle button for the File Tree when viewport < 900 px
    - _Requirements: 1.3, 1.5_
  - [x] 10.4 Create `ToastProvider.tsx` using Radix UI `Toast`; auto-dismiss after 4 seconds; expose a `useToast` hook for other components
    - _Requirements: 9.4_
  - [x] 10.5 Create `OfflineBanner.tsx` that listens to `navigator.onLine` / `online` / `offline` events and renders a persistent banner; disable Start Run and Generate buttons via context when offline
    - _Requirements: 9.5_
  - [x] 10.6 Create `KeyboardShortcutModal.tsx` (Radix UI `Dialog`) triggered by the `?` key listing all available shortcuts
    - _Requirements: 9.6_

- [x] 11. Implement the File Tree panel
  - [x] 11.1 Create `FileTree.tsx` that fetches `GET /api/files` on mount, renders a list of file descriptors, shows a dirty indicator (dot) for files with unsaved changes from `useEditorStore`, and displays an inline error message if the fetch fails
    - _Requirements: 2.5, 2.7, 2.8_
  - [x] 11.2 Add animated collapse/expand behavior to `FileTree.tsx` via CSS transition on panel width, toggled by a button; wire the collapse toggle to the `PanelLayout` collapse state
    - _Requirements: 2.6_
  - [x] 11.3 Add loading skeleton to `FileTree.tsx` while the initial `GET /api/files` fetch is in progress
    - _Requirements: 9.3_

- [x] 12. Implement the Monaco Loom Script Editor
  - [x] 12.1 Create `LoomEditor.tsx` wrapping `@monaco-editor/react`; register a custom `loom` language with token rules for DSL keywords (`agent`, `workflow`, `delegate`, `parallel`, `loop`, `handoff`, `broadcast`, `observe`, `guardrail`, `note`, `alt`); enable line numbers, minimap, and bracket matching
    - _Requirements: 3.1, 3.4_
  - [x] 12.2 Bind `Ctrl+S` / `Cmd+S` in `LoomEditor.tsx` to call `PUT /api/files/{encodedPath}` with current content; show a "Saved" toast on success (2 s) and an error toast with HTTP status on failure; update `savedContent` in `useEditorStore` on success
    - _Requirements: 3.2, 3.3, 3.6_
  - [x] 12.3 Add a status bar below the editor that parses the editor content client-side with a regex and displays detected agent names and workflow names
    - _Requirements: 3.5_
  - [x] 12.4 Wire `FileTree.tsx` file-click handler to call `GET /api/files/{encodedPath}` and load the content into `useEditorStore`; show a loading skeleton in the editor while loading
    - _Requirements: 2.5, 9.3_

- [x] 13. Implement the Execution Panel
  - [x] 13.1 Create `ExecutionPanel.tsx` that manages an `EventSource` lifecycle; renders each SSE event as a row with timestamp, event type badge, agent name, tier badge (`LOCAL` / `CLOUD` / `SYSTEM`), and token estimate from `metadata.inputTokensEstimate`
    - _Requirements: 4.2_
  - [x] 13.2 Add auto-scroll behavior to `ExecutionPanel.tsx` with a "Pause scroll" toggle button that freezes the view at the current position
    - _Requirements: 4.5_
  - [x] 13.3 Display a green "Completed" banner on `RUN_COMPLETED` and a red "Failed" banner with error message on `RUN_FAILED`; close the `EventSource` in both cases
    - _Requirements: 4.3, 4.4_
  - [x] 13.4 Wire the "Start Run" button in `TopBar.tsx` to call `POST /api/runs` with the current editor script, selected workflow name, and `mockMode` flag, then open the SSE stream for the returned `runId`
    - _Requirements: 4.1_
  - [x] 13.5 Wire the "Stop Run" button in `TopBar.tsx` to call `DELETE /api/runs/{runId}` and display a "Cancelled" status in `ExecutionPanel.tsx`
    - _Requirements: 4.7_

- [x] 14. Implement the Token Dashboard panel
  - [x] 14.1 Create `TokenDashboard.tsx` that fetches `GET /api/runs/{runId}/tokens` when a run is selected; renders a horizontal bar chart using `recharts` `BarChart` with `layout="vertical"` (agent names on Y-axis, token counts on X-axis)
    - _Requirements: 5.2_
  - [x] 14.2 Color bar segments amber where `squeezedCount > 0` for the corresponding agent; display a summary row with total tokens, squeezed turn count, and average compression ratio
    - _Requirements: 5.3, 5.6_
  - [x] 14.3 Subscribe to incoming SSE events in `TokenDashboard.tsx` and re-fetch token data with a 1-second debounce while a run is in progress
    - _Requirements: 5.4_
  - [x] 14.4 Add a run history selector to `TokenDashboard.tsx` that lists all runs from `useRunStore` and allows the user to select any run to display its token breakdown
    - _Requirements: 5.5_

- [x] 15. Implement the Engram Knowledge Graph panel
  - [x] 15.1 Create `EngramPanel.tsx` that fetches `GET /api/engram/nodes` on mount and on "Refresh" button click; renders a force-directed graph using `d3-force` where each node is a circle sized proportionally to `importance` and colored by tier (WORKING = `#3b82f6`, EPISODIC = `#22c55e`, SEMANTIC = `#f97316`)
    - _Requirements: 6.4, 6.7_
  - [x] 15.2 Add a search input to `EngramPanel.tsx` that filters visible nodes to those whose `content` or `topicKey` contains the search string, with filtering applied within 300 ms of the last keystroke (debounce)
    - _Requirements: 6.6_
  - [x] 15.3 Create `NodeDetailDrawer.tsx` (Radix UI `Sheet` or `Dialog`) that opens when a node is clicked, showing full node content, tier, importance, and topicKey; include a "Delete" button that calls `DELETE /api/engram/nodes/{id}` and removes the node from local state without a page reload
    - _Requirements: 6.5, 6.8_

- [x] 16. Implement the Chat / Generate panel
  - [x] 16.1 Create `ChatPanel.tsx` with a prompt input form that calls `POST /api/generate/loom` on submit; show a loading spinner during the request; on success, append the result to the scrollable message history
    - _Requirements: 7.4, 7.5_
  - [x] 16.2 Add a "Use in Editor" button next to each successful generation in the `ChatPanel.tsx` history that pushes the script into `useEditorStore`
    - _Requirements: 7.7_
  - [x] 16.3 Display error messages inline in the `ChatPanel.tsx` history on `POST /api/generate/loom` failure without clearing the prompt input
    - _Requirements: 7.6_

- [x] 17. Add loading skeletons to all panels
  - Add loading skeleton components to `TokenDashboard.tsx`, `EngramPanel.tsx`, and `ChatPanel.tsx` while their initial data fetches are in progress
  - Ensure all interactive elements (buttons, inputs, dialogs) have ARIA labels using Radix UI primitives
  - _Requirements: 9.2, 9.3_

- [ ] 18. Checkpoint — Ensure all frontend tests pass
  - Run `npm run test -- --run` in `examples/tantrik-console/tantrik-console-ui`; ask the user if any failures arise.

- [x] 19. Wire frontend static build into Spring Boot
  - Configure `vite.config.ts` to output the build to `../tantrik-console-server/src/main/resources/static`
  - Create `SpaController.java` in the server that catches all non-`/api/**` GET requests and returns `index.html` for client-side routing
  - Verify `npm run build` produces a deployable bundle and the Spring Boot server serves it correctly
  - _Requirements: 9.7_

- [x] 20. Final checkpoint — End-to-end integration
  - Ensure all backend tests pass (`mvn test`)
  - Ensure the frontend builds without TypeScript errors (`npm run build`)
  - Ask the user if any questions arise before closing out.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Each task references specific requirements for traceability
- Checkpoints (tasks 7, 18, 20) ensure incremental validation at natural boundaries
- The backend ring buffer (task 5) must be in place before the frontend run-history selector (task 14.4) is implemented
- The Zustand stores (task 9) must be in place before any panel component that reads from them (tasks 11–16)
