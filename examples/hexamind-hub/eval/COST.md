# What evaluating Hexamind Hub will cost

Estimates for [`SPEC.md`](SPEC.md): Gemini runs the agents, Claude judges. Reproduce or change any number with [`cost_model.py`](cost_model.py) (`python3 cost_model.py`). These are **model-based estimates, not measurements**: no real call has been made. Replace the assumptions with real token counts after day 2 of the plan; the eval4j report's *Cost and evidence* page shows tokens per judge.

## In rupees, and the lean plan (read this first)

The plan below the line costs about **$180 to set up and $180 a month**, roughly **₹16,000 each** at about ₹88 to ₹90 per dollar (check today's rate). That is too much for a first pass, and most of it is avoidable. The **lean plan** gets the same coverage for about a sixth:

| | Lean, expected | Lean, high case | In rupees (expected, before tax) |
|---|---|---|---|
| **Setup** (about 2 weeks) | **$20** (Gemini $12, Claude $9) | $33 | **about ₹1,800** |
| **A month afterwards** | **$30** (Gemini $21, Claude $9) | $50 | **about ₹2,700** |
| One release run | $6 | $10 | about ₹530 |
| One pull-request build | $0.65 | $1.04 | about ₹60 |

What makes it lean (each is a design change in [`SPEC.md`](SPEC.md)):

1. **Test the debate's path with a scripted model, not a real one.** Whether the workflow takes the right branch, runs five rounds, stops at a budget and routes feedback is logic, not language. A scripted (stub) model checks all of that for **$0**. This removes the biggest cost: the ten real debates were 74% of a full run.
2. **Run only three real debates per release** (standard, debunk, refinement) to judge consensus quality: about $2 instead of $8.60.
3. **Record and replay agent outputs.** Store each scenario's agent output keyed by scenario, prompt version and model. A pull request re-runs only what changed (about 20%); everything else replays for free. Judge verdicts are already cached by eval4j.
4. **Develop on the free tier or Flash-Lite.** Google's free tier covers Flash and Flash-Lite models with rate limits (about 5 to 15 requests a minute, up to roughly 1,000 a day) and with the catch that Google may use free-tier prompts to improve its products. Our scenarios are synthetic, so that is acceptable; do not paste real customer text. Gemini 3.5 Flash-Lite is $0.30 in and $2.50 out per million tokens, five times cheaper on input than Flash. Use the real Flash model only for the weekly release run. ([pricing summary](https://www.cloudzero.com/blog/gemini-pricing/); confirm free-tier eligibility of the exact model at ai.google.dev.)
5. **Claude is a small part of the bill.** A judge call on Claude Sonnet 5.5 is about $0.002 (about ₹0.2). Keep it as the judge. If you want to trim more, the cheaper Claude Haiku 4.5 would save about $4 of the $9 setup judge cost, at some loss in rubric accuracy.

**What to load for the lean plan:** about **$15 on Google** and **$25 on Anthropic** (₹1,300 and ₹2,200) covers the whole setup phase with room to spare; Anthropic credits are bought in advance, so start with the smallest amount it allows. Tax and card charges can add roughly 20 to 25% (Indian GST on imported services plus forex markup); check what each provider and your card charge.

Everything after this heading is the **full plan** for reference: it is what you would spend if you ran every scenario on real models every time.

---

## The full plan: how much to load

| | Google (Gemini) | Anthropic (Claude) | Total |
|---|---|---|---|
| **Setup phase** (about 2 weeks: 10 full runs, 2 calibration runs, 3 optimizer campaigns) | **$140** | **$40** | **$180** |
| **A normal month afterwards** (30 nightly samples, 20 PR builds, 4 weekly full runs) | $150 | $30 | $180 |
| Same, **without the nightly run** (PR builds and weekly full runs only) | $85 | $17 | $100 |

If you load **$250 on Google and $60 on Anthropic**, that covers the setup phase even if token use turns out about 1.7 times higher than assumed (the "high" case: $235 and $60). Start smaller: days 1 to 4 of the plan cost under $5 in total, and by then you will have real numbers.

Range (setup / month): low case $125 / $125, expected $180 / $180, high case $295 / $295. The spread is almost all Gemini token use.

## Prices used (USD per million tokens)

| Model | Role | Input | Output | Source |
|---|---|---|---|---|
| Gemini 3.5 Flash | agents under test | 1.50 | 9.00 (thinking tokens count as output); cached input 0.15 | [price listings](https://pricepertoken.com/pricing-page/model/google-gemini-3.5-flash), [OpenRouter](https://openrouter.ai/google/gemini-3.5-flash); Google's own page could not be opened from this environment, so **confirm at ai.google.dev/gemini-api/docs/pricing** |
| Claude Sonnet 5.5 | main judge | 2.00 | 10.00 | Anthropic's model table |
| Claude Haiku 4.5 | optional bulk judge | 1.00 | 5.00 | Anthropic's model table |
| Claude Opus 5.5 | calibration cross-check | 4.00 | 20.00 | Anthropic's model table |

Not included: SerpAPI (tests use recorded search fixtures, so no search calls), embeddings (negligible), and tax or currency conversion.

## Where the money goes

One **full debate** is 121 model calls (6 agents, 5 rounds, opinions, a coordinator, plus 36 knowledge-extraction calls): about 390k input and 60k output tokens, **$1.12**. A shortened debunk debate is about $0.25.

| Suite (full run) | Agent calls | Judge calls | Gemini | Claude | Share of the bill |
|---|---|---|---|---|---|
| Agent reasoning (48 scenarios) | 144 | 144 | $1.36 | $1.07 | 19% |
| Prompt tests (12 scenarios, current and candidate) | 48 | 48 | $0.48 | $0.34 | 7% |
| Trajectory tests (10 debates) | 925 | 40 | $8.64 | $0.68 | 74% |
| **Total** | 1,117 | 232 | **$10.47** | **$2.08** | **$12.55** |

The debates are three quarters of the cost. That is why the plan runs them only when orchestration or prompts changed, and why `BUILD` (changed cases only) matters. **Judging is cheap** (about $2 for a full run), so spending on the judge for better rigor is not where the bill is.

## Cost by run profile (expected case)

| Profile | Agent calls | Judge calls | Gemini | Claude | Total |
|---|---|---|---|---|---|
| `FAST`: deterministic checks only, agents still run | 1,117 | 0 | $10.47 | $0 | $10.47 |
| `BUILD`: about 20% of cases changed, rest reused | 223 | 46 | $2.09 | $0.42 | $2.51 |
| `SAMPLE`: 20% seeded sample | 223 | 46 | $2.09 | $0.42 | $2.51 |
| `FULL`: everything once | 1,117 | 232 | $10.47 | $2.08 | $12.55 |
| `FULL` + calibration (3 judge samples; Opus on 20%) | 1,117 | 694 | $10.47 | $6.74 | $17.21 |

`FAST` is not free because the agents still run on Gemini; it only skips the judge. A pure-offline check (dataset validation) is free.

One agent scenario costs about $0.03 on Gemini; one judge call about $0.002 on Claude Sonnet 5.5. An optimizer campaign (about 150 rollouts) is roughly $6.

## What would make it cost more or less

| Lever | Effect |
|---|---|
| **Gemini thinking tokens** (assumed 400 per call, billed as output) | the biggest uncertainty. At 0 the Gemini cost drops by about 30%; at 1,000 it rises by about 45%. Measure on day 2. |
| Live search results instead of recorded fixtures | observations are about 3 times larger, raising agent input cost (this is most of the "high" case) |
| Debates in every `BUILD` | about +$8 per pull request; keep them for orchestration or prompt changes |
| More judge samples | linear in judge cost; only calibration needs more than one |
| Prompt caching (Gemini cached input is a tenth of the input price) | the agent persona and tool schema (about 1,800 tokens) are resent on every call, so caching could cut agent input cost noticeably. Not assumed here. |
| A pro-class model for the coordinator or Rahul | small: they are one or a few calls per debate |

## Guard rails so a bug cannot burn credits

- `-Deval4j.judge.budgetUsd=<n>` stops judging once spent (the remaining cases become "not evaluated", never passed).
- Loom's `budget { tokens: 900000 calls: 220 }` in `hexamind.loom` caps a debate at roughly twice a normal one (a normal debate is about 450k tokens).
- `maxIterations` stays at 10 and 12 per agent; the trajectory tests assert it.
- Set a spending limit or alert on both provider accounts before the first live run.
