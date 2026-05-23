import { useEffect, useRef, useState } from "react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  Cell,
  ResponsiveContainer,
} from "recharts";
import { useRunStore } from "../stores/useRunStore";
import type { TokenBreakdown, AgentTokenStat } from "../types";

const API_BASE = "";

// ── color constants ───────────────────────────────────────────────────────────
const COLOR_SQUEEZED = "#f59e0b"; // amber — context compression occurred
const COLOR_NORMAL = "#3b82f6";   // blue  — no compression

// ── loading skeleton ──────────────────────────────────────────────────────────
function Skeleton({ width, height }: { width: string | number; height: number }) {
  return (
    <div
      aria-hidden="true"
      style={{
        width,
        height,
        background: "linear-gradient(90deg, #2a2a2a 25%, #333 50%, #2a2a2a 75%)",
        backgroundSize: "200% 100%",
        borderRadius: "4px",
        animation: "shimmer 1.4s infinite",
      }}
    />
  );
}

// ── custom tooltip ────────────────────────────────────────────────────────────
interface TooltipPayloadEntry {
  payload: AgentTokenStat;
}

function CustomTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: TooltipPayloadEntry[];
}) {
  if (!active || !payload || payload.length === 0) return null;
  const stat = payload[0].payload;
  return (
    <div
      style={{
        background: "#1e1e1e",
        border: "1px solid #3c3c3c",
        borderRadius: "6px",
        padding: "8px 12px",
        fontSize: "12px",
        color: "#cccccc",
      }}
    >
      <div style={{ fontWeight: 600, marginBottom: "4px" }}>{stat.agentName}</div>
      <div>Tokens: {stat.inputTokens.toLocaleString()}</div>
      {stat.squeezedCount > 0 && (
        <>
          <div style={{ color: COLOR_SQUEEZED }}>
            Squeezed turns: {stat.squeezedCount}
          </div>
          <div>Avg compression: {(stat.avgCompressionRatio * 100).toFixed(1)}%</div>
        </>
      )}
    </div>
  );
}

// ── main component ────────────────────────────────────────────────────────────
export default function TokenDashboard() {
  // ── Task 14.4: run history selector ──────────────────────────────────────
  const activeRunId = useRunStore((s) => s.activeRunId);
  const runs = useRunStore((s) => s.runs);
  const events = useRunStore((s) => s.events);

  // Selected run defaults to activeRunId when a run is active
  const [selectedRunId, setSelectedRunId] = useState<string | null>(
    activeRunId
  );

  // Keep selectedRunId in sync with activeRunId when a new run starts
  useEffect(() => {
    if (activeRunId !== null) {
      setSelectedRunId(activeRunId);
    }
  }, [activeRunId]);

  // ── Task 14.1: fetch token data ───────────────────────────────────────────
  const [breakdown, setBreakdown] = useState<TokenBreakdown | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchTokens = async (runId: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/runs/${runId}/tokens`);
      if (!res.ok) {
        let errMsg = `HTTP ${res.status}`;
        try {
          const json = await res.json();
          if (json.error) errMsg = json.error;
        } catch {
          // ignore
        }
        setError(errMsg);
        setBreakdown(null);
      } else {
        const data: TokenBreakdown = await res.json();
        setBreakdown(data);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      setError(msg);
      setBreakdown(null);
    } finally {
      setLoading(false);
    }
  };

  // Fetch when selectedRunId changes
  useEffect(() => {
    if (selectedRunId) {
      fetchTokens(selectedRunId);
    } else {
      setBreakdown(null);
      setError(null);
    }
  }, [selectedRunId]);

  // ── Task 14.3: real-time updates via debounced re-fetch ───────────────────
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // Only re-fetch while a run is in progress
    if (activeRunId === null || selectedRunId === null) return;

    // Clear any pending debounce
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }

    debounceRef.current = setTimeout(() => {
      fetchTokens(selectedRunId);
    }, 1000);

    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events]); // re-run whenever a new SSE event arrives

  // ── derived summary values (Task 14.2) ────────────────────────────────────
  const totalTokens = breakdown?.totalInputTokens ?? 0;
  const totalSqueezed =
    breakdown?.agentBreakdown.reduce((sum, a) => sum + a.squeezedCount, 0) ?? 0;
  const avgCompression =
    breakdown && breakdown.agentBreakdown.length > 0
      ? breakdown.agentBreakdown.reduce(
          (sum, a) => sum + a.avgCompressionRatio,
          0
        ) / breakdown.agentBreakdown.length
      : 0;

  // ── render ────────────────────────────────────────────────────────────────
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
          gap: "8px",
        }}
      >
        <span
          style={{
            fontSize: "12px",
            fontWeight: 600,
            color: "#cccccc",
            textTransform: "uppercase",
            letterSpacing: "0.05em",
            whiteSpace: "nowrap",
          }}
        >
          Token Dashboard
        </span>

        {/* Task 14.4: Run history selector */}
        <select
          aria-label="Select run"
          value={selectedRunId ?? ""}
          onChange={(e) => setSelectedRunId(e.target.value || null)}
          style={{
            background: "#2d2d2d",
            color: "#cccccc",
            border: "1px solid #3c3c3c",
            borderRadius: "4px",
            padding: "3px 8px",
            fontSize: "12px",
            cursor: "pointer",
            maxWidth: "220px",
            flex: 1,
          }}
        >
          <option value="">— Select a run —</option>
          {runs.map((run) => (
            <option key={run.runId} value={run.runId}>
              {run.runId.slice(0, 8)}… ({run.status})
            </option>
          ))}
        </select>
      </div>

      {/* Body */}
      <div
        style={{
          flex: 1,
          overflow: "auto",
          padding: "12px",
          display: "flex",
          flexDirection: "column",
          gap: "12px",
        }}
      >
        {/* No run selected */}
        {!selectedRunId && !loading && (
          <div
            style={{
              color: "#4b5563",
              fontSize: "13px",
              textAlign: "center",
              marginTop: "32px",
            }}
          >
            Select a run above to view its token breakdown.
          </div>
        )}

        {/* Loading skeleton (Task 14.1) */}
        {loading && (
          <div
            role="status"
            aria-label="Loading token data"
            style={{ display: "flex", flexDirection: "column", gap: "8px" }}
          >
            <Skeleton width="100%" height={20} />
            <Skeleton width="80%" height={20} />
            <Skeleton width="90%" height={20} />
            <Skeleton width="70%" height={20} />
            <Skeleton width="85%" height={20} />
          </div>
        )}

        {/* Error message (Task 14.1) */}
        {error && !loading && (
          <div
            role="alert"
            style={{
              background: "#450a0a",
              color: "#fca5a5",
              border: "1px solid #7f1d1d",
              borderRadius: "6px",
              padding: "10px 14px",
              fontSize: "13px",
            }}
          >
            Failed to load token data: {error}
          </div>
        )}

        {/* Chart (Tasks 14.1 + 14.2) */}
        {!loading && !error && breakdown && breakdown.agentBreakdown.length > 0 && (
          <>
            <div
              style={{
                fontSize: "12px",
                color: "#9ca3af",
                marginBottom: "4px",
              }}
            >
              Input tokens per agent
            </div>
            <div
              style={{
                width: "100%",
                height: Math.max(120, breakdown.agentBreakdown.length * 40),
              }}
            >
              <ResponsiveContainer width="100%" height="100%">
                {/* layout="vertical" → agent names on Y-axis, token counts on X-axis */}
                <BarChart
                  layout="vertical"
                  data={breakdown.agentBreakdown}
                  margin={{ top: 4, right: 16, bottom: 4, left: 8 }}
                >
                  <XAxis
                    type="number"
                    tick={{ fill: "#9ca3af", fontSize: 11 }}
                    axisLine={{ stroke: "#3c3c3c" }}
                    tickLine={{ stroke: "#3c3c3c" }}
                  />
                  <YAxis
                    type="category"
                    dataKey="agentName"
                    width={90}
                    tick={{ fill: "#cccccc", fontSize: 11 }}
                    axisLine={{ stroke: "#3c3c3c" }}
                    tickLine={false}
                  />
                  <Tooltip
                    content={<CustomTooltip />}
                    cursor={{ fill: "#ffffff0a" }}
                  />
                  <Bar dataKey="inputTokens" radius={[0, 3, 3, 0]}>
                    {breakdown.agentBreakdown.map((stat, index) => (
                      <Cell
                        key={`cell-${index}`}
                        fill={stat.squeezedCount > 0 ? COLOR_SQUEEZED : COLOR_NORMAL}
                      />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>

            {/* Color legend */}
            <div
              style={{
                display: "flex",
                gap: "16px",
                fontSize: "11px",
                color: "#9ca3af",
              }}
            >
              <span style={{ display: "flex", alignItems: "center", gap: "4px" }}>
                <span
                  style={{
                    width: "10px",
                    height: "10px",
                    borderRadius: "2px",
                    background: COLOR_NORMAL,
                    display: "inline-block",
                  }}
                />
                Normal
              </span>
              <span style={{ display: "flex", alignItems: "center", gap: "4px" }}>
                <span
                  style={{
                    width: "10px",
                    height: "10px",
                    borderRadius: "2px",
                    background: COLOR_SQUEEZED,
                    display: "inline-block",
                  }}
                />
                Squeezed (context compressed)
              </span>
            </div>

            {/* Task 14.2: Summary row */}
            <div
              style={{
                display: "flex",
                gap: "16px",
                padding: "10px 14px",
                background: "#252526",
                borderRadius: "6px",
                border: "1px solid #3c3c3c",
                fontSize: "12px",
                flexWrap: "wrap",
              }}
            >
              <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                <span style={{ color: "#6b7280", fontSize: "11px" }}>
                  Total Tokens
                </span>
                <span style={{ color: "#cccccc", fontWeight: 600 }}>
                  {totalTokens.toLocaleString()}
                </span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                <span style={{ color: "#6b7280", fontSize: "11px" }}>
                  Squeezed Turns
                </span>
                <span
                  style={{
                    color: totalSqueezed > 0 ? COLOR_SQUEEZED : "#cccccc",
                    fontWeight: 600,
                  }}
                >
                  {totalSqueezed}
                </span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: "2px" }}>
                <span style={{ color: "#6b7280", fontSize: "11px" }}>
                  Avg Compression
                </span>
                <span style={{ color: "#cccccc", fontWeight: 600 }}>
                  {avgCompression > 0
                    ? `${(avgCompression * 100).toFixed(1)}%`
                    : "—"}
                </span>
              </div>
              {breakdown.runDurationMs > 0 && (
                <div
                  style={{ display: "flex", flexDirection: "column", gap: "2px" }}
                >
                  <span style={{ color: "#6b7280", fontSize: "11px" }}>
                    Run Duration
                  </span>
                  <span style={{ color: "#cccccc", fontWeight: 600 }}>
                    {(breakdown.runDurationMs / 1000).toFixed(2)}s
                  </span>
                </div>
              )}
            </div>
          </>
        )}

        {/* Empty state — run selected but no agent data yet */}
        {!loading &&
          !error &&
          breakdown &&
          breakdown.agentBreakdown.length === 0 && (
            <div
              style={{
                color: "#4b5563",
                fontSize: "13px",
                textAlign: "center",
                marginTop: "32px",
              }}
            >
              No token data available for this run yet.
            </div>
          )}
      </div>

      {/* Shimmer keyframe — injected once via a style tag */}
      <style>{`
        @keyframes shimmer {
          0%   { background-position: 200% 0; }
          100% { background-position: -200% 0; }
        }
      `}</style>
    </div>
  );
}
