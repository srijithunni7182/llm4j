import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  ReactNode,
} from "react";
import {
  PanelGroup,
  Panel,
  PanelResizeHandle,
  ImperativePanelHandle,
} from "react-resizable-panels";
import { useTabContext } from "./TabContext";
import { dlog } from "../debug";

const STORAGE_KEY = "tantrik-panel-sizes";
const COLLAPSE_BREAKPOINT = 900;

// Default sizes as percentages
const DEFAULT_SIZES = { fileTree: 18, editor: 55, secondary: 27 };

interface PanelLayoutContextValue {
  fileTreeCollapsed: boolean;
  toggleFileTree: () => void;
}

const PanelLayoutContext = createContext<PanelLayoutContextValue>({
  fileTreeCollapsed: false,
  toggleFileTree: () => {},
});

export function usePanelLayout(): PanelLayoutContextValue {
  return useContext(PanelLayoutContext);
}

function loadSizes(): { fileTree: number; editor: number; secondary: number } {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (
        typeof parsed.fileTree === "number" &&
        typeof parsed.editor === "number" &&
        typeof parsed.secondary === "number"
      ) {
        return parsed;
      }
    }
  } catch {
    // ignore
  }
  return DEFAULT_SIZES;
}

function saveSizes(sizes: {
  fileTree: number;
  editor: number;
  secondary: number;
}) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sizes));
  } catch {
    // ignore
  }
}

interface PanelLayoutProps {
  fileTreeContent?: ReactNode;
  editorContent?: ReactNode;
  secondaryContent?: ReactNode;
}

let panelLayoutRenderCount = 0;

export default function PanelLayout({
  fileTreeContent,
  editorContent,
  secondaryContent,
}: PanelLayoutProps) {
  panelLayoutRenderCount++;
  dlog('PanelLayout', `render #${panelLayoutRenderCount}`, {
    hasFileTree: !!fileTreeContent,
    hasEditor: !!editorContent,
    hasSecondary: !!secondaryContent,
  });

  const { activeTab } = useTabContext();
  const [fileTreeCollapsed, setFileTreeCollapsed] = useState(false);
  const fileTreePanelRef = useRef<ImperativePanelHandle>(null);
  const savedSizes = useRef(loadSizes());

  // Log mount/unmount
  useEffect(() => {
    dlog('PanelLayout', 'MOUNTED');
    return () => dlog('PanelLayout', 'UNMOUNTED');
  }, []);

  // Log when props change identity
  const prevFileTree = useRef(fileTreeContent);
  const prevEditor = useRef(editorContent);
  const prevSecondary = useRef(secondaryContent);
  useEffect(() => {
    if (prevFileTree.current !== fileTreeContent) {
      dlog('PanelLayout', 'fileTreeContent prop identity changed — FileTree will remount');
      prevFileTree.current = fileTreeContent;
    }
    if (prevEditor.current !== editorContent) {
      dlog('PanelLayout', 'editorContent prop identity changed');
      prevEditor.current = editorContent;
    }
    if (prevSecondary.current !== secondaryContent) {
      dlog('PanelLayout', 'secondaryContent prop identity changed');
      prevSecondary.current = secondaryContent;
    }
  });

  // Auto-collapse on narrow viewports
  useEffect(() => {
    const mq = window.matchMedia(`(max-width: ${COLLAPSE_BREAKPOINT - 1}px)`);

    const handleChange = (e: MediaQueryListEvent | MediaQueryList) => {
      if (e.matches) {
        setFileTreeCollapsed(true);
        fileTreePanelRef.current?.collapse();
      } else {
        setFileTreeCollapsed(false);
        fileTreePanelRef.current?.expand();
      }
    };

    // Check immediately
    handleChange(mq);

    mq.addEventListener("change", handleChange);
    return () => mq.removeEventListener("change", handleChange);
  }, []);

  const toggleFileTree = () => {
    if (fileTreeCollapsed) {
      fileTreePanelRef.current?.expand();
      setFileTreeCollapsed(false);
    } else {
      fileTreePanelRef.current?.collapse();
      setFileTreeCollapsed(true);
    }
  };

  const handleLayout = (sizes: number[]) => {
    // sizes[0] = fileTree, sizes[1] = editor, sizes[2] = secondary
    if (sizes.length === 3) {
      const newSizes = {
        fileTree: sizes[0],
        editor: sizes[1],
        secondary: sizes[2],
      };
      savedSizes.current = newSizes;
      saveSizes(newSizes);
    }
  };

  const secondaryLabel =
    activeTab === "token-dashboard"
      ? "Token Dashboard"
      : activeTab === "engram"
      ? "Engram"
      : "Chat";

  return (
    <PanelLayoutContext.Provider value={{ fileTreeCollapsed, toggleFileTree }}>
      <div
        style={{
          display: "flex",
          flex: 1,
          overflow: "hidden",
          height: "100%",
        }}
      >
        <PanelGroup
          direction="horizontal"
          onLayout={handleLayout}
          style={{ flex: 1 }}
        >
          {/* File Tree Panel */}
          <Panel
            ref={fileTreePanelRef}
            defaultSize={savedSizes.current.fileTree}
            minSize={0}
            collapsible={true}
            collapsedSize={0}
            onCollapse={() => setFileTreeCollapsed(true)}
            onExpand={() => setFileTreeCollapsed(false)}
            style={{
              background: "var(--sidebar-bg, #252526)",
              overflow: "hidden",
              display: "flex",
              flexDirection: "column",
            }}
          >
            {fileTreeContent ?? (
              <div
                style={{
                  padding: "12px",
                  color: "var(--fg-muted, #888)",
                  fontSize: "13px",
                }}
              >
                File Tree
              </div>
            )}
          </Panel>

          <PanelResizeHandle
            style={{
              width: "4px",
              background: "var(--border, #3c3c3c)",
              cursor: "col-resize",
              flexShrink: 0,
              transition: "background 0.15s",
            }}
            aria-label="Resize file tree"
          />

          {/* Editor Panel */}
          <Panel
            defaultSize={savedSizes.current.editor}
            minSize={20}
            style={{
              background: "var(--editor-bg, #1e1e1e)",
              overflow: "hidden",
              display: "flex",
              flexDirection: "column",
            }}
          >
            {editorContent ?? (
              <div
                style={{
                  padding: "12px",
                  color: "var(--fg-muted, #888)",
                  fontSize: "13px",
                }}
              >
                Editor
              </div>
            )}
          </Panel>

          <PanelResizeHandle
            style={{
              width: "4px",
              background: "var(--border, #3c3c3c)",
              cursor: "col-resize",
              flexShrink: 0,
              transition: "background 0.15s",
            }}
            aria-label="Resize secondary panel"
          />

          {/* Secondary Panel (Token Dashboard / Engram / Chat) */}
          <Panel
            defaultSize={savedSizes.current.secondary}
            minSize={15}
            style={{
              background: "var(--sidebar-bg, #252526)",
              overflow: "hidden",
              display: "flex",
              flexDirection: "column",
            }}
          >
            {secondaryContent ?? (
              <div
                style={{
                  padding: "12px",
                  color: "var(--fg-muted, #888)",
                  fontSize: "13px",
                }}
              >
                {secondaryLabel}
              </div>
            )}
          </Panel>
        </PanelGroup>
      </div>
    </PanelLayoutContext.Provider>
  );
}
