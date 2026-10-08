/** The HTML of the graph panel. Everything it loads is a file of the extension, and scripts need the nonce. */

export interface PanelAssets {
    /** Where VS Code serves the extension's files from (webview.cspSource). */
    cspSource: string;
    /** A fresh random value for this page's scripts. */
    nonce: string;
    /** Turns a file name in media/ into the address the panel can load. */
    uri: (name: string) => string;
}

export function contentSecurityPolicy(cspSource: string, nonce: string): string {
    return [
        "default-src 'none'",
        `img-src ${cspSource}`,
        `style-src ${cspSource}`,
        `script-src 'nonce-${nonce}'`,
    ].join('; ');
}

/** The page shell: the panel script fills it in. All text here is fixed; nothing from a script is written into it. */
export function renderPanelHtml(assets: PanelAssets): string {
    const { cspSource, nonce, uri } = assets;
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(cspSource, nonce)}">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Loom Graph</title>
<link rel="stylesheet" href="${uri('graph-render.css')}">
<link rel="stylesheet" href="${uri('panel.css')}">
</head>
<body>
<header class="toolbar" id="toolbar">
  <div class="brand"><span class="tile"><img src="${uri('loom-mark-128.png')}" alt=""></span><b>Loom Graph</b></div>
  <label for="workflow" class="muted">Workflow</label>
  <select id="workflow" aria-label="Workflow"></select>
  <span class="chip budget" id="runbudget" hidden></span>
  <span class="spacer"></span>
  <span class="zoomgroup">
    <button class="btn" id="zoomOut" type="button" aria-label="Zoom out">−</button>
    <span class="zoomlabel" id="zoomLabel" aria-live="polite">100%</span>
    <button class="btn" id="zoomIn" type="button" aria-label="Zoom in">+</button>
    <button class="btn" id="fit" type="button" aria-label="Fit to view">Fit</button>
  </span>
  <button class="btn" id="legendButton" type="button" aria-expanded="false" aria-controls="legend">Legend</button>
  <button class="btn" id="mermaid" type="button">Copy Mermaid</button>
</header>
<nav class="crumbs" id="crumbs" aria-label="Workflow path" hidden></nav>
<div class="banner stale" id="stale" role="status" hidden>
  <span id="staleText"></span>
  <button class="link" id="staleOpen" type="button">Open file</button>
</div>
<section class="diag" id="diagnostics" hidden></section>
<main class="canvas" id="canvas" aria-label="Workflow graph">
  <div class="loading" id="loading">
    <span class="tile big pulse"><img src="${uri('loom-logo-320.png')}" alt="Loom"></span>
    <p class="muted" id="loadingText">Reading the script…</p>
  </div>
  <div class="empty" id="empty" hidden>
    <span class="tile big"><img src="${uri('loom-logo-320.png')}" alt="Loom"></span>
    <p id="emptyText"></p>
    <button class="btn" id="emptyOpen" type="button">Go to line 1</button>
  </div>
  <div class="legend" id="legend" hidden></div>
  <div class="card" id="card" role="tooltip" hidden></div>
</main>
<footer class="footer">
  <section aria-label="Files"><h2>Files</h2><div class="files" id="files"></div></section>
  <section aria-label="Selected step"><h2>Selected</h2><div class="selected" id="selected"></div></section>
</footer>
<script nonce="${nonce}" src="${uri('graph-render.js')}"></script>
<script nonce="${nonce}" src="${uri('panel.js')}"></script>
</body>
</html>
`;
}

export function newNonce(random: (bytes: number) => Buffer): string {
    return random(16).toString('base64').replace(/[^A-Za-z0-9]/g, '');
}
