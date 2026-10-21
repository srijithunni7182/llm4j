# 16. Cost and model choice

**Goal:** every step runs on the cheapest thing that does its job well enough, the cost of a run is estimated before it is paid for, and the user chooses models from evidence about their own cases, not from a name.

Use this chapter whenever the user asks "which model should I use?", "how do I make this cheaper?" or "what will it cost?". It is a reference for **suggesting** models: it tells you what to ask, what to propose and how to check the proposal.

## The rule: pay for a model only where a model is needed, and for a strong one only where it is measured to be needed

Cost comes from three choices, in this order of effect:

1. **What kind of step it is** (chapter 1: task, classifier or agent). A task costs nothing. A classification costs a fraction of a cent. An agent that writes costs the most.
2. **Which model runs it.** Prices differ by two orders of magnitude between a small and a large model, and the small one is often good enough.
3. **How often it runs and how much it reads.** Loops, retries, long prompts, long histories and many samples multiply the bill.

Do them in that order. Changing a model on a step that should have been a task saves little.

## A first suggestion for each kind of step

| The step | Suggest first | Move up only if the user's cases show it fails |
|---|---|---|
| exact, or moves money, sends, writes | a **task** (no model) | never a model |
| chooses a label, yes/no or a rating (route, judge, is-this-risky) | an **`intentclassify`** step: Jev if the user accepts a hosted service for that text and has a key, otherwise a small chat model with `samples: 3` | a larger chat model for the classifier |
| pulls fields out of text into a fixed shape | a small chat model with `expecting { ... }` | a mid-size model |
| writes a reply, a summary, a draft | a small or mid-size model | a larger model on the branches that need it |
| plans, reasons over many steps, reviews hard work | a large model, on **that step only** | n/a |
| judges quality in tests (eval4j) | a model *different from* and at least as strong as the one being judged, with cached verdicts | n/a |

"Small", "mid-size" and "large" are relative to what the user's provider sells today. **Model names, prices and limits change. Do not quote a price from memory:** ask the user which providers and keys they have, check the provider's current price list with them, and write the figures in the price table (below). The numbers in this chapter are the ones measured on 2026-10-21 and are examples of the *shape* of the difference, not a price list.

## What was measured (an example, with its limits)

A study of the classifier on our own labelled cases (30 to 65 per task, written by us, in one product domain) compared Jev with Claude Opus 5.5 and Claude Haiku 5.5. Per million tokens, input / output: Jev $0.042 / free, Haiku 5.5 $0.10 / $0.50, Opus 5.5 $4 / $20.

| Task | Jev | Haiku 5.5 | Opus 5.5 | What it says |
|---|---|---|---|---|
| intent routing (65 hard cases) | 0.969 | 0.969 | 1.000 | the cheap models matched; the strong one was one case better |
| judging a criterion (30 cases) | 0.967 | 0.967 | 0.967 | all equal |
| destructive-command check (40 hard cases) | 0.950 | 1.000 | 0.875 | the cheapest was not the best; Jev missed `> /etc/passwd` with confidence 0.95 |
| cost per 1,000 decisions, routing | about $0.016 | about $0.04 to $0.07 | about $1.6 | about 100 times between the ends |
| p95 latency | about 0.2 to 0.5 s | about 1.5 to 2 s | about 2.5 to 3 s | |

Read it this way: for choosing a label the small models were as accurate as the large one on this data, at a hundredth of the price; and the large model is not automatically the safest (it refused several risky-looking prompts, and counted as invalid). Differences of one or two cases are noise. **Never recommend a model to a user because of this table; use it to show why they should measure.**

## How to suggest a model: the procedure

1. **List the steps and sort them** (task, classifier or agent). Say which need no model.
2. **Ask what they have and what they care about:** providers and keys, whether text may go to a hosted service (and which text), the budget per run or per month, the volume (runs per day), and how bad a wrong answer is on each step.
3. **Propose the cheapest candidate for each step** from the table above, and one step up as the fallback. Say why in a sentence.
4. **Measure it on their cases before they commit.** For a classifier: `ClassifierComparison` over their labelled cases, one contender per backend, which reports accuracy, calibration, invalid replies, latency and cost per 1,000 (a live run costs a few cents). For an agent step: the golden dataset and the judge of chapters 2 to 5, with a spend cap. Chapter 11 says how to build the cases with the user.
5. **Apply a stated rule, written down before the run.** A good one: take the cheapest model that is within 2 points of the best on accuracy, whose confidence is calibrated well enough to act on (expected calibration error 0.10 or less), and, for a step that gates something risky, whose accuracy at 80% coverage is not below the best. Do not pick on a single run or on a difference of a case or two; repeat the run.
6. **Write down the choice and the date,** with the numbers. Prices and models move: say when to re-run (a new model, a price change, a drop in quality in production).

## Make it cheaper: the levers, biggest first

1. **Take model calls out.** A step done by a task or one classification instead of an agent. Branch early so most traffic never reaches an expensive step (`alt` before the agent, not after).
2. **Use the smallest model that passes** (the procedure above). Different agents in one workflow can and should use different models.
3. **Bound everything.** A `budget` at the top and on each agent that loops (`budget { tokens: 200000 calls: 150 warn_at: 80% }`, `budget { tokens: 20000 per_call: 2000 }` inside an agent, `budget 5000 tokens` on a step), `loop ... max N`, and `retry` counts that mean something. A budget that is reached has an outcome (`on_failure`, `on_exhausted`, or `when_exhausted: suspend`); chapter 9 covers it.
4. **Send less.** Keep prompts short and the history trimmed: `context` and compaction for agents that loop on tools (chapter 14), only the fields a step needs, results of tools cut to what matters. Put the text every agent starts with in `shared_prompt` (identical, with no dates or run ids): a provider that charges less for the start of a prompt it has just seen can reuse it across agents. Loom only lays the prompt out that way; the saving is real when the shared text is long and the agents use the same model.
5. **Do not sample what does not need it.** `samples: 3` triples a chat-model classifier's cost. It is for making a chat model's confidence mean something; it does nothing for Jev.
6. **Cache in tests.** Judge verdicts and agent runs are cached by eval4j so a re-run pays only for what changed. Run free first (mocks, scripted classifiers, recorded fixtures), then live under a cap (chapter 5).
7. **Fall back, do not over-provision.** A routing policy can try a cheap model and fall back to a stronger one only on failure, instead of always paying for the stronger.
8. **Do not run what nobody reads.** Rate limits and schedules: a workflow on a timer that finds nothing should find it cheaply (a classifier or task first, an agent only when there is something to do).

## Estimate the cost before paying it

`cost of a step = (input tokens x input price + output tokens x output price) / 1,000,000`, times the number of calls. For a Loom run:

- **Count the calls** from the script: each `delegate` and `intentclassify` is at least one call; a `loop` is up to `max` times its body; a `for each` is once per item; `retry N` can add N; `samples` multiplies.
- **Size the tokens** from a mock or small live run: the trace and the spend report show each step's tokens. A classification was about 355 input and 65 output tokens in the study.
- **Put prices in a table** so the spend report shows money: `flow.prices` beside the script (or `--prices`, or `LOOM_PRICES`), one line per model, `model = input / output` in dollars per million tokens. A model with no price shows tokens and no cost; say so instead of showing zero.
- **Set the budget at about twice the estimate** for the first runs, then tighten it from what runs actually cost (chapter 9).

Example: 10,000 messages a day through one Jev classification each is about 3.6 million input tokens, about $0.15 a day at Jev's list price; the same through a small chat model is about $0.60 a day, and through a large one about $16 a day (study prices and token counts; confirm current prices first).

## Cost and safety pull in different directions in one place

The cheapest step is a task, and the safest step for money is a task, so they agree there. They disagree on **classifiers gating risk**: a cheap classifier is fine to route and to judge, but not to be the only thing between a model and a destructive action. Spend the money there: a task that checks the facts, or a second independent check, or a person.

<!-- weave check -->
```loom
// Different models for different jobs, and a ceiling on the run.
budget { tokens: 200000  calls: 60  warn_at: 80% }

agent Extractor { model: "gemini-2.5-flash"  system: "Pull out the fields asked for."  temperature: 0.1 }
agent Writer    { model: "gemini-2.5-flash"  system: "Write a short, polite reply."  temperature: 0.7  budget { tokens: 20000  per_call: 2000 } }

workflow Main(message) {
    delegate "Pull the order and the request out of: {message}" to Extractor -> fields expecting { order: string, request: string }
    delegate "Reply to the customer. Their request: {fields.request}" to Writer -> reply
    note "{reply}"
}
```

## Gate

For each step the user can say: why it is a task, a classifier or an agent; which model, and the measured numbers that justify it (accuracy on their cases, cost per 1,000, date); what it costs per run and per month at their volume; and which bound stops it if it runs away. Prices are in the price table, not in anyone's head.
