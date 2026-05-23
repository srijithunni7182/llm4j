# Tantrik Console UI

React 18 + TypeScript + Vite frontend for the Tantrik IDE Console. A full IDE-like web application for authoring Loom workflow scripts and observing their execution.

---

## Running

```bash
# Install dependencies (first time only)
npm install

# Development server with hot-reload (http://localhost:5173)
npm run dev

# Production build (outputs to ../tantrik-console-server/src/main/resources/static)
npm run build

# Run tests
npm test
```

The dev server proxies nothing — it talks directly to the Spring Boot backend at `http://localhost:8090`. Make sure the backend is running before using the IDE.

---

## Tech Stack

| Library | Version | Purpose |
|---------|---------|---------|
| React | 18.3.1 | UI framework |
| TypeScript | 5.6.3 | Type safety |
| Vite | 5.4.8 | Build tool + dev server |
| Zustand | 5.0.1 | Global state management |
| `@monaco-editor/react` | 4.6.0 | Code editor |
| `react-resizable-panels` | 2.1.4 | Resizable panel layout |
| `recharts` | 2.13.0 | Token consumption charts |
| `d3-force` | 3.0.0 | Engram force-directed graph |
| `@radix-ui/*` | various | Accessible UI primitives |
| Vitest | 2.1.4 | Unit testing |

---

## Project Structure

```
src/
├── App.tsx                     # Root shell — wires all panels together
├── main.tsx                    # React entry point
├── styles.css                  # Global dark theme CSS variables
├── types/
│   └── index.ts                # All TypeScript interfaces
├── stores/
│   ├── useEditorStore.ts       # Editor state (file, content, dirty flag)
│   └── useRunStore.ts          # Run state (active run, events, history)
└── components/
    ├── PanelLayout.tsx          # Resizable 3-panel layout
    ├── TopBar.tsx               # Title, Start/Stop Run, tab switcher
    ├── TabContext.tsx           # Shared tab state (token/engram/chat)
    ├── ToastProvider.tsx        # Global toast notifications
    ├── OfflineBanner.tsx        # Offline detection + context
    ├── KeyboardShortcutModal.tsx # ? key → shortcut reference
    ├── FileTree.tsx             # Left sidebar file browser
    ├── LoomEditor.tsx           # Monaco editor + Loom syntax + save
    ├── ExecutionPanel.tsx       # SSE trace viewer
    ├── TokenDashboard.tsx       # Token consumption bar chart
    ├── EngramPanel.tsx          # Force-directed knowledge graph
    ├── NodeDetailDrawer.tsx     # Engram node detail + delete
    └── ChatPanel.tsx            # Natural-language → Loom generation
```

---

## IDE Features

### Panel Layout

The IDE uses a three-column resizable layout:

```
┌─────────────┬──────────────────────────┬──────────────────┐
│  File Tree  │  Monaco Editor           │  Token Dashboard │
│  (left)     │  ─────────────────────── │  or              │
│             │  Execution Trace         │  Engram Graph    │
│             │  (below editor)          │  or Chat Panel   │
└─────────────┴──────────────────────────┴──────────────────┘
```

- Drag dividers to resize panels
- Sizes are persisted in `localStorage` under key `tantrik-panel-sizes`
- File Tree auto-collapses on viewports narrower than 900px

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+S` / `Cmd+S` | Save current file |
| `Ctrl+Z` | Undo |
| `Ctrl+Y` / `Ctrl+Shift+Z` | Redo |
| `Ctrl+F` | Find |
| `Shift+Alt+F` | Format document |
| `?` | Show keyboard shortcut reference |

### Loom DSL Syntax Highlighting

The Monaco editor registers a custom `loom` language with token rules for all DSL keywords:

`agent` · `workflow` · `delegate` · `parallel` · `loop` · `handoff` · `broadcast` · `observe` · `guardrail` · `note` · `alt`

### Mock Mode

All run requests are sent with `mockMode: true` by default. This means the Tantrik executor returns deterministic mock LLM responses — no API keys needed. Uncheck Mock Mode in the TopBar (coming in a future update) to use real LLMs.

---

## State Management

Two Zustand stores manage global state:

### `useEditorStore`

```typescript
{
  currentFile: FileDescriptor | null  // currently open file
  content: string                     // editor content
  savedContent: string                // last saved content
  isDirty: boolean                    // content !== savedContent

  setContent(content: string): void
  setSavedContent(content: string): void
  loadFile(file: FileDescriptor, content: string): void
}
```

### `useRunStore`

```typescript
{
  activeRunId: string | null   // currently running run ID
  runs: RunSummary[]           // run history (up to 50)
  events: RunEvent[]           // SSE events for the active run

  startRun(runId: string): void
  stopRun(): void
  selectRun(runId: string): void
  addRun(run: RunSummary): void
  addEvent(event: RunEvent): void
  updateRunStatus(runId: string, status: RunSummary['status']): void
}
```

---

## TypeScript Types

All API response shapes are defined in `src/types/index.ts`:

```typescript
FileDescriptor    // { path, name, lastModified }
RunSummary        // { runId, status, startedAt, completedAt?, workflowName, error? }
RunEvent          // { type, message, executionTier, timestamp, metadata }
TokenBreakdown    // { totalInputTokens, agentBreakdown, runDurationMs }
AgentTokenStat    // { agentName, inputTokens, squeezedCount, avgCompressionRatio }
EngramNode        // { id, content, tier, importance, topicKey }
ChatMessage       // { id, prompt, script?, workflowName?, error?, timestamp }
```

---

## Building for Production

```bash
npm run build
```

The build outputs to `../tantrik-console-server/src/main/resources/static/`. The Spring Boot server's `SpaController` then serves `index.html` for all non-API routes, enabling HTML5 history-mode routing.

The TypeScript compiler runs first (`tsc`) to catch type errors before Vite bundles the output.

---

## Environment

The API base URL is hardcoded to `http://localhost:8090` in the component files. To change it for a different deployment, search for `API_BASE` across the `src/` directory and update accordingly. A future improvement would be to move this to a Vite environment variable.
