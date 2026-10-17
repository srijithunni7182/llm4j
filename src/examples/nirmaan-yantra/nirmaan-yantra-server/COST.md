# What a run costs, and what stops it

Because the workflow is a straight line with one loop, the number of model calls has a hard upper bound. Everything below is
arithmetic on the script, except the first number, which a test pins.

## Calls

| Stage | Smooth run | Worst case (no retries) |
|---|---|---|
| Plan: Aditi, Rishi | 2 | 2 |
| Prepare: Vihaan (red test) and Drishti (end to end suite), in parallel | 2 | 2 |
| Build: Vihaan writes the code, then `Fix` the unit tests | 1 | 1 + 3 |
| Review: Vishnu; a rejection adds one polish and a `Fix` | 1 | 1 + 1 + 3 |
| Assure: the clean build is one command with no model; `Fix` the end to end suite, `Fix` the unit tests again | 0 | 3 + 3 |
| Release: Vishnu | 1 | 1 |
| **Total** | **7** | **20** |

* **Smooth = 7** is asserted by `FactoryTrajectoryTest.aSmoothRunIsOneStraightLineFromIdeaToZip`, so the table cannot drift from the script.
* **Worst case = 20.** `Fix` is the only loop, it makes at most 3 fix calls, and the script calls it 4 times. The clean build is deliberately not one of them: a build that fails after the tests pass is a person's decision, not a reason to loop on a model. After the third failed fix
  a person decides (continue or stop); the factory never loops on its own beyond that.
* **Retries:** each agent step has `retry 2` for malformed answers, which can triple a step: at most **60** calls.
* The run budget is `calls: 100`, above the 60 bound, so it only bites if the script is edited. When it does, a person is asked whether to
  extend it (`when_exhausted: ask`); nothing already paid for is lost.

## Tokens and money (estimates, not measurements)

No real model has been run. These use assumptions you should replace with `weave run`'s spend table after the first capped run:

* a typical call reads 4 to 8 thousand tokens and writes 1 to 3 thousand; a fix call on a project of a few files reads up to
  about 15 thousand (the code is capped at 60,000 characters) and writes up to about 8 thousand;
* Every agent runs on `gemini-2.5-flash` ($0.30 in / $2.50 out per million tokens, from Google's pricing page; output includes thinking
  tokens). `gemini-2.5-pro` is no longer available to new API keys, so it is not used. Putting Rishi and Vihaan on a stronger model
  would raise these figures several times over.

| | Calls | Tokens | Money |
|---|---|---|---|
| Smooth run | 7 | about 60 thousand | about $0.03 to $0.08 (more if the model thinks a lot) |
| Worst case | 20 | about 500 to 700 thousand | about $0.5 to $1 |

The run budget is `tokens: 1500000`, about twice the worst-case estimate, and each `Fix` loop also has its own `budget 250000 tokens`,
so one stubborn command cannot spend the run. Set a hard money cap yourself on the first runs:

```bash
weave run src/main/resources/main.loom -i idea_text="a command-line word counter" -i project_id=demo \
     --prices prices.properties --max-cost 1.00 --journal runs/demo
```

and set a spending limit with your provider as well: the script's budget is checked before each model call, not by your invoice.

## What is not counted

* Commands (builds, tests, installs) cost no tokens but take time and bandwidth; each is cut off after 5 minutes.
* Embedding and web search calls are not LLM calls and are not charged to the budget. Rishi and Vihaan may search the web.
