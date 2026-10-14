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
        `img-src ${cspSource} data: blob:`,
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
<link rel="stylesheet" href="${uri('panel.css')}">
</head>
<body>
<header class="toolbar" id="toolbar">
  <div class="brand"><span class="tile"><img src="${uri('loom-mark-128.png')}" alt=""></span><b>Loom Graph</b></div>
  <label for="workflow" class="muted">Workflow</label>
  <select id="workflow" aria-label="Workflow"></select>
  <span class="chip budget" id="runbudget" hidden></span>
  <span class="spacer"></span>
  <div class="viewsw" id="viewSwitch" role="group" aria-label="View">
    <button class="vbtn" id="viewOutline" type="button" data-view="outline" aria-pressed="true" title="Outline (1)">Outline</button>
    <button class="vbtn" id="viewFocus" type="button" data-view="focus" aria-pressed="false" title="Focus (2)">Focus</button>
    <button class="vbtn" id="viewFlow" type="button" data-view="flow" aria-pressed="false" title="Flow (3)">Flow</button>
  </div>
  <button class="btn" id="mermaid" type="button">Copy Mermaid</button>
  <button class="btn" id="exportPng" type="button"><span id="exportIcon" aria-hidden="true"></span>Export PNG</button>
</header>
<nav class="crumbs" id="crumbs" aria-label="Where you are" hidden></nav>
<details class="filesbox" id="filesBox"><summary>Files</summary><div class="files" id="files"></div></details>
<details class="agentsbox" id="agentsBox"><summary>Agents and their tools</summary><div class="agents" id="agents"></div></details>
<div class="banner stale" id="stale" role="status" hidden>
  <span id="staleText"></span>
  <button class="link" id="staleOpen" type="button">Open file</button>
</div>
<section class="diag" id="diagnostics" hidden></section>
<main class="canvas" id="canvas" aria-label="Workflow graph">
  <div class="viewhost" id="viewHost"></div>
  <div class="loading" id="loading">
    <span class="tile big pulse"><img src="${uri('loom-logo-320.png')}" alt="Loom"></span>
    <p class="muted" id="loadingText">Reading the script…</p>
  </div>
  <div class="empty" id="empty" hidden>
    <span class="tile big"><img src="${uri('loom-logo-320.png')}" alt="Loom"></span>
    <p id="emptyText"></p>
    <button class="btn" id="emptyOpen" type="button">Go to line 1</button>
  </div>
</main>
<footer class="footer" id="footer" aria-label="Main path">
  <div class="railtop"><span class="kicker">Main path</span><span class="note" id="railNote"></span><div class="legend" id="legend" aria-label="Kinds of step"></div></div>
  <div class="rail" id="rail" role="group" aria-label="Steps on the main path"></div>
  <div class="railsecs" id="railSecs"></div>
</footer>
<div class="ctxmenu" id="ctxmenu" role="menu" aria-label="Step actions" hidden></div>
<div class="toast" id="toast" role="status" aria-live="polite" hidden></div>
<script nonce="${nonce}" src="${uri('graph-render.js')}"></script>
<script nonce="${nonce}" src="${uri('graph-model.js')}"></script>
<script nonce="${nonce}" src="${uri('views.js')}"></script>
<script nonce="${nonce}" src="${uri('panel.js')}"></script>
</body>
</html>
`;
}

export function newNonce(random: (bytes: number) => Buffer): string {
    return random(16).toString('base64').replace(/[^A-Za-z0-9]/g, '');
}
