import { useEffect, useRef, useState } from "react";
import { useRunStore } from "../stores/useRunStore";
import { dlog } from "../debug";
import type { RunEvent } from "../types";

const API_BASE = "";

// ── helpers ──────────────────────────────────────────────────────────────────

function formatTimestamp(iso: string): string {
  try {
    const d = new Date(iso);
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    const ss = String(d.getSeconds()).padStart(2, "0");
    return `${hh}:${mm}:${ss}`;
  } catch {
    return "--:--:--";
  }
}

/** Parse agent name from message like "Agent Foo phase bar" */
function parseAgentName(message: string): string {
  const m = message.match(/^Agent\s+(\S+)/i);
  return m ? m[1] : "";
}

const TIER_COLORS: Record<string, { bg: string; fg: string }> = {
  LOCAL: { bg: "#166534", fg: "#86efac" },
  CLOUD: { bg: "#1e3a5f", fg: "#93c5fd" },
  SYSTEM: { bg: "#374151", fg: "#9ca3af" },
};

const EVENT_TYPE_COLORS: Record<string, string> = {
  RUN_STARTED: "#2563eb",
  TRACE_PRE_TURN: "#7c3aed",
  TRACE_POST_TURN: "#0891b2",
  RUN_COMPLETED: "#16a34a",
  RUN_FAILED: "#dc2626",
  RUN_CANCELLED: "#d97706",
};

// ── sub-components ────────────────────────────────────────────────────────────

interface EventRowProps {
  event: RunEvent;
}

function EventRow({ event }: EventRowProps) {
  const agentName = parseAgentName(event.message);
  const tier = event.executionTier ?? "SYSTEM";
  const tierStyle = TIER_COLORS[tier] ?? TIER_COLORS.SYSTEM;
  const typeColor = EVENT_TYPE_COLORS[event.type] ?? "#6b7280";
  const tokenEstimate = event.metadata?.inputTokensEstimate as number | undefined;

  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: "8px",
        padding: "4px 10px",
        borderBottom: "1px solid #2a2a2a",
        fontSize: "12px",
        fontFamily: "monospace",
        flexShrink: 0,
      }}
    >
      {/* Timestamp */}
      <span style={{ color: "#6b7280", minWidth: "60px", flexShrink: 0 }}>
        {formatTimestamp(event.timestamp)}
      </span>

      {/* Event type badge */}
      <span
        style={{
          background: typeColor + "22",
          color: typeColor,
          border: `1px solid ${typeColor}55`,
          borderRadius: "4px",
          padding: "1px 6px",
          fontSize: "11px",
          fontWeight: 600,
          minWidth: "120px",
          textAlign: "center",
          flexShrink: 0,
        }}
      >
        {event.type}
      </span>

      {/* Agent name */}
      {agentName && (
        <span style={{ color: "#e2e8f0", minWidth: "80px", flexShrink: 0 }}>
          {agentName}
        </span>
      )}

      {/* Message */}
      <span
        style={{
          color: "#9ca3af",
          flex: 1,
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
        }}
      >
        {event.message}
      </span>

      {/* Tier badge */}
      <span
        style={{
          background: tierStyle.bg,
          color: tierStyle.fg,
          borderRadius: "4px",
          padding: "1px 6px",
          fontSize: "11px",
          fontWeight: 600,
          flexShrink: 0,
        }}
      >
        {tier}
      </span>

      {/* Token estimate */}
      {tokenEstimate !== undefined && tokenEstimate > 0 && (
        <span
          style={{
            color: "#f59e0b",
            fontSize: "11px",
            minWidth: "60px",
            textAlign: "right",
            flexShrink: 0,
          }}
        >
          ~{tokenEstimate}t
        </span>
      )}
    </div>
  );
}

// ── main component ────────────────────────────────────────────────────────────

type RunStatus = "idle" | "running" | "completed" | "failed" | "cancelled";

export default function ExecutionPanel() {
  const activeRunId = useRunStore((s) => s.activeRunId);
  const events = useRunStore((s) => s.events);
  const addEvent = useRunStore((s) => s.addEvent);
  const stopRun = useRunStore((s) => s.stopRun);
  const updateRunStatus = useRunStore((s) => s.updateRunStatus);

  const [runStatus, setRunStatus] = useState<RunStatus>("idle");
  const [failedMessage, setFailedMessage] = useState<string>("");
  const [pauseScroll, setPauseScroll] = useState(false);

  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const eventSourceRef = useRef<EventSource | null>(null);

  // Mount/unmount logging
  useEffect(() => {
    dlog('ExecutionPanel', 'MOUNTED');
    return () => dlog('ExecutionPanel', 'UNMOUNTED');
  }, []);

  // ── SSE lifecycle ──────────────────────────────────────────────────────────
  useEffect(() => {
    dlog('ExecutionPanel', 'SSE useEffect fired', { activeRunId });
    // Close any existing connection
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }

    if (!activeRunId) {
      // No active run — reset to idle only if we were running
      setRunStatus((prev) =>
        prev === "running" ? "idle" : prev
      );
      return;
    }

    // New run started — reset state
    setRunStatus("running");
    setFailedMessage("");
    setPauseScroll(false);

    const url = `${API_BASE}/api/runs/${activeRunId}/stream`;
    const es = new EventSource(url);
    eventSourceRef.current = es;

    es.onmessage = (e) => {
      try {
        const event: RunEvent = JSON.parse(e.data);
        addEvent(event);

        if (event.type === "RUN_COMPLETED") {
          setRunStatus("completed");
          if (activeRunId) updateRunStatus(activeRunId, "SUCCESS");
          es.close();
          eventSourceRef.current = null;
          stopRun();
        } else if (event.type === "RUN_FAILED") {
          const errMsg =
            (event.metadata?.error as string) ||
            event.message ||
            "Unknown error";
          setFailedMessage(errMsg);
          setRunStatus("failed");
          if (activeRunId) updateRunStatus(activeRunId, "FAILED");
          es.close();
          eventSourceRef.current = null;
          stopRun();
        } else if (event.type === "RUN_CANCELLED") {
          setRunStatus("cancelled");
          if (activeRunId) updateRunStatus(activeRunId, "CANCELLED");
          es.close();
          eventSourceRef.current = null;
          stopRun();
        }
      } catch {
        // ignore parse errors
      }
    };

    es.onerror = () => {
      // Connection dropped — mark as failed if still running
      setRunStatus((prev) => (prev === "running" ? "failed" : prev));
      setFailedMessage("SSE connection lost");
      es.close();
      eventSourceRef.current = null;
    };

    return () => {
      es.close();
      eventSourceRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeRunId]);

  // ── auto-scroll ────────────────────────────────────────────────────────────
  useEffect(() => {
    if (!pauseScroll && bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [events, pauseScroll]);

  // ── status banner ──────────────────────────────────────────────────────────
  const renderBanner = () => {
    if (runStatus === "completed") {
      return (
        <div
          role="status"
          aria-live="polite"
          style={{
            background: "#14532d",
            color: "#86efac",
            padding: "8px 16px",
            fontSize: "13px",
            fontWeight: 600,
            borderTop: "1px solid #166534",
            flexShrink: 0,
          }}
        >
          ✓ Completed
        </div>
      );
    }
    if (runStatus === "failed") {
      return (
        <div
          role="alert"
          aria-live="assertive"
          style={{
            background: "#450a0a",
            color: "#fca5a5",
            padding: "8px 16px",
            fontSize: "13px",
            fontWeight: 600,
            borderTop: "1px solid #7f1d1d",
            flexShrink: 0,
          }}
        >
          ✗ Failed: {failedMessage}
        </div>
      );
    }
    if (runStatus === "cancelled") {
      return (
        <div
          role="status"
          aria-live="polite"
          style={{
            background: "#451a03",
            color: "#fcd34d",
            padding: "8px 16px",
            fontSize: "13px",
            fontWeight: 600,
            borderTop: "1px solid #78350f",
            flexShrink: 0,
          }}
        >
          ⊘ Cancelled
        </div>
      );
    }
    return null;
  };

  // ── render ─────────────────────────────────────────────────────────────────
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
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
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
          Execution Trace
          {activeRunId && (
            <span
              style={{
                marginLeft: "8px",
                color: "#6b7280",
                fontWeight: 400,
                textTransform: "none",
                letterSpacing: 0,
              }}
            >
              {activeRunId}
            </span>
          )}
        </span>

        {/* Pause / Resume scroll toggle */}
        <button
          onClick={() => setPauseScroll((p) => !p)}
          aria-label={pauseScroll ? "Resume scroll" : "Pause scroll"}
          style={{
            background: pauseScroll ? "#374151" : "transparent",
            color: pauseScroll ? "#f59e0b" : "#6b7280",
            border: `1px solid ${pauseScroll ? "#f59e0b" : "#3c3c3c"}`,
            borderRadius: "4px",
            padding: "3px 10px",
            fontSize: "12px",
            cursor: "pointer",
            transition: "all 0.15s",
          }}
        >
          {pauseScroll ? "▶ Resume scroll" : "⏸ Pause scroll"}
        </button>
      </div>

      {/* Event list */}
      <div
        ref={scrollContainerRef}
        style={{
          flex: 1,
          overflowY: "auto",
          overflowX: "hidden",
        }}
      >
        {events.length === 0 && (
          <div
            style={{
              padding: "24px 16px",
              color: "#4b5563",
              fontSize: "13px",
              textAlign: "center",
            }}
          >
            {activeRunId
              ? "Waiting for events…"
              : "No active run. Click ▶ Start Run to begin."}
          </div>
        )}

        {events.map((event, idx) => (
          <EventRow key={`${event.timestamp}-${idx}`} event={event} />
        ))}

        {/* Scroll anchor */}
        <div ref={bottomRef} />
      </div>

      {/* Status banner (pinned to bottom) */}
      {renderBanner()}
    </div>
  );
}
