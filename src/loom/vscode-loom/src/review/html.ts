import { contentSecurityPolicy, PanelAssets } from '../graph/html';

/**
 * The page shell of the Loom Review panel. All text here is fixed; the page script fills the rest in with textContent, so nothing
 * from a script or a finding is ever written into markup. The scripts need the nonce; styles and images are files of the extension.
 */
export function renderReviewHtml(assets: PanelAssets): string {
    const { cspSource, nonce, uri } = assets;
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(cspSource, nonce)}">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Loom Review</title>
<link rel="stylesheet" href="${uri('review.css')}">
</head>
<body>
<header class="toolbar" id="toolbar">
  <div class="brand"><span class="tile"><img src="${uri('loom-mark-128.png')}" alt=""></span><b>Loom Review</b></div>
  <label for="workflow" class="muted">Workflow</label>
  <select id="workflow" aria-label="Workflow"></select>
  <span class="chip budget" id="runbudget" hidden></span>
  <span class="sp"></span>
  <div class="viewsw" id="layoutSwitch" role="group" aria-label="Layout">
    <button class="vbtn" id="layoutSplit" type="button" aria-pressed="true" data-layout="split">Split</button>
    <button class="vbtn" id="layoutOutline" type="button" aria-pressed="false" data-layout="outline">Outline</button>
    <button class="vbtn" id="layoutList" type="button" aria-pressed="false" data-layout="list">List</button>
  </div>
  <div class="viewsw" id="paneSwitch" role="group" aria-label="Panes">
    <button class="vbtn" id="paneFindings" type="button" aria-pressed="false">Findings</button>
    <button class="vbtn" id="paneBoth" type="button" aria-pressed="true">Both</button>
    <button class="vbtn" id="paneGraph" type="button" aria-pressed="false">Graph</button>
  </div>
  <button class="btn" id="rerun" type="button">Re-run review</button>
  <button class="btn" id="acceptAll" type="button" hidden>Accept all existing…</button>
  <button class="btn primary" id="fixAll" type="button" hidden>Fix all safe</button>
</header>
<div class="banner" id="banner" role="status" aria-live="polite" hidden></div>
<section class="strip" id="strip" aria-label="Summary"></section>
<section class="controls" id="controls" aria-label="Filter and group the findings">
  <input class="input" id="filter" type="search" aria-label="Filter findings" placeholder="Filter by text, rule, agent or file">
  <div class="viewsw" role="group" aria-label="Group by">
    <button class="vbtn" id="groupSeverity" type="button" aria-pressed="true">Severity</button>
    <button class="vbtn" id="groupLens" type="button" aria-pressed="false">Lens</button>
    <button class="vbtn" id="groupRule" type="button" aria-pressed="false">Rule</button>
    <button class="vbtn" id="groupFile" type="button" aria-pressed="false">File</button>
  </div>
  <button class="chip chipbtn" id="clearNode" type="button" hidden></button>
  <span class="mono muted small" id="fcount"></span>
  <span class="sp"></span>
  <button class="ghost" id="showHandled" type="button" hidden>Show handled</button>
  <button class="ghost" id="showNotEvaluated" type="button" hidden>Show not evaluated</button>
</section>
<main class="main layout-split pane-both" id="main"></main>
<div class="loading" id="loading"><span class="muted">Reviewing the script…</span></div>
<footer class="footer" id="footer"></footer>
<script nonce="${nonce}" src="${uri('graph-model.js')}"></script>
<script nonce="${nonce}" src="${uri('views.js')}"></script>
<script nonce="${nonce}" src="${uri('review-model.js')}"></script>
<script nonce="${nonce}" src="${uri('review.js')}"></script>
</body>
</html>
`;
}
