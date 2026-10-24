# Loom extension screenshots

The pictures in the VS Code extension README and the guide. They are taken from the real panel with a stand-in for the VS Code API, using the laptop-doctor sample (`src/loom/vscode-loom/test/fixtures/laptop-doctor.graph.json`) and a small always-running workflow.

Retake them after changing the panel:

```
cd src/loom/vscode-loom && npm ci && npx tsc -p tsconfig.test.json && cd ../../..
node scripts/take-graph-screenshots.js
```

It needs `playwright` with Chromium installed.

The `review-*.png` pictures (the Review panel in its three layouts, light and dark) are taken from the real review page with real `weave review` output (`src/loom/vscode-loom/test/fixtures/review/`):

```
node scripts/take-review-screenshots.js
```
