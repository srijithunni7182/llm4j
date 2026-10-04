# 9. Go live: real APIs, budgets and cost checks

**Goal:** run the workflow on real models in stages, with limits at three levels, and compare the bill with your model.

## Three nets, from outside in

1. **Provider limit** (their dashboard): the one thing no bug in your code can bypass. Set it before anything else, below what you could afford to lose.
2. **Your cap** (`SpendGuard`, or Loom's `--max-cost`): counts real tokens and stops the whole run. Set it below the provider limit.
3. **Per-step budgets** (Loom `budget`, per agent, per call, per loop): stop one runaway step, not the whole run.

```loom
budget { tokens: 200000  calls: 150  warn_at: 80% }                          // whole run
agent Writer { model: "gemini/gemini-2.5-flash"  budget { tokens: 20000  per_call: 2000 } }
delegate "Draft {topic}" to Writer -> draft budget 5000 tokens on_failure { note "Out of budget: {_error}" }
loop until (review.verdict == "OK") max 5 budget 30000 tokens { ... } on_exhausted { note "Stopped after {_loopRounds} rounds" }
rate_limits { on_limit: suspend  max_wait: 24h  max_resumes: 50 }
```

`when_exhausted` is `stop` (default), `suspend` (a durable run pauses and `weave resume` continues) or `ask`.
Costs need a price table: `prices.properties` with lines like `gemini/gemini-2.5-flash = 0.30 / 2.50` (per million tokens, input / output).

## Run it

```
weave check hexamind.loom && weave audit hexamind.loom --fail-on medium     # free, first, always
weave run hexamind.loom --max-cost 0.50 --prices prices.properties --journal runs/debate-1
weave run hexamind.loom --max-tokens 50000 --max-calls 40 --trace            # trace events to stderr
weave resume runs/debate-1                                                   # continue a paused or failed run
```

A spend table (calls, tokens, cost per agent) prints at the end of a budgeted run. Exit codes: 0 done, 1 failed, 2 bad options, 3 budget, 4 paused, 5 `--stop-at`.
A **journal** makes the run durable: re-running with the same journal replays finished steps for free, and `weave timeline <dir>` lists every step and its cost.

## Roll out in stages

1. **`check` and `audit`** (free).
2. **Smoke:** one tiny real run (one case, a small `--max-cost`). Confirm keys, tools, trace and spend report; compare *measured* tokens per call with your model.
3. **One real run of the cheapest representative case**, then the next. Stop if the first costs more than about twice the estimate: your model is wrong, so fix it before spending more.
4. **Everything else.** Keep the journal and the replay/judge caches so a failure does not re-pay for what finished.

## Check the cost afterwards

There is no `weave estimate`; keep a small cost model (calls x tokens x price) and compare. Hexamind's is [`cost_model.py`](../../examples/hexamind-hub/eval/cost_model.py): expected a full debate at
about 121 model calls, measured against the spend report. When reality and model disagree, change the model, not just the cap.

Rate limits: a 429 with a short reset is waited out inside the call; a long one reaches Loom's `rate_limits`. A free-tier key will hit them: pace the calls (Hexamind's evaluation used
about 10 a minute) rather than hammering.

## Keys and secrets

Environment variables (`env.NAME`) or an encrypted [secret store](../../ai-agent4j/wiki/Secret-Store.md) (`secret.NAME`, `weave run --secrets <file>`; you choose and protect its path and master key) in scripts, `GEMINI_API_KEY` / `ANTHROPIC_API_KEY` for the evaluation. Never in a script, a test or the repository; if a key is ever pasted somewhere shared, rotate it.
Secrets are scrubbed from results, traces, journals and audit logs.

## Gate

The smoke run and the first real run cost about what the model said, no limit was hit unexpectedly, the audit trail and trace look right, and provider limits and caps are set.
