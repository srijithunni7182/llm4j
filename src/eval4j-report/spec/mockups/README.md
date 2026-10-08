# Mockups (visual reference)

High-fidelity, throwaway design mockups. They are the visual reference for [04-DASHBOARD-UI.md](../04-DASHBOARD-UI.md); they are **not** product code and must not be copied into the module.

| File | What it is |
|---|---|
| `interactive-edition.html` | The interactive dashboard: left navigation by test family, overview, dimension drill-down, case drawer, run comparison, prompts / agent reasoning / Loom trajectory views, coverage, cost and evidence, judges and models. Open it in a browser; the URL hash opens a view (`#compare`, `#agents-reasoning`, `#workflows-trajectory`, `#grounding`). |
| `static-edition.html` | The static edition for locked-down viewers (Jenkins): no scripts, no inline styles, anchor navigation, expandable sections. In the real output the CSS lives in `eval4j-static.css`; here it is inlined so the file opens on its own. |

**All numbers are generated sample data** (a made-up support-bot evaluation). Nothing in them is a real run.

The interactive mockup loads IBM Plex from Google Fonts. The real report must make **no** network requests and uses the system font stack by default (see UI-14).
