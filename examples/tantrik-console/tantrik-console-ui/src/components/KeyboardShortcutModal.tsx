import { useEffect, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";

interface Shortcut {
  keys: string;
  description: string;
}

const SHORTCUTS: Shortcut[] = [
  { keys: "Ctrl+S / Cmd+S", description: "Save file" },
  { keys: "Ctrl+Z", description: "Undo" },
  { keys: "Ctrl+Y / Ctrl+Shift+Z", description: "Redo" },
  { keys: "Ctrl+F", description: "Find" },
  { keys: "Shift+Alt+F", description: "Format document" },
  { keys: "?", description: "Show keyboard shortcuts" },
];

export default function KeyboardShortcutModal() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Only trigger on bare "?" — not when typing in an input/textarea
      const target = e.target as HTMLElement;
      const isEditable =
        target.tagName === "INPUT" ||
        target.tagName === "TEXTAREA" ||
        target.isContentEditable;

      if (e.key === "?" && !isEditable && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0, 0, 0, 0.6)",
            zIndex: 9000,
          }}
        />
        <Dialog.Content
          aria-describedby="shortcuts-description"
          style={{
            position: "fixed",
            top: "50%",
            left: "50%",
            transform: "translate(-50%, -50%)",
            background: "#252526",
            border: "1px solid #3c3c3c",
            borderRadius: "10px",
            padding: "24px",
            width: "480px",
            maxWidth: "90vw",
            maxHeight: "80vh",
            overflowY: "auto",
            zIndex: 9001,
            boxShadow: "0 8px 32px rgba(0,0,0,0.6)",
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              marginBottom: "20px",
            }}
          >
            <Dialog.Title
              style={{
                color: "#cccccc",
                fontSize: "16px",
                fontWeight: 600,
                margin: 0,
              }}
            >
              Keyboard Shortcuts
            </Dialog.Title>
            <Dialog.Close
              aria-label="Close keyboard shortcuts"
              style={{
                background: "transparent",
                border: "none",
                color: "#888",
                cursor: "pointer",
                fontSize: "20px",
                padding: "2px 6px",
                borderRadius: "4px",
                lineHeight: 1,
              }}
            >
              ×
            </Dialog.Close>
          </div>

          <p
            id="shortcuts-description"
            style={{
              color: "#888",
              fontSize: "13px",
              marginTop: 0,
              marginBottom: "16px",
            }}
          >
            All available keyboard shortcuts for Tantrik IDE.
          </p>

          <table
            style={{
              width: "100%",
              borderCollapse: "collapse",
            }}
          >
            <thead>
              <tr>
                <th
                  style={{
                    textAlign: "left",
                    color: "#888",
                    fontSize: "12px",
                    fontWeight: 500,
                    padding: "4px 8px 8px 0",
                    borderBottom: "1px solid #3c3c3c",
                    textTransform: "uppercase",
                    letterSpacing: "0.05em",
                  }}
                >
                  Shortcut
                </th>
                <th
                  style={{
                    textAlign: "left",
                    color: "#888",
                    fontSize: "12px",
                    fontWeight: 500,
                    padding: "4px 0 8px 8px",
                    borderBottom: "1px solid #3c3c3c",
                    textTransform: "uppercase",
                    letterSpacing: "0.05em",
                  }}
                >
                  Action
                </th>
              </tr>
            </thead>
            <tbody>
              {SHORTCUTS.map((shortcut, i) => (
                <tr
                  key={i}
                  style={{
                    borderBottom: "1px solid #2a2a2a",
                  }}
                >
                  <td
                    style={{
                      padding: "10px 8px 10px 0",
                      verticalAlign: "middle",
                    }}
                  >
                    <kbd
                      style={{
                        background: "#1e1e1e",
                        border: "1px solid #3c3c3c",
                        borderRadius: "4px",
                        padding: "2px 8px",
                        fontSize: "12px",
                        color: "#cccccc",
                        fontFamily: "monospace",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {shortcut.keys}
                    </kbd>
                  </td>
                  <td
                    style={{
                      padding: "10px 0 10px 8px",
                      color: "#cccccc",
                      fontSize: "13px",
                      verticalAlign: "middle",
                    }}
                  >
                    {shortcut.description}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
