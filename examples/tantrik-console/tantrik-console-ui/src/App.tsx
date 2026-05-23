import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import TopBar from "./components/TopBar";
import PanelLayout from "./components/PanelLayout";
import ToastProvider from "./components/ToastProvider";
import OfflineBanner from "./components/OfflineBanner";
import KeyboardShortcutModal from "./components/KeyboardShortcutModal";
import { TabContextProvider, useTabContext } from "./components/TabContext";
import FileTree from "./components/FileTree";
import LoomEditor from "./components/LoomEditor";
import ExecutionPanel from "./components/ExecutionPanel";
import TokenDashboard from "./components/TokenDashboard";
import EngramPanel from "./components/EngramPanel";
import ChatPanel from "./components/ChatPanel";
import { useEditorStore } from "./stores/useEditorStore";
import { useToast } from "./components/ToastProvider";
import { dlog } from "./debug";
import type { FileDescriptor } from "./types";

const API_BASE = "";

// Secondary panel content — rendered inside TabContextProvider so useTabContext works
function SecondaryPanel() {
  const { activeTab } = useTabContext();

  if (activeTab === "token-dashboard") {
    return <TokenDashboard />;
  }

  if (activeTab === "engram") {
    return <EngramPanel />;
  }

  if (activeTab === "chat") {
    return <ChatPanel />;
  }

  return null;
}

// Inner component that has access to ToastProvider context
let appShellRenderCount = 0;
function AppShell() {
  appShellRenderCount++;
  const renderNum = appShellRenderCount;
  dlog('AppShell', `render #${renderNum}`);

  const [editorLoading, setEditorLoading] = useState(false);
  const loadFile = useEditorStore((s) => s.loadFile);
  const { showToast } = useToast();

  // Track when loadFile / showToast references change
  const prevLoadFile = useRef(loadFile);
  const prevShowToast = useRef(showToast);
  useEffect(() => {
    if (prevLoadFile.current !== loadFile) {
      dlog('AppShell', 'loadFile reference changed');
      prevLoadFile.current = loadFile;
    }
    if (prevShowToast.current !== showToast) {
      dlog('AppShell', 'showToast reference changed');
      prevShowToast.current = showToast;
    }
  });

  // Stable callback — only recreated if loadFile or showToast change (they don't)
  const handleFileClick = useCallback(async (file: FileDescriptor) => {
    setEditorLoading(true);
    try {
      const res = await fetch(`${API_BASE}/api/files/content?path=${encodeURIComponent(file.path)}`);
      if (!res.ok) {
        showToast(`Failed to load file: HTTP ${res.status}`, "error");
        return;
      }
      const content = await res.text();
      loadFile(file, content);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Network error";
      showToast(`Failed to load file: ${msg}`, "error");
    } finally {
      setEditorLoading(false);
    }
  }, [loadFile, showToast]);

  // Memoize panel content so PanelLayout doesn't see new props on every render.
  // FileTree only remounts when handleFileClick changes (stable above).
  // LoomEditor only remounts when editorLoading changes.
  const fileTreeContent = useMemo(
    () => <FileTree onFileClick={handleFileClick} />,
    [handleFileClick]
  );

  const editorContent = useMemo(
    () => (
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          height: "100%",
          overflow: "hidden",
        }}
      >
        <div style={{ flex: "0 0 60%", overflow: "hidden", minHeight: 0 }}>
          <LoomEditor loading={editorLoading} />
        </div>
        <div
          style={{
            height: "4px",
            background: "var(--border, #3c3c3c)",
            flexShrink: 0,
            cursor: "row-resize",
          }}
          aria-hidden="true"
        />
        <div style={{ flex: 1, overflow: "hidden", minHeight: 0 }}>
          <ExecutionPanel />
        </div>
      </div>
    ),
    [editorLoading]
  );

  // SecondaryPanel reads activeTab internally — no prop needed
  const secondaryContent = useMemo(() => <SecondaryPanel />, []);

  return (
    <TabContextProvider>
      <OfflineBanner>
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            height: "100%",
            overflow: "hidden",
            background: "var(--bg, #1e1e1e)",
          }}
        >
          <TopBar />
          <div style={{ flex: 1, overflow: "hidden", display: "flex" }}>
            <PanelLayout
              fileTreeContent={fileTreeContent}
              editorContent={editorContent}
              secondaryContent={secondaryContent}
            />
          </div>
        </div>
        <KeyboardShortcutModal />
      </OfflineBanner>
    </TabContextProvider>
  );
}

export default function App() {
  return (
    <ToastProvider>
      <AppShell />
    </ToastProvider>
  );
}