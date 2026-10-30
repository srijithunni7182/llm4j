#!/usr/bin/env bash
# Makes the tutorial's web page from the markdown in docs/tutorial and puts it where the jar picks it up
# (src/loom/ai-agent4j-loom/src/main/resources/guide/loom-tutorial.html, shown by `weave guide tutorial`).
# GuideTutorialPageTest fails when the markdown has changed and this has not been run. Needs Node 18 or newer and a
# Chromium to draw the diagrams with (CHROMIUM_PATH, or `npx playwright install chromium`, or an installed Chrome).
# The dependencies (marked, mermaid, playwright-core, all pinned) are installed in a temporary folder, never in the repository.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/src/loom/ai-agent4j-loom/src/main/resources/guide/loom-tutorial.html
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$ROOT/scripts/tutorial-html.mjs" "$WORK/"
(cd "$WORK" && npm init -y >/dev/null && npm install --silent --no-audit --no-fund marked@12.0.2 mermaid@10.9.1 playwright-core@1.49.1 && mkdir -p "$(dirname "$OUT")" && node tutorial-html.mjs "$ROOT" "$OUT")
