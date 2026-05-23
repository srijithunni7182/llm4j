import { useEffect, useState } from "react";
import type { FileDescriptor } from "../types";
import { useEditorStore } from "../stores/useEditorStore";
import { usePanelLayout } from "./PanelLayout";
import { dlog } from "../debug";

const API_BASE = "";

// ── DEBUG ─────────────────────────────────────────────────────────────────────
let fileTreeMountCount = 0;

interface FileTreeProps {
  onFileClick: (file: FileDescriptor) => void;
}

// ── Loading skeleton ──────────────────────────────────────────────────────────

function SkeletonRow() {
  return (
    <div
      style={{
        height: "20px",
        borderRadius: "4px",
        margin: "6px 12px",
        background: "var(--skeleton-base, #3a3a3a)",
        animation: "skeleton-shimmer 1.4s ease-in-out infinite",
      }}
      aria-hidden="true"
    />
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export default function FileTree({ onFileClick }: FileTreeProps) {
  const [files, setFiles] = useState<FileDescriptor[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const currentFile = useEditorStore((s) => s.currentFile);
  const isDirty = useEditorStore((s) => s.isDirty);

  const { fileTreeCollapsed, toggleFileTree } = usePanelLayout();

  useEffect(() => {
    fileTreeMountCount++;
    dlog('FileTree', `MOUNTED #${fileTreeMountCount}`);
    return () => {
      dlog('FileTree', `UNMOUNTED #${fileTreeMountCount}`);
    };
  }, []);

  useEffect(() => {
    dlog('FileTree', 'fetch useEffect fired');
    let cancelled = false;

    async function fetchFiles() {
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(`${API_BASE}/api/files`);
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}: ${res.statusText}`);
        }
        const data: FileDescriptor[] = await res.json();
        if (!cancelled) {
          setFiles(data);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load files");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    fetchFiles();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      {/* Inject skeleton shimmer keyframes once */}
      <style>{`
        @keyframes skeleton-shimmer {
          0%   { opacity: 1; }
          50%  { opacity: 0.4; }
          100% { opacity: 1; }
        }
      `}</style>

      {/* Panel header */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "8px 12px",
          borderBottom: "1px solid var(--border, #3c3c3c)",
          flexShrink: 0,
        }}
      >
        <span
          style={{
            fontSize: "11px",
            fontWeight: 600,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: "var(--fg-muted, #888)",
            userSelect: "none",
          }}
        >
          Files
        </span>

        {/* Collapse / expand toggle */}
        <button
          onClick={toggleFileTree}
          aria-label={fileTreeCollapsed ? "Expand file tree" : "Collapse file tree"}
          title={fileTreeCollapsed ? "Expand file tree" : "Collapse file tree"}
          style={{
            background: "none",
            border: "none",
            cursor: "pointer",
            color: "var(--fg-muted, #888)",
            fontSize: "14px",
            padding: "2px 4px",
            borderRadius: "3px",
            lineHeight: 1,
            transition: "color 0.15s",
          }}
          onMouseEnter={(e) =>
            ((e.currentTarget as HTMLButtonElement).style.color =
              "var(--fg, #cccccc)")
          }
          onMouseLeave={(e) =>
            ((e.currentTarget as HTMLButtonElement).style.color =
              "var(--fg-muted, #888)")
          }
        >
          {fileTreeCollapsed ? "▶" : "◀"}
        </button>
      </div>

      {/* Panel body */}
      <div
        style={{
          flex: 1,
          overflowY: "auto",
          overflowX: "hidden",
        }}
      >
        {/* Loading skeleton — 5 placeholder rows */}
        {loading && (
          <div role="status" aria-label="Loading files">
            <SkeletonRow />
            <SkeletonRow />
            <SkeletonRow />
            <SkeletonRow />
            <SkeletonRow />
          </div>
        )}

        {/* Inline error message */}
        {!loading && error && (
          <div
            role="alert"
            style={{
              margin: "12px",
              padding: "8px 10px",
              borderRadius: "4px",
              background: "rgba(239,68,68,0.15)",
              border: "1px solid rgba(239,68,68,0.4)",
              color: "#f87171",
              fontSize: "12px",
              lineHeight: 1.5,
            }}
          >
            <strong>Error loading files:</strong>
            <br />
            {error}
          </div>
        )}

        {/* File list */}
        {!loading && !error && files.length === 0 && (
          <div
            style={{
              padding: "12px",
              color: "var(--fg-muted, #888)",
              fontSize: "12px",
            }}
          >
            No .loom files found.
          </div>
        )}

        {!loading && !error && files.length > 0 && (
          <ul
            role="tree"
            aria-label="Loom script files"
            style={{
              listStyle: "none",
              margin: 0,
              padding: "4px 0",
            }}
          >
            {files.map((file) => {
              const isActive = currentFile?.path === file.path;
              const showDirty = isActive && isDirty;

              return (
                <li
                  key={file.path}
                  role="treeitem"
                  aria-selected={isActive}
                  aria-label={`${file.name}${showDirty ? " (unsaved changes)" : ""}`}
                >
                  <button
                    onClick={() => onFileClick(file)}
                    title={file.path}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: "6px",
                      width: "100%",
                      background: isActive
                        ? "var(--selection-bg, rgba(255,255,255,0.08))"
                        : "none",
                      border: "none",
                      cursor: "pointer",
                      color: isActive
                        ? "var(--fg, #cccccc)"
                        : "var(--fg-muted, #aaaaaa)",
                      fontSize: "13px",
                      padding: "5px 12px",
                      textAlign: "left",
                      whiteSpace: "nowrap",
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      transition: "background 0.1s, color 0.1s",
                    }}
                    onMouseEnter={(e) => {
                      if (!isActive) {
                        (e.currentTarget as HTMLButtonElement).style.background =
                          "var(--hover-bg, rgba(255,255,255,0.05))";
                        (e.currentTarget as HTMLButtonElement).style.color =
                          "var(--fg, #cccccc)";
                      }
                    }}
                    onMouseLeave={(e) => {
                      if (!isActive) {
                        (e.currentTarget as HTMLButtonElement).style.background =
                          "none";
                        (e.currentTarget as HTMLButtonElement).style.color =
                          "var(--fg-muted, #aaaaaa)";
                      }
                    }}
                  >
                    {/* Dirty indicator */}
                    {showDirty && (
                      <span
                        aria-hidden="true"
                        style={{
                          color: "#f97316",
                          fontSize: "10px",
                          flexShrink: 0,
                          lineHeight: 1,
                        }}
                      >
                        ●
                      </span>
                    )}

                    {/* File icon */}
                    <span
                      aria-hidden="true"
                      style={{ flexShrink: 0, fontSize: "12px", opacity: 0.7 }}
                    >
                      📄
                    </span>

                    {/* Filename */}
                    <span
                      style={{
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                        flex: 1,
                      }}
                    >
                      {file.name}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </>
  );
}
