# eval4j — Spec: Dashboard v2 ("Run Explorer")

Status: **Draft for review** · Scope: `eval4j` module, `report` package · No code written yet

## 1. Where we are

v1 (shipped on branch `ccr-24bca9ce-lieq8a`) is a **single-run report**: one HTML file showing KPIs,
metric table, trend, heatmap, per-case drill-down, and a "since previous run" list. It answers
*"how did this run go?"*

It does **not** yet answer the questions that make a team *live* in a dashboard:

| Question a user asks | v1 | v2 |
|---|---|---|
| "I changed the prompt. Did it help?" | only vs. *previous* run, no side-by-side | **Compare any two runs** |
| "Which tests are flaky vs. really broken?" | no | **Stability** per case from history |
| "What did this run cost me?" | no | **Cost & tokens** (judge + system under test) |
| "Why is Faithfulness low?" | list of failures | **Failure clustering** by reason |
| "Show me everything on one screen for the whole project" | one run only | **Runs index** (all runs, one page) |
| "Can I share a link to one failing case?" | anchor only | **Permalinks** + copy-link |

## 2. Principles

1. **Free, local, no account.** Output is static files. No server, no telemetry, no network. This is
   the differentiator against hosted dashboards.
2. **One file you can email.** Every page is self-contained (inline CSS/SVG, no external requests).
3. **Complete without JavaScript.** JS only enhances (filter, sort, compare pickers).
4. **Reads from JSON, never from the run.** Every file is re-renderable from `eval4j-report.json`
   (+ history). This keeps `EvalReportCli` honest.
5. **Additive.** No breaking changes to `EvalRecord`, `HistoryEntry`, report JSON.
6. **Escape everything.** Model output is hostile input; same rules as v1.

## 3. Information architecture

Two kinds of page, both static, cross-linked by relative links:

```
report-dir/
  index.html            ← NEW  Runs index (project home)
  runs/<runId>.html     ← v1 report, now one per run (kept, not overwritten)
  compare/<A>..<B>.html ← NEW  Run comparison (generated on demand by CLI, and for latest-vs-previous automatically)
  eval4j-report.html    ← alias to the latest run page (backwards compatible path)
  eval4j-report.json, eval4j-junit.xml, eval4j-summary.md, eval4j-report.csv  (unchanged)
  eval4j-history.jsonl  ← extended (see §6)
```

Navigation bar on every page: **Runs · This run · Compare · Stability**.

## 4. Mockups

### 4.1 Runs index (`index.html`) — NEW

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ eval4j   Runs   Stability                                          ◐ Theme       │
├──────────────────────────────────────────────────────────────────────────────────┤
│  Support-bot evals                      last 40 runs · branch: all ▾  suite: all ▾│
│                                                                                  │
│  Pass rate over time                                                             │
│  100% ┤                                                                          │
│   80% ┤      ●────●        ●───●                                                 │
│   60% ┤ ●───●       ╲  ●──●     ╲●  ← latest                                     │
│   40% ┤              ●                                                           │
│       └─────────────────────────────────────────────────────────────────────     │
│        (hover: run, commit, pass rate · click a point to open that run)          │
│                                                                                  │
│  ┌ Runs ────────────────────────────────────────────────────────────────────┐    │
│  │ ☐ │ Status   │ Run / commit      │ When       │ Pass  │ Avg  │ Cost │ Δ  │    │
│  │ ☑ │ ● FAIL   │ #8  9f2c4e1  main │ 2h ago     │ 64%   │ 0.72 │ $0.41│ −15│    │
│  │ ☑ │ ● PASS   │ #7  3ab91d0  main │ yesterday  │ 79%   │ 0.80 │ $0.39│ +2 │    │
│  │ ☐ │ ● PASS   │ #6  77c0e52  feat │ 2 days ago │ 77%   │ 0.78 │ $0.40│ −1 │    │
│  └──────────────────────────────────────────────────────────────────────────┘    │
│  [ Compare selected (2) ]                                                        │
└──────────────────────────────────────────────────────────────────────────────────┘
```

- Selecting exactly two runs enables **Compare**; without JS each row has a "compare with previous" link.
- Status dot: green = all passed, red = failures, amber = pass but regressed vs. baseline.

### 4.2 Run page (v1, refined)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ eval4j  ● FAILING  run #8 · 9f2c4e1 · 2m41s      Runs  This run  Compare  Stability│
├──────────────────────────────────────────────────────────────────────────────────┤
│ ┌Evals──┐ ┌Passed──┐ ┌Pass rate┐ ┌Avg score┐ ┌Tests──┐ ┌Cost───┐ ┌Tokens──┐       │
│ │  72   │ │ 46/72  │ │  64%    │ │  0.717  │ │ 17/18 │ │ $0.41 │ │ 182k   │       │
│ └───────┘ └────────┘ └─────────┘ └─────────┘ └───────┘ └───────┘ └────────┘       │
│                                                                                  │
│ Top failure reasons  (NEW)                                                       │
│ ┌──────────────────────────────────────────────────────────────────────────┐     │
│ │ ████████████ 12  cites a figure not present in retrieved context          │     │
│ │ ██████ 6        answer ignores part of the question                       │     │
│ │ ███ 3           tone too informal                                         │     │
│ └──────────────────────────────────────────────────────────────────────────┘     │
│                                                                                  │
│ Since previous run · Metrics · Trends · Heatmap · Evaluations · Tests  (as v1)   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 Evaluation row, expanded (v1, plus new bits marked ★)

```
▾ FAIL  refund window  SupportBotEvalTest   Faithfulness   ███░░░ 0.54 ≥0.70
  ┌ Why it failed ───────────────────────────────────────────────────────────┐
  │ States 30 days; retrieved policy says 14 days.                           │
  └──────────────────────────────────────────────────────────────────────────┘
  Input · Actual output · Expected output · Retrieved context (2)     (as v1)
  ★ Score history of this case:  0.81 0.79 0.84 0.77 0.54   ▁▂▃▂▇  (stable → just regressed)
  ★ Stability: Reliable (5/5 earlier runs passed)          ★ Tokens: 1.2k in / 0.3k out · $0.004
  ★ [ Copy link ]   [ Compare with previous run ]
```

### 4.4 Compare two runs — NEW (`compare/A..B.html`)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Compare   Baseline: #7 3ab91d0 (main)   ⇄   Candidate: #8 9f2c4e1 (feat/prompt-v3)│
├──────────────────────────────────────────────────────────────────────────────────┤
│ Verdict:  ▼ WORSE   pass rate 79% → 64% (−15 pts) · 12 new failures · 1 fixed    │
│                                                                                  │
│ Metric              Baseline   Candidate    Δ         Distribution (B ▏C)        │
│ Faithfulness          0.79       0.81      +0.02      ▂▃▅▇ ▏▂▃▅▇                 │
│ Contextual Precision  0.69       0.60      −0.09 ▼    ▃▅▇▃ ▏▇▅▃▂                 │
│ Tone                  0.78       0.70      −0.08 ▼                                 │
│                                                                                  │
│ Per-case scatter (each dot = one case; above the line = candidate better)        │
│  cand ↑        ·  ·                                                              │
│   1.0 ┤       · ·   ╱                                                            │
│   0.5 ┤   ●●●  ·  ╱   ●=regressed (red)  ○=improved (green)                      │
│   0.0 ┼──────────────→ baseline                                                  │
│                                                                                  │
│ Cases that changed  [ All ▾ Regressed ▾ Improved ▾ ]   sorted by |Δ|             │
│ ┌────────────────────────────────────────────────────────────────────────────┐   │
│ │ cancel subscription · Faithfulness   0.94 ─────────▶ 0.41   ▼ −0.53        │   │
│ │   baseline answer  │ candidate answer   (side-by-side text, word diff)     │   │
│ └────────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

Needs per-case records of **both** runs, so run JSON is kept per run (see §6).

### 4.5 Stability — NEW

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Stability   last 20 runs                  filter: [Flaky ▾]  metric: [all ▾]     │
│                                                                                  │
│  Case                                  Last 20 runs        Pass%  σ     Verdict  │
│  cancel subscription · Faithfulness    ■■■■■■■□■■□■■□■■■■■■   80%  0.21  ⚠ Flaky │
│  reset password · Contextual Precision □□□□□□□□□□□□□□□□□□□□    0%  0.03  ✖ Broken│
│  refund window · Tone                  ■■■■■■■■■■■■■■■■■■■■  100%  0.02  ✔ Stable│
│  (■ pass  □ fail  — hover a cell: run, score, commit; click: open that case)     │
└──────────────────────────────────────────────────────────────────────────────────┘
```

Verdict rules (configurable): **Broken** = failing in the last N consecutive runs; **Flaky** = both
passes and failures in the window *and* score σ above a threshold; **Stable** otherwise; **New** if
fewer than 3 runs of history.

## 5. Features and acceptance criteria

| ID | Feature | Acceptance criteria |
|---|---|---|
| D1 | Runs index | Lists every retained run, newest first; pass-rate chart; links to each run page; works with JS off |
| D2 | Per-run pages kept | A new run no longer overwrites older run pages; `eval4j-report.html` still points at the latest |
| D3 | Compare | Any two runs; metric table; scatter; changed-case list; side-by-side output with word diff; verdict |
| D4 | Stability | Pass/fail strip per case over last N runs; Broken/Flaky/Stable/New; filter by verdict |
| D5 | Failure clustering | Failure reasons grouped by normalised text similarity (no LLM call); counts link to cases |
| D6 | Cost & tokens | Optional per-evaluation token counts and price table; run and case totals; absent data hides the UI |
| D7 | Permalinks | Every case has a stable id (hash of suite+test+metric), not an index, so links survive re-runs |
| D8 | Case history | Expanded row shows that case's score trend across runs |
| D9 | Retention | Configurable (`eval4j.retention.runs`, default 40); oldest run pages and records pruned |
| D10 | CLI | `EvalReportCli compare A B`, `EvalReportCli index <dir>` regenerate pages from stored JSON |
| D11 | Accessibility | Keyboard navigable, colour never the only signal (icons + text), WCAG AA contrast, print stylesheet |
| D12 | Security | All dynamic text escaped; no inline data in script blocks; CSV formula neutralised (unchanged from v1) |

Explicitly **out of scope**: hosting, auth, comments, live updating, any network call, LLM calls to
summarise failures.

## 6. Data model changes (additive)

- `EvalRecord`: add `caseId` (stable hash), `inputTokens`, `outputTokens`, `costUsd` (all nullable).
- `HistoryEntry`: add `runFile` (relative path of that run's JSON), `branch`, `costUsd`.
- Per-run JSON stored under `runs/<runId>.json`; history stays the lightweight index.
- Old reports/history load unchanged; missing fields hide the related UI.

## 7. Cost & tokens (D6) — design note

`LLMClient` responses already carry usage in `ai-agent4j`; judge conditions can read it and pass it via
`EvalDetails`. Pricing is a user-supplied table (`eval4j.pricing.properties`: model → $/1M in/out);
no built-in prices (they go stale). Without a table, tokens show but cost does not.

## 8. Phasing

| Phase | Contents | Why this order |
|---|---|---|
| 1 | D2, D7, D9, D8 + data model (§6) | Foundation: per-run pages, stable ids |
| 2 | D1 Runs index, D4 Stability | Highest value from data we already store |
| 3 | D3 Compare | Needs phase 1 |
| 4 | D6 Cost, D5 clustering | Needs model usage plumbing; clustering is independent |
| 5 | D10 CLI, D11 polish | Hardening |

## 9. Open questions for review

1. **Compare vs. scatter**: is the per-case scatter worth the space, or is the changed-case list enough?
2. **Flaky definition**: is "both pass and fail in window + σ" right, or should it be a pass-rate band (e.g. 20–95%)?
3. **Failure clustering** without an LLM is a heuristic (token similarity). Acceptable, or skip until we can afford a judge call?
4. **Cost**: should pricing be user-supplied only (my proposal), or ship a default table?
5. **Branch awareness**: group/filter runs by git branch in the index (PR vs. main)? Requires reading branch from CI env.
6. **Retention**: 40 runs × per-run JSON could reach tens of MB with large outputs. Cap by size, or by count only?
7. **Naming**: "Run Explorer" or keep "dashboard"?
8. **Anything missing** you want from a DeepEval/Confident-style UI that you'd want in v2 (e.g. dataset view, prompt versions, annotations)?
