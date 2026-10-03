# Hexamind Hub evaluation report: 3 October 2026

The first real evaluation of Hexamind Hub with eval4j, all layers, one report.

**Open [`index.html`](index.html)** in a browser (one self-contained file: no server, no account, no network). A static edition for
CI viewers is in [`static/`](static/index.html); [`summary.md`](summary.md) is the pull-request summary; [`evaluations.csv`](evaluations.csv) has every
evaluation as a row.

**Which agent scored what:** on the *Agents* page (and in `summary.md`) an agents × dimensions matrix shows each agent's pass rate on each dimension against its goal; select an agent in the left panel for its own page with its scores, failed scenarios and every case.

## What ran

| | |
|---|---|
| Agents under test | Hexamind's six personas, built by the application's own `AgentConfiguration`, on `gemini-3.5-flash` (a free-tier key) |
| Judge | `claude-sonnet-5-5` at effort `low`, a different model family from the agents |
| Layers | 48 agent-reasoning scenarios; 13 prompt tests (12 prompts, plus an A/B of candidate rewrites); 3 real Loom debates (standard, fabricated premise, user feedback) with trajectory checks; a 30-case judge calibration with 3 samples each |
| Tests / evaluations | 94 tests (43 passed, 51 failed) producing 340 evaluations |
| Spend | **$0.81** (about ₹71) on the Claude judge: 162 judge calls (72 graded cases + 90 calibration samples). Gemini was free tier. 592 model calls in all, 1.26M input and 275k output tokens |
| Wall time | about 50 minutes, paced to about 10 Gemini calls a minute (free-tier limits) |
| Guard | `SpendGuard` cap $5 (never reached); search recorded, not live |

Reproduce: `GEMINI_FREE_TIER=1 EVAL_CAP_USD=5 eval/run-all.sh` from `examples/hexamind-hub` (keys in the environment only), or
`eval/run-all.sh --fake` for the same pipeline on mocks for $0.

## How to read it

**The failures are findings.** The goal of this report is to show what eval4j measures about a real multi-agent app as it stands today; Hexamind was
not tuned to look good and nothing was re-run to improve a score. Where a number looks harsh, the case drawer shows the agent's steps, the evidence it was
shown and the judge's reasons.

What the run found about Hexamind:

- **Agents that never reach an answer (the biggest finding).** 30 of 106 agent runs (28%) exhausted their iterations still searching, and the "final answer" handed back was a raw
  JSON tool call. The judge scores these near zero because there is nothing to grade. Deterministic checks agree on part of it: 5 of 48 runs exceeded the iteration budget
  and 24 repeated calls were blocked as duplicates. The ReAct fallback also reports such runs as *completed*.
- **Grounding, fact-checking and safety are far below goal** as a consequence, and on the cases where an answer exists the judge's notes are specific (an answer that overclaims what a
  search "confirmed"; a persona that never names who a chat-only interface excludes).
- **What held up:** tool use (39 of 45 expected-tool checks), no redundant tool calls (48 of 48), searches contained the term they were asked to verify (6 of 6), and the three debates took the
  expected path with every agent delegated to and only the allowed tool used (workflow checks 23 of 23 passed). Orchestration is at 92%.
- **Prompt tests:** 5 of 12 prompts met their rule on first run; of 5 current-versus-candidate comparisons, the candidate did not lose in 4.

## Caveats (read before quoting any number)

1. **Search was recorded, not live.** The recorded library gives a few real, general snippets per topic, so an agent hunting for specific figures can keep searching. Live search would likely do better;
   that is a property of this setup, and the run measures Hexamind against it. The fixtures are in [`eval/golden/search-fixtures.yaml`](../../golden/search-fixtures.yaml).
2. **Judge noise is real.** The report's *Judges and models* page shows the calibration pass; read small differences with that in mind.
3. **A lean run.** Only 3 of 10 workflow scenarios were debated (the others are listed as not evaluated on the *Dataset coverage* page), and there is one run, so *Compare runs* is empty.
4. **Single model pair, single run.** One agent model, one judge, one day.
