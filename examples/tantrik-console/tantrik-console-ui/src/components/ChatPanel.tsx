import { useRef, useState } from "react";
import type { ChatMessage } from "../types";
import { useEditorStore } from "../stores/useEditorStore";
import { useOnlineStatus } from "./OfflineBanner";

const API_BASE = "";

// ── loading spinner ───────────────────────────────────────────────────────────
function Spinner() {
  return (
    <span
      role="status"
      aria-label="Generating…"
      style={{
        display: "inline-block",
        width: "14px",
        height: "14px",
        border: "2px solid #4b5563",
        borderTopColor: "#60a5fa",
        borderRadius: "50%",
        animation: "spin 0.7s linear infinite",
        flexShrink: 0,
      }}
    />
  );
}

// ── single history entry ──────────────────────────────────────────────────────
interface HistoryEntryProps {
  message: ChatMessage;
  onUseInEditor: (script: string) => void;
}

function HistoryEntry({ message, onUseInEditor }: HistoryEntryProps) {
  const isError = Boolean(message.error);

  return (
    <div
      style={{
        borderRadius: "6px",
        border: `1px solid ${isError ? "#7f1d1d" : "#3c3c3c"}`,
        background: isError ? "#1c0a0a" : "#252526",
        padding: "10px 12px",
        display: "flex",
        flexDirection: "column",
        gap: "8px",
        fontSize: "13px",
      }}
    >
      {/* Prompt row */}
      <div style={{ display: "flex", gap: "8px", alignItems: "flex-start" }}>
        <span
          style={{
            color: "#9ca3af",
            fontSize: "11px",
            fontWeight: 600,
            textTransform: "uppercase",
            letterSpacing: "0.05em",
            whiteSpace: "nowrap",
            paddingTop: "1px",
          }}
        >
          You
        </span>
        <span style={{ color: "#cccccc", flex: 1, wordBreak: "break-word" }}>
          {message.prompt}
        </span>
        <span
          style={{
            color: "#4b5563",
            fontSize: "11px",
            whiteSpace: "nowrap",
            paddingTop: "1px",
          }}
        >
          {new Date(message.timestamp).toLocaleTimeString()}
        </span>
      </div>

      {/* Error result */}
      {isError && (
        <div
          role="alert"
          style={{
            background: "#450a0a",
            color: "#fca5a5",
            border: "1px solid #7f1d1d",
            borderRadius: "4px",
            padding: "8px 10px",
            fontSize: "12px",
            wordBreak: "break-word",
          }}
        >
          {message.error}
        </div>
      )}

      {/* Success result */}
      {!isError && message.script && (
        <>
          {/* Workflow name */}
          {message.workflowName && (
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: "6px",
                fontSize: "12px",
                color: "#9ca3af",
              }}
            >
              <span
                style={{
                  background: "#1d4ed8",
                  color: "#bfdbfe",
                  borderRadius: "3px",
                  padding: "1px 6px",
                  fontSize: "11px",
                  fontWeight: 600,
                }}
              >
                workflow
              </span>
              <span>{message.workflowName}</span>
            </div>
          )}

          {/* Generated script */}
          <pre
            style={{
              background: "#1a1a1a",
              border: "1px solid #3c3c3c",
              borderRadius: "4px",
              padding: "8px 10px",
              margin: 0,
              fontSize: "12px",
              color: "#d4d4d4",
              overflowX: "auto",
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
              maxHeight: "240px",
              overflowY: "auto",
            }}
          >
            <code>{message.script}</code>
          </pre>

          {/* Task 16.2: Use in Editor button */}
          <div style={{ display: "flex", justifyContent: "flex-end" }}>
            <button
              aria-label={`Use generated script "${message.workflowName ?? "script"}" in editor`}
              onClick={() => onUseInEditor(message.script!)}
              style={{
                background: "#1d4ed8",
                color: "#eff6ff",
                border: "none",
                borderRadius: "4px",
                padding: "4px 12px",
                fontSize: "12px",
                cursor: "pointer",
                fontWeight: 500,
              }}
            >
              Use in Editor
            </button>
          </div>
        </>
      )}
    </div>
  );
}

// ── main component ────────────────────────────────────────────────────────────
export default function ChatPanel() {
  const [prompt, setPrompt] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);

  const isOnline = useOnlineStatus();
  const setContent = useEditorStore((s) => s.setContent);
  const historyEndRef = useRef<HTMLDivElement>(null);

  // Scroll history to bottom after new message
  const scrollToBottom = () => {
    setTimeout(() => {
      historyEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }, 50);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = prompt.trim();
    if (!trimmed || loading) return;

    setLoading(true);

    try {
      const res = await fetch(`${API_BASE}/api/generate/loom`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: trimmed, mockMode: true }),
      });

      if (!res.ok) {
        let errMsg = `HTTP ${res.status}`;
        try {
          const json = await res.json();
          if (json.error) errMsg = json.error;
        } catch {
          // ignore parse error
        }
        // Task 16.3: append error entry, do NOT clear prompt
        setMessages((prev) => [
          ...prev,
          {
            id: crypto.randomUUID(),
            prompt: trimmed,
            error: errMsg,
            timestamp: new Date().toISOString(),
          },
        ]);
        scrollToBottom();
        return;
      }

      const data: { script: string; workflowName: string } = await res.json();

      // Clear prompt only on success
      setPrompt("");

      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          prompt: trimmed,
          script: data.script,
          workflowName: data.workflowName,
          timestamp: new Date().toISOString(),
        },
      ]);
      scrollToBottom();
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      // Task 16.3: append error entry, do NOT clear prompt
      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          prompt: trimmed,
          error: msg,
          timestamp: new Date().toISOString(),
        },
      ]);
      scrollToBottom();
    } finally {
      setLoading(false);
    }
  };

  // Task 16.2: push script into editor store
  const handleUseInEditor = (script: string) => {
    setContent(script);
  };

  const canSubmit = Boolean(prompt.trim()) && !loading && isOnline;

  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        height: "100%",
        background: "#1a1a1a",
        overflow: "hidden",
      }}
    >
      {/* Header */}
      <div
        style={{
          padding: "6px 10px",
          background: "#252526",
          borderBottom: "1px solid #3c3c3c",
          flexShrink: 0,
        }}
      >
        <span
          style={{
            fontSize: "12px",
            fontWeight: 600,
            color: "#cccccc",
            textTransform: "uppercase",
            letterSpacing: "0.05em",
          }}
        >
          Generate Loom Script
        </span>
      </div>

      {/* Message history — scrollable */}
      <div
        role="log"
        aria-label="Generation history"
        aria-live="polite"
        style={{
          flex: 1,
          overflowY: "auto",
          padding: "12px",
          display: "flex",
          flexDirection: "column",
          gap: "10px",
        }}
      >
        {messages.length === 0 && (
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              height: "100%",
              color: "#4b5563",
              fontSize: "13px",
              textAlign: "center",
              padding: "24px",
            }}
          >
            Describe a workflow in natural language and press Generate to create
            a Loom script.
          </div>
        )}

        {messages.map((msg) => (
          <HistoryEntry
            key={msg.id}
            message={msg}
            onUseInEditor={handleUseInEditor}
          />
        ))}

        {/* Loading entry while request is in flight */}
        {loading && (
          <div
            aria-live="polite"
            style={{
              borderRadius: "6px",
              border: "1px solid #3c3c3c",
              background: "#252526",
              padding: "10px 12px",
              display: "flex",
              alignItems: "center",
              gap: "8px",
              fontSize: "13px",
              color: "#9ca3af",
            }}
          >
            <Spinner />
            Generating…
          </div>
        )}

        <div ref={historyEndRef} />
      </div>

      {/* Prompt input form */}
      <form
        onSubmit={handleSubmit}
        style={{
          padding: "10px 12px",
          background: "#252526",
          borderTop: "1px solid #3c3c3c",
          display: "flex",
          flexDirection: "column",
          gap: "8px",
          flexShrink: 0,
        }}
      >
        <textarea
          aria-label="Describe the workflow you want to generate"
          placeholder="Describe a workflow… e.g. 'A pipeline that summarises documents and stores key facts in memory'"
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={(e) => {
            // Ctrl+Enter / Cmd+Enter submits
            if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
              e.preventDefault();
              handleSubmit(e as unknown as React.FormEvent);
            }
          }}
          rows={3}
          style={{
            background: "#2d2d2d",
            color: "#cccccc",
            border: "1px solid #3c3c3c",
            borderRadius: "4px",
            padding: "8px 10px",
            fontSize: "13px",
            resize: "vertical",
            outline: "none",
            fontFamily: "inherit",
            lineHeight: 1.5,
          }}
        />

        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: "8px",
          }}
        >
          {!isOnline && (
            <span
              style={{ fontSize: "12px", color: "#b45309" }}
              role="status"
            >
              Offline — generation unavailable
            </span>
          )}
          {isOnline && (
            <span style={{ fontSize: "11px", color: "#4b5563" }}>
              Ctrl+Enter to submit
            </span>
          )}

          <button
            type="submit"
            aria-label="Generate Loom script"
            disabled={!canSubmit}
            style={{
              background: canSubmit ? "#1d4ed8" : "#374151",
              color: canSubmit ? "#eff6ff" : "#6b7280",
              border: "none",
              borderRadius: "4px",
              padding: "6px 16px",
              fontSize: "13px",
              fontWeight: 500,
              cursor: canSubmit ? "pointer" : "not-allowed",
              display: "flex",
              alignItems: "center",
              gap: "6px",
              transition: "background 0.15s ease",
            }}
          >
            {loading && <Spinner />}
            Generate
          </button>
        </div>
      </form>

      {/* Keyframe for spinner */}
      <style>{`
        @keyframes spin {
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
