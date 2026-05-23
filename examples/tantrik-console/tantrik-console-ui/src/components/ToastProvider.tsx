import {
  createContext,
  useContext,
  useState,
  useCallback,
  ReactNode,
} from "react";
import * as Toast from "@radix-ui/react-toast";

export type ToastType = "success" | "error" | "info";

interface ToastMessage {
  id: string;
  title: string;
  description?: string;
  type: ToastType;
  duration?: number; // ms; defaults to 4000
}

interface ToastContextValue {
  showToast: (title: string, type?: ToastType, description?: string, duration?: number) => void;
}

const ToastContext = createContext<ToastContextValue>({
  showToast: () => {},
});

export function useToast(): ToastContextValue {
  return useContext(ToastContext);
}

const TYPE_COLORS: Record<ToastType, string> = {
  success: "#16a34a",
  error: "#dc2626",
  info: "#2563eb",
};

interface ToastProviderProps {
  children?: ReactNode;
}

export default function ToastProvider({ children }: ToastProviderProps) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const showToast = useCallback(
    (title: string, type: ToastType = "info", description?: string, duration?: number) => {
      const id = `${Date.now()}-${Math.random()}`;
      setToasts((prev) => [...prev, { id, title, description, type, duration }]);
    },
    []
  );

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      <Toast.Provider swipeDirection="right" duration={4000}>
        {children}

        {toasts.map((toast) => (
          <Toast.Root
            key={toast.id}
            open={true}
            duration={toast.duration ?? 4000}
            onOpenChange={(open) => {
              if (!open) removeToast(toast.id);
            }}
            style={{
              background: "#2a2a2a",
              border: `1px solid ${TYPE_COLORS[toast.type]}`,
              borderRadius: "8px",
              padding: "12px 16px",
              display: "flex",
              flexDirection: "column",
              gap: "4px",
              boxShadow: "0 4px 12px rgba(0,0,0,0.5)",
              minWidth: "280px",
              maxWidth: "400px",
            }}
          >
            <Toast.Title
              style={{
                color: TYPE_COLORS[toast.type],
                fontWeight: 600,
                fontSize: "14px",
              }}
            >
              {toast.title}
            </Toast.Title>
            {toast.description && (
              <Toast.Description
                style={{ color: "#cccccc", fontSize: "13px" }}
              >
                {toast.description}
              </Toast.Description>
            )}
            <Toast.Close
              aria-label="Close notification"
              style={{
                position: "absolute",
                top: "8px",
                right: "8px",
                background: "transparent",
                border: "none",
                color: "#888",
                cursor: "pointer",
                fontSize: "16px",
                padding: "2px 6px",
              }}
            >
              ×
            </Toast.Close>
          </Toast.Root>
        ))}

        <Toast.Viewport
          style={{
            position: "fixed",
            bottom: "24px",
            right: "24px",
            display: "flex",
            flexDirection: "column",
            gap: "8px",
            zIndex: 9999,
            listStyle: "none",
            margin: 0,
            padding: 0,
          }}
        />
      </Toast.Provider>
    </ToastContext.Provider>
  );
}
