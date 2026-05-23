import { useEffect, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import type { EngramNode } from "../types";

const API_BASE = "";

// ── tier color helpers ────────────────────────────────────────────────────────
const TIER_COLOR: Record<EngramNode["tier"], string> = {
  WORKING: "#3b82f6",
  EPISODIC: "#22c55e",
  SEMANTIC: "#f97316",
};

// ── props ─────────────────────────────────────────────────────────────────────
interface NodeDetailDrawerProps {
  /** The id of the node to show, or null when the drawer is closed. */
  nodeId: string | null;
  onClose: () => void;
  onDeleted: (id: string) => void;
}

// ── component ─────────────────────────────────────────────────────────────────
export default function NodeDetailDrawer({
  nodeId,
  onClose,
  onDeleted,
}: NodeDetailDrawerProps) {
  const [node, setNode] = useState<EngramNode | null>(null);
  const [loading, setLoading] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Fetch full node content whenever nodeId changes
  useEffect(() => {
    if (!nodeId) {
      setNode(null);
      setFetchError(null);
      setDeleteError(null);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setFetchError(null);
    setDeleteError(null);
    setNode(null);

    fetch(`${API_BASE}/api/engram/nodes/${encodeURIComponent(nodeId)}`)
      .then(async (res) => {
        if (cancelled) return;
        if (!res.ok) {
          let errMsg = `HTTP ${res.status}`;
          try {
            const json = await res.json();
            if (json.error) errMsg = json.error;
          } catch {
            // ignore
          }
          setFetchError(errMsg);
        } else {
          const data: EngramNode = await res.json();
          if (!cancelled) setNode(data);
        }
      })
      .catch((err) => {
        if (cancelled) return;
        const msg = err instanceof Error ? err.message : "Network error";
        setFetchError(msg);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [nodeId]);

  // Delete handler
  const handleDelete = async () => {
    if (!nodeId) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      const res = await fetch(
        `${API_BASE}/api/engram/nodes/${encodeURIComponent(nodeId)}`,
        { method: "DELETE" }
      );
      if (!res.ok) {
        let errMsg = `HTTP ${res.status}`;
        try {
          const json = await res.json();
          if (json.error) errMsg = json.error;
        } catch {
          // ignore
        }
        setDeleteError(errMsg);
      } else {
        // Success — notify parent to remove from local state
        onDeleted(nodeId);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      setDeleteError(msg);
    } finally {
      setDeleting(false);
    }
  };

  const isOpen = nodeId !== null;

  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => { if (!open) onClose(); }}>
      <Dialog.Portal>
        {/* Overlay */}
        <Dialog.Overlay
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0,0,0,0.5)",
            zIndex: 50,
          }}
        />

        {/* Content — positioned as a right-side drawer */}
        <Dialog.Content
          aria-describedby="node-detail-description"
          style={{
            position: "fixed",
            top: 0,
            right: 0,
            bottom: 0,
            width: "360px",
            maxWidth: "90vw",
            background: "#1e1e1e",
            borderLeft: "1px solid #3c3c3c",
            zIndex: 51,
            display: "flex",
            flexDirection: "column",
            overflow: "hidden",
          }}
        >
          {/* Header */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "12px 16px",
              background: "#252526",
              borderBottom: "1px solid #3c3c3c",
              flexShrink: 0,
            }}
          >
            <Dialog.Title
              style={{
                fontSize: "13px",
                fontWeight: 600,
                color: "#cccccc",
                margin: 0,
              }}
            >
              Node Detail
            </Dialog.Title>
            <Dialog.Close
              aria-label="Close node detail drawer"
              style={{
                background: "transparent",
                border: "none",
                color: "#9ca3af",
                cursor: "pointer",
                fontSize: "18px",
                lineHeight: 1,
                padding: "2px 6px",
                borderRadius: "4px",
              }}
            >
              ×
            </Dialog.Close>
          </div>

          {/* Body */}
          <div
            id="node-detail-description"
            style={{
              flex: 1,
              overflow: "auto",
              padding: "16px",
              display: "flex",
              flexDirection: "column",
              gap: "14px",
            }}
          >
            {/* Loading */}
            {loading && (
              <div
                role="status"
                aria-label="Loading node details"
                style={{ color: "#9ca3af", fontSize: "13px" }}
              >
                Loading…
              </div>
            )}

            {/* Fetch error */}
            {fetchError && !loading && (
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
                Failed to load node: {fetchError}
              </div>
            )}

            {/* Node details */}
            {!loading && !fetchError && node && (
              <>
                {/* Topic key */}
                <div>
                  <div
                    style={{
                      fontSize: "11px",
                      color: "#6b7280",
                      marginBottom: "4px",
                      textTransform: "uppercase",
                      letterSpacing: "0.05em",
                    }}
                  >
                    Topic Key
                  </div>
                  <div
                    style={{
                      fontSize: "13px",
                      color: "#cccccc",
                      fontWeight: 600,
                      wordBreak: "break-all",
                    }}
                  >
                    {node.topicKey}
                  </div>
                </div>

                {/* Tier + Importance row */}
                <div style={{ display: "flex", gap: "16px" }}>
                  <div>
                    <div
                      style={{
                        fontSize: "11px",
                        color: "#6b7280",
                        marginBottom: "4px",
                        textTransform: "uppercase",
                        letterSpacing: "0.05em",
                      }}
                    >
                      Tier
                    </div>
                    <span
                      style={{
                        display: "inline-block",
                        background: TIER_COLOR[node.tier],
                        color: "#fff",
                        borderRadius: "4px",
                        padding: "2px 8px",
                        fontSize: "11px",
                        fontWeight: 600,
                      }}
                    >
                      {node.tier}
                    </span>
                  </div>
                  <div>
                    <div
                      style={{
                        fontSize: "11px",
                        color: "#6b7280",
                        marginBottom: "4px",
                        textTransform: "uppercase",
                        letterSpacing: "0.05em",
                      }}
                    >
                      Importance
                    </div>
                    <div style={{ fontSize: "13px", color: "#cccccc" }}>
                      {(node.importance * 100).toFixed(0)}%
                    </div>
                  </div>
                </div>

                {/* Content */}
                <div>
                  <div
                    style={{
                      fontSize: "11px",
                      color: "#6b7280",
                      marginBottom: "6px",
                      textTransform: "uppercase",
                      letterSpacing: "0.05em",
                    }}
                  >
                    Content
                  </div>
                  <div
                    style={{
                      background: "#2d2d2d",
                      border: "1px solid #3c3c3c",
                      borderRadius: "6px",
                      padding: "10px 12px",
                      fontSize: "12px",
                      color: "#cccccc",
                      lineHeight: 1.6,
                      whiteSpace: "pre-wrap",
                      wordBreak: "break-word",
                      maxHeight: "300px",
                      overflow: "auto",
                    }}
                  >
                    {node.content}
                  </div>
                </div>

                {/* Node ID */}
                <div>
                  <div
                    style={{
                      fontSize: "11px",
                      color: "#6b7280",
                      marginBottom: "4px",
                      textTransform: "uppercase",
                      letterSpacing: "0.05em",
                    }}
                  >
                    ID
                  </div>
                  <div
                    style={{
                      fontSize: "11px",
                      color: "#6b7280",
                      fontFamily: "monospace",
                      wordBreak: "break-all",
                    }}
                  >
                    {node.id}
                  </div>
                </div>
              </>
            )}

            {/* Delete error */}
            {deleteError && (
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
                Delete failed: {deleteError}
              </div>
            )}
          </div>

          {/* Footer — Delete button */}
          {!loading && !fetchError && node && (
            <div
              style={{
                padding: "12px 16px",
                borderTop: "1px solid #3c3c3c",
                flexShrink: 0,
                background: "#252526",
              }}
            >
              <button
                aria-label="Delete this engram node"
                onClick={handleDelete}
                disabled={deleting}
                style={{
                  width: "100%",
                  background: deleting ? "#450a0a" : "#7f1d1d",
                  color: "#fca5a5",
                  border: "1px solid #991b1b",
                  borderRadius: "6px",
                  padding: "8px 16px",
                  fontSize: "13px",
                  fontWeight: 600,
                  cursor: deleting ? "not-allowed" : "pointer",
                  opacity: deleting ? 0.7 : 1,
                  transition: "opacity 0.15s ease",
                }}
              >
                {deleting ? "Deleting…" : "Delete Node"}
              </button>
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
