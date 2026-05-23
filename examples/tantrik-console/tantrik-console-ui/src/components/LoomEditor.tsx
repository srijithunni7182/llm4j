import { useRef, useCallback } from "react";
import Editor, { OnMount, BeforeMount } from "@monaco-editor/react";
import type * as Monaco from "monaco-editor";
import { useEditorStore } from "../stores/useEditorStore";
import { useToast } from "./ToastProvider";

const API_BASE = "";

const LOOM_KEYWORDS = [
  "agent",
  "workflow",
  "delegate",
  "parallel",
  "loop",
  "handoff",
  "broadcast",
  "observe",
  "guardrail",
  "note",
  "alt",
];

// ── Loom language registration ────────────────────────────────────────────────

function registerLoomLanguage(monaco: typeof Monaco) {
  // Only register once
  const existing = monaco.languages.getLanguages().find((l) => l.id === "loom");
  if (existing) return;

  monaco.languages.register({ id: "loom", extensions: [".loom"] });

  monaco.languages.setMonarchTokensProvider("loom", {
    keywords: LOOM_KEYWORDS,
    tokenizer: {
      root: [
        // Keywords
        [
          new RegExp(`\\b(${LOOM_KEYWORDS.join("|")})\\b`),
          { cases: { "@keywords": "keyword" } },
        ],
        // Identifiers
        [/[a-zA-Z_]\w*/, "identifier"],
        // Strings
        [/"([^"\\]|\\.)*"/, "string"],
        [/'([^'\\]|\\.)*'/, "string"],
        // Numbers
        [/\d+(\.\d+)?/, "number"],
        // Comments
        [/#.*$/, "comment"],
        // Operators / punctuation
        [/[{}()\[\]]/, "delimiter"],
        [/[=:,;]/, "delimiter"],
        // Whitespace
        [/\s+/, "white"],
      ],
    },
  });

  monaco.editor.defineTheme("loom-dark", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "keyword", foreground: "569cd6", fontStyle: "bold" },
      { token: "identifier", foreground: "9cdcfe" },
      { token: "string", foreground: "ce9178" },
      { token: "number", foreground: "b5cea8" },
      { token: "comment", foreground: "6a9955", fontStyle: "italic" },
      { token: "delimiter", foreground: "d4d4d4" },
    ],
    colors: {},
  });
}

// ── Status bar ────────────────────────────────────────────────────────────────

function parseAgentsAndWorkflows(content: string): {
  agents: string[];
  workflows: string[];
} {
  const agents: string[] = [];
  const workflows: string[] = [];

  const agentRegex = /^agent\s+(\w+)/gm;
  const workflowRegex = /^workflow\s+(\w+)/gm;

  let match: RegExpExecArray | null;

  while ((match = agentRegex.exec(content)) !== null) {
    agents.push(match[1]);
  }
  while ((match = workflowRegex.exec(content)) !== null) {
    workflows.push(match[1]);
  }

  return { agents, workflows };
}

function StatusBar({ content }: { content: string }) {
  const { agents, workflows } = parseAgentsAndWorkflows(content);

  const agentText = agents.length > 0 ? agents.join(", ") : "None";
  const workflowText = workflows.length > 0 ? workflows.join(", ") : "None";

  return (
    <div
      style={{
        height: "22px",
        background: "var(--statusbar-bg, #007acc)",
        display: "flex",
        alignItems: "center",
        padding: "0 12px",
        gap: "16px",
        flexShrink: 0,
        fontSize: "12px",
        color: "rgba(255,255,255,0.8)",
        userSelect: "none",
        overflow: "hidden",
        whiteSpace: "nowrap",
      }}
      aria-label="Editor status bar"
    >
      <span>
        <span style={{ opacity: 0.7 }}>Agents: </span>
        <span>{agentText}</span>
      </span>
      <span style={{ opacity: 0.4 }}>|</span>
      <span>
        <span style={{ opacity: 0.7 }}>Workflows: </span>
        <span>{workflowText}</span>
      </span>
    </div>
  );
}

// ── Loading skeleton ──────────────────────────────────────────────────────────

function EditorSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading editor"
      style={{
        flex: 1,
        background: "var(--editor-bg, #1e1e1e)",
        display: "flex",
        flexDirection: "column",
        gap: "8px",
        padding: "16px",
        overflow: "hidden",
      }}
    >
      <style>{`
        @keyframes editor-skeleton-shimmer {
          0%   { opacity: 1; }
          50%  { opacity: 0.3; }
          100% { opacity: 1; }
        }
      `}</style>
      {Array.from({ length: 12 }).map((_, i) => (
        <div
          key={i}
          aria-hidden="true"
          style={{
            height: "14px",
            borderRadius: "3px",
            background: "var(--skeleton-base, #3a3a3a)",
            width: `${40 + ((i * 37) % 55)}%`,
            animation: "editor-skeleton-shimmer 1.4s ease-in-out infinite",
            animationDelay: `${i * 0.05}s`,
          }}
        />
      ))}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

interface LoomEditorProps {
  loading?: boolean;
}

export default function LoomEditor({ loading = false }: LoomEditorProps) {
  const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<typeof Monaco | null>(null);
  // Always-current ref so the Monaco action closure never goes stale
  const handleSaveRef = useRef<() => Promise<void>>(async () => {});

  const content = useEditorStore((s) => s.content);
  const currentFile = useEditorStore((s) => s.currentFile);
  const setContent = useEditorStore((s) => s.setContent);
  const setSavedContent = useEditorStore((s) => s.setSavedContent);

  const { showToast } = useToast();

  // ── Save handler ────────────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    if (!currentFile) {
      showToast("No file selected", "info");
      return;
    }

    const currentContent =
      editorRef.current?.getValue() ?? content;
    const encodedPath = encodeURIComponent(currentFile.path);

    try {
      const res = await fetch(`${API_BASE}/api/files/content?path=${encodedPath}`, {
        method: "PUT",
        headers: { "Content-Type": "text/plain" },
        body: currentContent,
      });

      if (!res.ok) {
        showToast(`Save failed: HTTP ${res.status}`, "error");
        return;
      }

      setSavedContent(currentContent);
      showToast("Saved", "success", undefined, 2000);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      showToast(`Save failed: ${msg}`, "error");
    }
  }, [currentFile, content, setSavedContent, showToast]);

  // Keep the ref in sync with the latest callback
  handleSaveRef.current = handleSave;

  // ── Monaco before-mount: register language ──────────────────────────────────

  const handleBeforeMount: BeforeMount = (monaco) => {
    monacoRef.current = monaco;
    registerLoomLanguage(monaco);
  };

  // ── Monaco on-mount: add Ctrl+S / Cmd+S action ──────────────────────────────

  const handleMount: OnMount = (editor, monaco) => {
    editorRef.current = editor;
    monacoRef.current = monaco;

    editor.addAction({
      id: "editor.action.save",
      label: "Save File",
      keybindings: [
        monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS,
      ],
      run: () => {
        handleSaveRef.current();
      },
    });
  };

  // ── Render ──────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          height: "100%",
          overflow: "hidden",
        }}
      >
        <EditorSkeleton />
        <StatusBar content="" />
      </div>
    );
  }

  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        height: "100%",
        overflow: "hidden",
      }}
    >
      <div style={{ flex: 1, overflow: "hidden" }}>
        <Editor
          height="100%"
          language="loom"
          theme="loom-dark"
          value={content}
          beforeMount={handleBeforeMount}
          onMount={handleMount}
          onChange={(value) => setContent(value ?? "")}
          options={{
            lineNumbers: "on",
            minimap: { enabled: true },
            matchBrackets: "always",
            fontSize: 14,
            fontFamily: "'Cascadia Code', 'Fira Code', 'Consolas', monospace",
            scrollBeyondLastLine: false,
            wordWrap: "on",
            automaticLayout: true,
            tabSize: 2,
            renderWhitespace: "selection",
          }}
          loading={<EditorSkeleton />}
        />
      </div>
      <StatusBar content={content} />
    </div>
  );
}
