import { useCallback, useEffect, useRef, useState } from "react";
import {
  forceCenter,
  forceCollide,
  forceManyBody,
  forceSimulation,
  SimulationNodeDatum,
} from "d3-force";
import type { EngramNode } from "../types";
import NodeDetailDrawer from "./NodeDetailDrawer";

const API_BASE = "";

// ── tier colors ───────────────────────────────────────────────────────────────
const TIER_COLOR: Record<EngramNode["tier"], string> = {
  WORKING: "#3b82f6",
  EPISODIC: "#22c55e",
  SEMANTIC: "#f97316",
};

// ── radius helpers ────────────────────────────────────────────────────────────
const MIN_RADIUS = 8;
const MAX_RADIUS = 24;

function nodeRadius(importance: number): number {
  // importance is 0–1; clamp to [0,1] defensively
  const clamped = Math.max(0, Math.min(1, importance));
  return MIN_RADIUS + clamped * (MAX_RADIUS - MIN_RADIUS);
}

// ── simulation node type ──────────────────────────────────────────────────────
interface SimNode extends SimulationNodeDatum {
  id: string;
  data: EngramNode;
  r: number;
}

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

// ── main component ────────────────────────────────────────────────────────────
export default function EngramPanel() {
  const [nodes, setNodes] = useState<EngramNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Task 15.2: search / filter
  const [searchQuery, setSearchQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Task 15.3: selected node for drawer
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  // SVG container ref for measuring available size
  const svgContainerRef = useRef<HTMLDivElement>(null);
  const [svgSize, setSvgSize] = useState({ width: 600, height: 400 });

  // Simulation positions — stored in state so SVG re-renders on tick
  const [simNodes, setSimNodes] = useState<SimNode[]>([]);
  const simulationRef = useRef<ReturnType<typeof forceSimulation<SimNode>> | null>(null);

  // ── fetch nodes ─────────────────────────────────────────────────────────────
  const fetchNodes = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/engram/nodes`);
      if (!res.ok) {
        let errMsg = `HTTP ${res.status}`;
        try {
          const json = await res.json();
          if (json.error) errMsg = json.error;
        } catch {
          // ignore
        }
        setError(errMsg);
        setNodes([]);
      } else {
        const data: EngramNode[] = await res.json();
        setNodes(data);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      setError(msg);
      setNodes([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch on mount
  useEffect(() => {
    fetchNodes();
  }, [fetchNodes]);

  // ── measure SVG container ───────────────────────────────────────────────────
  useEffect(() => {
    const el = svgContainerRef.current;
    if (!el) return;

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect;
        if (width > 0 && height > 0) {
          setSvgSize({ width, height });
        }
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // ── debounce search query ───────────────────────────────────────────────────
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setDebouncedQuery(searchQuery);
    }, 300);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [searchQuery]);

  // ── run d3-force simulation whenever nodes or SVG size changes ──────────────
  useEffect(() => {
    // Stop any previous simulation
    if (simulationRef.current) {
      simulationRef.current.stop();
    }

    if (nodes.length === 0) {
      setSimNodes([]);
      return;
    }

    const { width, height } = svgSize;

    // Build simulation nodes, preserving existing positions if available
    const existingById = new Map<string, SimNode>(
      simNodes.map((n) => [n.id, n])
    );

    const newSimNodes: SimNode[] = nodes.map((node) => {
      const existing = existingById.get(node.id);
      return {
        id: node.id,
        data: node,
        r: nodeRadius(node.importance),
        x: existing?.x ?? width / 2 + (Math.random() - 0.5) * 100,
        y: existing?.y ?? height / 2 + (Math.random() - 0.5) * 100,
        vx: existing?.vx ?? 0,
        vy: existing?.vy ?? 0,
      };
    });

    const sim = forceSimulation<SimNode>(newSimNodes)
      .force("center", forceCenter(width / 2, height / 2))
      .force("charge", forceManyBody<SimNode>().strength(-120))
      .force(
        "collide",
        forceCollide<SimNode>().radius((d) => d.r + 6)
      )
      .alphaDecay(0.03)
      .on("tick", () => {
        // Clamp nodes within SVG bounds
        for (const n of newSimNodes) {
          n.x = Math.max(n.r, Math.min(width - n.r, n.x ?? width / 2));
          n.y = Math.max(n.r + 14, Math.min(height - n.r, n.y ?? height / 2));
        }
        setSimNodes([...newSimNodes]);
      });

    simulationRef.current = sim;

    return () => {
      sim.stop();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, svgSize]);

  // ── filter nodes for display ────────────────────────────────────────────────
  const matchesSearch = useCallback(
    (node: EngramNode): boolean => {
      if (!debouncedQuery.trim()) return true;
      const q = debouncedQuery.toLowerCase();
      return (
        node.content.toLowerCase().includes(q) ||
        node.topicKey.toLowerCase().includes(q)
      );
    },
    [debouncedQuery]
  );

  // ── handle node deletion from drawer ───────────────────────────────────────
  const handleNodeDeleted = useCallback((deletedId: string) => {
    setNodes((prev) => prev.filter((n) => n.id !== deletedId));
    setSelectedNodeId(null);
  }, []);

  // ── render ──────────────────────────────────────────────────────────────────
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
          Engram Graph
        </span>

        {/* Task 15.2: Search input */}
        <input
          type="search"
          aria-label="Search nodes by content or topic key"
          placeholder="Search nodes…"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          style={{
            flex: 1,
            background: "#2d2d2d",
            color: "#cccccc",
            border: "1px solid #3c3c3c",
            borderRadius: "4px",
            padding: "3px 8px",
            fontSize: "12px",
            outline: "none",
            minWidth: 0,
          }}
        />

        {/* Refresh button */}
        <button
          aria-label="Refresh engram nodes"
          onClick={fetchNodes}
          disabled={loading}
          style={{
            background: "#2d2d2d",
            color: "#cccccc",
            border: "1px solid #3c3c3c",
            borderRadius: "4px",
            padding: "3px 10px",
            fontSize: "12px",
            cursor: loading ? "not-allowed" : "pointer",
            whiteSpace: "nowrap",
            opacity: loading ? 0.6 : 1,
          }}
        >
          {loading ? "Loading…" : "Refresh"}
        </button>
      </div>

      {/* Body */}
      <div
        ref={svgContainerRef}
        style={{ flex: 1, overflow: "hidden", position: "relative" }}
      >
        {/* Loading skeleton */}
        {loading && (
          <div
            role="status"
            aria-label="Loading engram nodes"
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              flexDirection: "column",
              gap: "12px",
              padding: "24px",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <Skeleton width="60%" height={16} />
            <Skeleton width="40%" height={16} />
            <Skeleton width="50%" height={16} />
          </div>
        )}

        {/* Error message */}
        {error && !loading && (
          <div
            role="alert"
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              padding: "24px",
            }}
          >
            <div
              style={{
                background: "#450a0a",
                color: "#fca5a5",
                border: "1px solid #7f1d1d",
                borderRadius: "6px",
                padding: "10px 14px",
                fontSize: "13px",
                maxWidth: "400px",
                textAlign: "center",
              }}
            >
              Failed to load engram nodes: {error}
            </div>
          </div>
        )}

        {/* Empty state */}
        {!loading && !error && nodes.length === 0 && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "#4b5563",
              fontSize: "13px",
            }}
          >
            No engram nodes found. Run a workflow to populate the graph.
          </div>
        )}

        {/* Force-directed graph */}
        {!loading && !error && simNodes.length > 0 && (
          <svg
            width={svgSize.width}
            height={svgSize.height}
            aria-label="Engram knowledge graph"
            style={{ display: "block" }}
          >
            {simNodes.map((sn) => {
              const matches = matchesSearch(sn.data);
              const cx = sn.x ?? svgSize.width / 2;
              const cy = sn.y ?? svgSize.height / 2;
              const color = TIER_COLOR[sn.data.tier] ?? "#6b7280";

              return (
                <g
                  key={sn.id}
                  transform={`translate(${cx},${cy})`}
                  style={{
                    cursor: "pointer",
                    opacity: matches ? 1 : 0.15,
                    transition: "opacity 0.2s ease",
                  }}
                  onClick={() => setSelectedNodeId(sn.id)}
                  role="button"
                  aria-label={`Node: ${sn.data.topicKey}`}
                >
                  <circle
                    r={sn.r}
                    fill={color}
                    stroke={selectedNodeId === sn.id ? "#ffffff" : "transparent"}
                    strokeWidth={2}
                    style={{ transition: "stroke 0.15s ease" }}
                  />
                  <text
                    y={sn.r + 12}
                    textAnchor="middle"
                    fill="#cccccc"
                    fontSize={10}
                    style={{ pointerEvents: "none", userSelect: "none" }}
                  >
                    {sn.data.topicKey.length > 16
                      ? sn.data.topicKey.slice(0, 14) + "…"
                      : sn.data.topicKey}
                  </text>
                </g>
              );
            })}
          </svg>
        )}
      </div>

      {/* Tier legend */}
      {!loading && !error && nodes.length > 0 && (
        <div
          style={{
            display: "flex",
            gap: "16px",
            padding: "6px 12px",
            background: "#252526",
            borderTop: "1px solid #3c3c3c",
            flexShrink: 0,
            fontSize: "11px",
            color: "#9ca3af",
          }}
        >
          {(["WORKING", "EPISODIC", "SEMANTIC"] as const).map((tier) => (
            <span
              key={tier}
              style={{ display: "flex", alignItems: "center", gap: "4px" }}
            >
              <span
                style={{
                  width: "10px",
                  height: "10px",
                  borderRadius: "50%",
                  background: TIER_COLOR[tier],
                  display: "inline-block",
                  flexShrink: 0,
                }}
              />
              {tier}
            </span>
          ))}
        </div>
      )}

      {/* Task 15.3: Node detail drawer */}
      <NodeDetailDrawer
        nodeId={selectedNodeId}
        onClose={() => setSelectedNodeId(null)}
        onDeleted={handleNodeDeleted}
      />

      {/* Shimmer keyframe */}
      <style>{`
        @keyframes shimmer {
          0%   { background-position: 200% 0; }
          100% { background-position: -200% 0; }
        }
      `}</style>
    </div>
  );
}
