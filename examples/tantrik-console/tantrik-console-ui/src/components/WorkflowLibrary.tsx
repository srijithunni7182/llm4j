import { useEffect, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import type { FileDescriptor } from "../types";
import { useEditorStore } from "../stores/useEditorStore";
import { useToast } from "./ToastProvider";

const API_BASE = "";

// ── Workflow metadata ─────────────────────────────────────────────────────────
// Enriches raw FileDescriptor entries with human-readable descriptions.
const WORKFLOW_META: Record<string, { label: string; description: string; badge: string; badgeColor: string }> = {
  "sdlc/autonomous-dev-cycle.loom": {
    label: "Autonomous Development Cycle",
    description: "Full SDLC pipeline: BA → Architect → parallel Test Strategy + Implementation Plan → parallel coding → Build gate → parallel Review + QA → final delivery.",
    badge: "SDLC",
    badgeColor: "#7c3aed",
  },
  "examples/mock-demo.loom": {
    label: "Mock Demo",
    description: "Simple Planner → Executor pipeline. Works with Mock Mode — no API keys needed. Great for testing the IDE.",
    badge: "Demo",
    badgeColor: "#0891b2",
  },
  "examples/research-summarizer.loom": {
    label: "Research & Summarizer",
    description: "A Researcher gathers comprehensive information on a topic, then a Summarizer condenses it into key takeaways.",
    badge: "Research",
    badgeColor: "#16a34a",
  },
  "examples/code-review.loom": {
    label: "Code Review Pipeline",
    description: "Three reviewers run in parallel (correctness, security, performance), then a Consolidator merges findings into a prioritised action list.",
    badge: "Engineering",
    badgeColor: "#d97706",
  },
  "examples/content-pipeline.loom": {
    label: "Content Creation Pipeline",
    description: "Ideate → Write → parallel Edit + SEO optimise. Produces a polished blog post with an SEO brief.",
    badge: "Content",
    badgeColor: "#db2777",
  },
  "examples/data-analysis.loom": {
    label: "Data Analysis Pipeline",
    description: "Analyse raw data → identify statistical patterns → write an executive summary report.",
    badge: "Analytics",
    badgeColor: "#2563eb",
  },
};

function getMeta(path: string) {
  return WORKFLOW_META[path] ?? {
    label: path.split("/").pop()?.replace(".loom", "") ?? path,
    description: "Custom Loom workflow.",
    badge: "Custom",
    badgeColor: "#4b5563",
  };
}

// ── Skeleton row ──────────────────────────────────────────────────────────────
function SkeletonRow() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "6px", padding: "12px 0" }}>
      <div style={{ height: "14px", width: "60%", background: "#3a3a3a", borderRadius: "3px", animation: "shimmer 1.4s infinite" }} />
      <div style={{ height: "11px", width: "85%", background: "#2d2d2d", borderRadius: "3px", animation: "shimmer 1.4s infinite" }} />
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────
interface WorkflowLibraryProps {
  open: boolean;
  onClose: () => void;
}

export default function WorkflowLibrary({ open, onClose }: WorkflowLibraryProps) {
  const [files, setFiles] = useState<FileDescriptor[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadingPath, setLoadingPath] = useState<string | null>(null);

  const loadFile = useEditorStore((s) => s.loadFile);
  const { showToast } = useToast();

  // Fetch file list when dialog opens
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setError(null);

    fetch(`${API_BASE}/api/files`)
      .then(async (res) => {
        if (cancelled) return;
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data: FileDescriptor[] = await res.json();
        if (!cancelled) setFiles(data.filter((f) => f.name.endsWith(".loom")));
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Failed to load workflows");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => { cancelled = true; };
  }, [open]);

  const handleLoad = async (file: FileDescriptor) => {
    setLoadingPath(file.path);
    try {
      const res = await fetch(`${API_BASE}/api/files/content?path=${encodeURIComponent(file.path)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const content = await res.text();
      loadFile(file, content);
      showToast(`Loaded: ${getMeta(file.path).label}`, "success", undefined, 2000);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      showToast(`Failed to load workflow: ${msg}`, "error");
    } finally {
      setLoadingPath(null);
    }
  };

  // Sort: known workflows first (in WORKFLOW_META order), then alphabetical
  const sortedFiles = [...files].sort((a, b) => {
    const aIdx = Object.keys(WORKFLOW_META).indexOf(a.path);
    const bIdx = Object.keys(WORKFLOW_META).indexOf(b.path);
    if (aIdx !== -1 && bIdx !== -1) return aIdx - bIdx;
    if (aIdx !== -1) return -1;
    if (bIdx !== -1) return 1;
    return a.path.localeCompare(b.path);
  });

  return (
    <Dialog.Root open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0,0,0,0.6)",
            zIndex: 200,
          }}
        />
        <Dialog.Content
          aria-describedby="workflow-library-description"
          style={{
            position: "fixed",
            top: "50%",
            left: "50%",
            transform: "translate(-50%, -50%)",
            background: "#1e1e1e",
            border: "1px solid #3c3c3c",
            borderRadius: "10px",
            width: "600px",
            maxWidth: "92vw",
            maxHeight: "80vh",
            display: "flex",
            flexDirection: "column",
            overflow: "hidden",
            zIndex: 201,
            boxShadow: "0 16px 48px rgba(0,0,0,0.7)",
          }}
        >
          {/* Header */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "16px 20px",
              background: "#252526",
              borderBottom: "1px solid #3c3c3c",
              flexShrink: 0,
            }}
          >
            <div>
              <Dialog.Title
                style={{ color: "#cccccc", fontSize: "15px", fontWeight: 700, margin: 0 }}
              >
                Workflow Library
              </Dialog.Title>
              <p
                id="workflow-library-description"
                style={{ color: "#6b7280", fontSize: "12px", margin: "2px 0 0" }}
              >
                Select a pre-canned workflow to load it into the editor
              </p>
            </div>
            <Dialog.Close
              aria-label="Close workflow library"
              style={{
                background: "transparent",
                border: "none",
                color: "#9ca3af",
                cursor: "pointer",
                fontSize: "20px",
                lineHeight: 1,
                padding: "4px 8px",
                borderRadius: "4px",
              }}
            >
              ×
            </Dialog.Close>
          </div>

          {/* Body */}
          <div style={{ flex: 1, overflowY: "auto", padding: "12px 20px" }}>
            {/* Shimmer keyframe */}
            <style>{`
              @keyframes shimmer {
                0%   { opacity: 1; }
                50%  { opacity: 0.4; }
                100% { opacity: 1; }
              }
            `}</style>

            {loading && (
              <div role="status" aria-label="Loading workflows">
                <SkeletonRow />
                <SkeletonRow />
                <SkeletonRow />
                <SkeletonRow />
              </div>
            )}

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
                Failed to load workflow list: {error}
              </div>
            )}

            {!loading && !error && sortedFiles.length === 0 && (
              <div style={{ color: "#4b5563", fontSize: "13px", textAlign: "center", padding: "32px 0" }}>
                No .loom files found in the server's loom-scripts directory.
              </div>
            )}

            {!loading && !error && sortedFiles.map((file) => {
              const meta = getMeta(file.path);
              const isLoading = loadingPath === file.path;

              return (
                <div
                  key={file.path}
                  style={{
                    display: "flex",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                    gap: "12px",
                    padding: "12px 0",
                    borderBottom: "1px solid #2a2a2a",
                  }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    {/* Title row */}
                    <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "4px" }}>
                      <span
                        style={{
                          background: meta.badgeColor + "22",
                          color: meta.badgeColor,
                          border: `1px solid ${meta.badgeColor}44`,
                          borderRadius: "4px",
                          padding: "1px 7px",
                          fontSize: "10px",
                          fontWeight: 700,
                          textTransform: "uppercase",
                          letterSpacing: "0.05em",
                          flexShrink: 0,
                        }}
                      >
                        {meta.badge}
                      </span>
                      <span style={{ color: "#cccccc", fontSize: "13px", fontWeight: 600 }}>
                        {meta.label}
                      </span>
                    </div>

                    {/* Description */}
                    <p style={{ color: "#9ca3af", fontSize: "12px", margin: 0, lineHeight: 1.5 }}>
                      {meta.description}
                    </p>

                    {/* File path */}
                    <span style={{ color: "#4b5563", fontSize: "11px", fontFamily: "monospace" }}>
                      {file.path}
                    </span>
                  </div>

                  {/* Load button */}
                  <button
                    aria-label={`Load workflow: ${meta.label}`}
                    onClick={() => handleLoad(file)}
                    disabled={isLoading || loadingPath !== null}
                    style={{
                      background: isLoading ? "#374151" : "#1d4ed8",
                      color: isLoading ? "#6b7280" : "#eff6ff",
                      border: "none",
                      borderRadius: "5px",
                      padding: "6px 14px",
                      fontSize: "12px",
                      fontWeight: 600,
                      cursor: isLoading || loadingPath !== null ? "not-allowed" : "pointer",
                      whiteSpace: "nowrap",
                      flexShrink: 0,
                      transition: "background 0.15s",
                    }}
                  >
                    {isLoading ? "Loading…" : "Load"}
                  </button>
                </div>
              );
            })}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
