import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";
import { dlogPageLoad } from "./debug";

// Mark this page load in the persistent log immediately
dlogPageLoad();

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Root element not found");
}

createRoot(rootElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
