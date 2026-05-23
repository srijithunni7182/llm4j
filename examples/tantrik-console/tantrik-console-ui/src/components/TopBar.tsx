import { useState } from "react";
import * as Tabs from "@radix-ui/react-tabs";
import { useRunStore } from "../stores/useRunStore";
import { useEditorStore } from "../stores/useEditorStore";
import { useOnlineStatus } from "./OfflineBanner";
import { useTabContext, SecondaryTab } from "./TabContext";
import { useToast } from "./ToastProvider";
import WorkflowLibrary from "./WorkflowLibrary";
import type { RunSummary } from "../types";

const API_BASE = "";

const TABS: { value: SecondaryTab; label: string }[] = [
  { value: "token-dashboard", label: "Token Dashboard" },
  { value: "engram", label: "Engram" },
  { value: "chat", label: "Chat" },
];

export default function TopBar() {
  const activeRunId = useRunStore((s) => s.activeRunId);
  const startRun = useRunStore((s) => s.startRun);
  const stopRun = useRunStore((s) => s.stopRun);
  const addRun = useRunStore((s) => s.addRun);
  const isOnline = useOnlineStatus();
  const { activeTab, setActiveTab } = useTabContext();
  const { showToast } = useToast();

  const content = useEditorStore((s) => s.content);
  const currentFile = useEditorStore((s) => s.currentFile);

  const [libraryOpen, setLibraryOpen] = useState(false);

  const canStart = activeRunId === null && isOnline;
  const canStop = activeRunId !== null && isOnline;

  // ── Task 13.4: Start Run ──────────────────────────────────────────────────
  const handleStartRun = async () => {
    if (!canStart) return;

    // Derive workflow name from current file (strip .loom extension) or default
    const workflowName = currentFile
      ? currentFile.name.replace(/\.loom$/i, "")
      : "Main";

    const body = {
      script: content,
      workflowName,
      mockMode: true,
      initialContext: {},
    };

    try {
      const res = await fetch(`${API_BASE}/api/runs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        let errMsg = `HTTP ${res.status}`;
        try {
          const json = await res.json();
          if (json.error) errMsg = json.error;
        } catch {
          // ignore
        }
        showToast(`Failed to start run: ${errMsg}`, "error");
        return;
      }

      const run: RunSummary = await res.json();
      startRun(run.runId);
      addRun(run);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      showToast(`Failed to start run: ${msg}`, "error");
    }
  };

  // ── Task 13.5: Stop Run ───────────────────────────────────────────────────
  const handleStopRun = async () => {
    if (!canStop || !activeRunId) return;

    try {
      const res = await fetch(`${API_BASE}/api/runs/${activeRunId}`, {
        method: "DELETE",
      });

      if (!res.ok) {
        let errMsg = `HTTP ${res.status}`;
        try {
          const json = await res.json();
          if (json.error) errMsg = json.error;
        } catch {
          // ignore
        }
        showToast(`Failed to stop run: ${errMsg}`, "error");
        return;
      }

      // The RUN_CANCELLED SSE event will arrive via ExecutionPanel and call stopRun()
      // but we also call it here as a safety net in case the SSE stream is already closed
      stopRun();
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      showToast(`Failed to stop run: ${msg}`, "error");
    }
  };

  return (
    <>
    <header
      style={{
        display: "flex",
        alignItems: "center",
        gap: "16px",
        padding: "0 16px",
        height: "48px",
        background: "var(--topbar-bg, #252526)",
        borderBottom: "1px solid var(--border, #3c3c3c)",
        flexShrink: 0,
        zIndex: 100,
      }}
    >
      {/* App title */}
      <span
        style={{
          fontWeight: 700,
          fontSize: "15px",
          color: "var(--fg, #cccccc)",
          letterSpacing: "0.02em",
          whiteSpace: "nowrap",
        }}
      >
        Tantrik IDE
      </span>

      {/* Workflow Library button */}
      <button
        onClick={() => setLibraryOpen(true)}
        aria-label="Open workflow library"
        title="Browse pre-canned workflows"
        style={{
          background: "#2d2d2d",
          color: "#9ca3af",
          border: "1px solid #3c3c3c",
          borderRadius: "5px",
          padding: "4px 10px",
          fontSize: "12px",
          fontWeight: 500,
          cursor: "pointer",
          display: "flex",
          alignItems: "center",
          gap: "5px",
          whiteSpace: "nowrap",
          transition: "background 0.15s, color 0.15s",
        }}
        onMouseEnter={(e) => {
          (e.currentTarget as HTMLButtonElement).style.background = "#3c3c3c";
          (e.currentTarget as HTMLButtonElement).style.color = "#cccccc";
        }}
        onMouseLeave={(e) => {
          (e.currentTarget as HTMLButtonElement).style.background = "#2d2d2d";
          (e.currentTarget as HTMLButtonElement).style.color = "#9ca3af";
        }}
      >
        📚 Workflows
      </button>

      {/* Run controls */}
      <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
        <button
          onClick={handleStartRun}
          disabled={!canStart}
          aria-label="Start Run"
          style={{
            background: canStart ? "#16a34a" : "#374151",
            color: canStart ? "#fff" : "#6b7280",
            border: "none",
            borderRadius: "5px",
            padding: "5px 12px",
            fontSize: "13px",
            fontWeight: 500,
            cursor: canStart ? "pointer" : "not-allowed",
            transition: "background 0.15s",
          }}
        >
          ▶ Start Run
        </button>
        <button
          onClick={handleStopRun}
          disabled={!canStop}
          aria-label="Stop Run"
          style={{
            background: canStop ? "#dc2626" : "#374151",
            color: canStop ? "#fff" : "#6b7280",
            border: "none",
            borderRadius: "5px",
            padding: "5px 12px",
            fontSize: "13px",
            fontWeight: 500,
            cursor: canStop ? "pointer" : "not-allowed",
            transition: "background 0.15s",
          }}
        >
          ■ Stop Run
        </button>
      </div>

      {/* Spacer */}
      <div style={{ flex: 1 }} />

      {/* Secondary panel tab switcher */}
      <Tabs.Root
        value={activeTab}
        onValueChange={(v) => setActiveTab(v as SecondaryTab)}
      >
        <Tabs.List
          aria-label="Secondary panel"
          style={{
            display: "flex",
            gap: "2px",
            background: "#1e1e1e",
            borderRadius: "6px",
            padding: "2px",
          }}
        >
          {TABS.map((tab) => (
            <Tabs.Trigger
              key={tab.value}
              value={tab.value}
              style={{
                background: activeTab === tab.value ? "#3c3c3c" : "transparent",
                color: activeTab === tab.value ? "#cccccc" : "#888",
                border: "none",
                borderRadius: "4px",
                padding: "4px 12px",
                fontSize: "13px",
                cursor: "pointer",
                transition: "background 0.15s, color 0.15s",
                whiteSpace: "nowrap",
              }}
            >
              {tab.label}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
      </Tabs.Root>
    </header>

    {/* Workflow Library dialog — rendered outside <header> to avoid stacking context issues */}
    <WorkflowLibrary open={libraryOpen} onClose={() => setLibraryOpen(false)} />
    </>
  );
}
