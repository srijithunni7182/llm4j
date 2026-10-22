# Cost and model choice, for a harness design

Use this when the user asks which models to use, how to make a harness cheaper, or what it will cost. A harness has a cost envelope as well as a
safety envelope; write both into `HARNESS.md`. The full procedure is `weave guide 16` if `weave` is installed.

## The rule

Pay for a model only where a model is needed, and for a strong one only where it is measured to be needed. In order of effect:

1. **The kind of step.** A deterministic step costs nothing. A classification (a label, a yes/no, a rating, from a fixed set) costs a fraction of a cent. A step that
   writes text costs the most.
2. **The model that runs it.** Two orders of magnitude separate a small and a large model, and the small one is often enough.
3. **How often it runs and how much it reads.** Loops, retries, long prompts, long histories and repeated samples multiply the bill.

## What to suggest first

| The step | Suggest first | Move up only if the user's cases show it fails |
|---|---|---|
| exact, or moves money, sends, writes | a deterministic step, no model | never a model |
| chooses a label, yes/no or rating (route, judge, is-this-risky) | a classifier: Jev if text may go to that hosted service and the user has a key, else a small chat model sampled 3 times | a larger chat model |
| pulls fields from text into a fixed shape | a small chat model with a required output shape | a mid-size model |
| writes a reply, summary or draft | a small or mid-size model | a larger model on the branches that need it |
| plans, reasons over many steps, reviews hard work | a large model, on that step only | n/a |
| grades quality in tests | a model different from, and at least as strong as, the one being graded, with cached verdicts | n/a |

Model names, prices and limits change. Do not quote a price from memory: ask which providers and keys the user has, check the current price list with them, and
write the figures and the date in the design record.

## An example of the shape (measured 2026-10-21, with its limits)

A study on 30 to 65 hand-labelled cases per task, written by the team, in one domain, compared Jev with Claude Opus 5.5 and Claude Haiku 5.5 (per million tokens,
input / output: Jev $0.042 / free, Haiku $0.10 / $0.50, Opus $4 / $20). Choosing a label: all three within one case of each other on routing (0.969, 0.969, 1.000) and equal on
judging (0.967); about $0.016, $0.04 to $0.07 and $1.6 per 1,000 decisions; p95 latency about 0.2 to 0.5 s, 1.5 to 2 s and 2.5 to 3 s. On telling destructive shell commands from safe
ones the cheapest was not the best (0.950, 1.000, 0.875), and Jev called `> /etc/passwd` safe with confidence 0.95. Differences of a case or two are noise. Use this to show the user why
to measure, never as a reason to pick a model.

## The procedure

1. Sort the steps (deterministic, classifier, agent). Say which need no model.
2. Ask: providers and keys, whether text may go to a hosted service (and which text), budget per run or month, volume, and how bad a wrong answer is per step.
3. Propose the cheapest candidate per step and one step up as the fallback, with a sentence of reason.
4. Measure on the user's own cases before they commit: a comparison of backends over labelled cases (accuracy, calibration, invalid replies, latency, cost per 1,000), or the golden
   dataset and a judge with a spend cap.
5. Decide by a rule written before the run: the cheapest model within 2 points of the best on accuracy, with calibration error 0.10 or less, and, for a step that gates something
   risky, accuracy at 80% coverage not below the best. Repeat the run; do not decide on one run or on one or two cases.
6. Record the choice, the numbers and the date, and when to re-run (a new model, a price change, a quality drop).

## The levers, biggest first

1. Take model calls out (a deterministic step or one classification instead of an agent; branch early so most traffic never reaches an expensive step).
2. Use the smallest model that passes, per agent.
3. Bound everything: a run budget, a budget on each looping agent, bounded loops and retries, and an outcome for each bound that is reached.
4. Send less: short prompts, trimmed history and compaction for long runs, only the fields a step needs, one identical shared prompt prefix.
5. Do not sample what does not need it (a repeated sample multiplies a chat-model classifier's cost and does nothing for Jev).
6. Cache in tests and run free first (mocks, scripted classifiers, recorded fixtures) before any live run under a cap.
7. Fall back instead of over-provisioning: cheap first, stronger only on failure.
8. Do not run what nobody reads: a scheduled run that finds nothing should find it cheaply.

## Estimate before paying

Cost of a step is (input tokens x input price + output tokens x output price) / 1,000,000, times the number of calls. Count the calls from the design (each agent step and classification is at
least one; a loop is up to its maximum times its body; a per-item step is once per item; retries and samples multiply), size the tokens from a small run, put the prices in a price table so the
spend report shows money, and set the first budget at about twice the estimate, then tighten it from what runs cost.

## Where cost and safety disagree

The cheapest step and the safest step for money are the same: a deterministic step. They disagree on classifiers that gate risk. A cheap classifier is fine to route and to judge, but must not be the
only thing between a model and a destructive action. Spend the money there: a deterministic check of the facts, a second independent check, or a person.

## For the design record

Fill in: for each step, its kind and model, the measured numbers and date; the estimated cost per run and per month at the stated volume; the bound that stops it if it runs away and what happens then;
and when the choice will be re-measured.
