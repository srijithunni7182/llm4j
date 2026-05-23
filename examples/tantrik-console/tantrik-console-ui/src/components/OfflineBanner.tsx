import { createContext, useContext, useEffect, useState, ReactNode } from "react";

// Context so TopBar and other components can read online status
interface OnlineContextValue {
  isOnline: boolean;
}

const OnlineContext = createContext<OnlineContextValue>({ isOnline: true });

export function useOnlineStatus(): boolean {
  return useContext(OnlineContext).isOnline;
}

interface OfflineBannerProps {
  children?: ReactNode;
}

export default function OfflineBanner({ children }: OfflineBannerProps) {
  const [isOnline, setIsOnline] = useState<boolean>(navigator.onLine);

  useEffect(() => {
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);

    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);

    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  return (
    <OnlineContext.Provider value={{ isOnline }}>
      {!isOnline && (
        <div
          role="alert"
          aria-live="assertive"
          style={{
            background: "#b45309",
            color: "#fff",
            padding: "8px 16px",
            textAlign: "center",
            fontSize: "14px",
            fontWeight: 500,
            zIndex: 1000,
            position: "relative",
          }}
        >
          You are offline. Some features are unavailable.
        </div>
      )}
      {children}
    </OnlineContext.Provider>
  );
}
