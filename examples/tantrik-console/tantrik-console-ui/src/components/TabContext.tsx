import { createContext, useContext, useState, ReactNode } from "react";

export type SecondaryTab = "token-dashboard" | "engram" | "chat";

interface TabContextValue {
  activeTab: SecondaryTab;
  setActiveTab: (tab: SecondaryTab) => void;
}

const TabContext = createContext<TabContextValue>({
  activeTab: "token-dashboard",
  setActiveTab: () => {},
});

export function useTabContext(): TabContextValue {
  return useContext(TabContext);
}

export function TabContextProvider({ children }: { children: ReactNode }) {
  const [activeTab, setActiveTab] = useState<SecondaryTab>("token-dashboard");
  return (
    <TabContext.Provider value={{ activeTab, setActiveTab }}>
      {children}
    </TabContext.Provider>
  );
}
