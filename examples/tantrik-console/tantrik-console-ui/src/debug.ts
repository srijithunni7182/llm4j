/**
 * Persistent debug logger — sends log lines to the backend which appends
 * them to debug-client.log. Survives page reloads.
 *
 * Usage:  import { dlog } from '../debug';
 *         dlog('MyComponent', 'mounted', { someValue });
 *
 * View:   tail -f examples/tantrik-console/tantrik-console-server/debug-client.log
 *
 * DELETE this file once the reload bug is fixed.
 */

const SESSION_ID = Math.random().toString(36).slice(2, 7);
let seq = 0;

export function dlog(component: string, event: string, data?: unknown): void {
  seq++;
  const msg = `[${SESSION_ID}:#${seq}] [${component}] ${event}${data !== undefined ? ' ' + JSON.stringify(data) : ''}`;

  // Also write to console for immediate visibility
  console.log(msg);

  // Fire-and-forget POST to backend — use sendBeacon so it works during unload too
  const line = msg;
  if (navigator.sendBeacon) {
    const blob = new Blob([line], { type: 'text/plain' });
    navigator.sendBeacon('/api/debug/log', blob);
  } else {
    fetch('/api/debug/log', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: line,
      keepalive: true,
    }).catch(() => {/* ignore */});
  }
}

/** Call once at app startup to mark a new page load in the log */
export function dlogPageLoad(): void {
  // Clear previous log on first load so each run starts fresh
  fetch('/api/debug/log', { method: 'DELETE' }).catch(() => {});
  dlog('PAGE', 'LOAD', { url: window.location.href, time: new Date().toISOString() });

  // Log every unhandled error
  window.addEventListener('error', (e) => {
    dlog('WINDOW', 'error', { message: e.message, source: e.filename, line: e.lineno });
  });

  // Log every unhandled promise rejection
  window.addEventListener('unhandledrejection', (e) => {
    dlog('WINDOW', 'unhandledrejection', { reason: String(e.reason) });
  });

  // Log page visibility changes (tab hidden/shown)
  document.addEventListener('visibilitychange', () => {
    dlog('PAGE', 'visibilitychange', { hidden: document.hidden });
  });

  // Log before-unload (page about to reload/close)
  window.addEventListener('beforeunload', () => {
    dlog('PAGE', 'BEFOREUNLOAD — page is reloading or closing');
  });
}
