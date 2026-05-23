import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../tantrik-console-server/src/main/resources/static",
    emptyOutDir: true
  },
  server: {
    port: 5173,
    // Proxy all /api requests to the Spring Boot backend.
    // This eliminates CORS entirely in dev mode.
    proxy: {
      "/api": {
        target: "http://localhost:8090",
        changeOrigin: true,
        // Do NOT proxy WebSocket upgrades on /api — only HTTP
        ws: false,
      },
    },
    hmr: {
      // Use a dedicated path for HMR WebSocket so it never
      // conflicts with the /api proxy or gets forwarded to the backend.
      path: "/__vite_hmr",
      port: 5173,
      host: "localhost",
      protocol: "ws",
    },
  },
  test: {
    globals: true,
    environment: "jsdom"
  }
});
