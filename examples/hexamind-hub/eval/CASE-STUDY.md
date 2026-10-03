# Case study: evaluating a six-agent app with eval4j

**Hexamind Hub** is a real multi-agent application: six AI personas debate a question over five rounds, a moderator checks whether the question
contains a term that does not exist, and a coordinator writes one consensus. We pointed eval4j at it, on real models, and went from "no tests" to a
full dashboard of quality, cost and behaviour in a single run that cost **$0.81**.

[![The report: each quality dimension against its goal](../../../eval4j/docs/images/hexamind-overview.png)](reports/2026-10-03/index.html)

**[Open the interactive report](reports/2026-10-03/index.html)** · [Short version](../../../eval4j/docs/REAL-EXAMPLE.md) · [How to do this for your own workflow](../../../eval4j/docs/guide/README.md)

## What we set out to do

Evaluate every layer of the app the way a team shipping it would: each agent's reasoning, each prompt, the workflow's trajectory, and the judge's own reliability. Gemini
ran the agents; Claude judged them. The goal was a report a stakeholder can read, not a score to be proud of, so the app was **measured as it stands, not tuned**.

## What eval4j gave us

- **One dataset, every layer.** 70 golden scenarios in YAML (a rubric, the tools expected, the quality dimensions each speaks to) drove the agent tests, the prompt tests and the
  trajectory tests. Dimensions (fact-checking, grounding, safety, persona fidelity, orchestration and more) came from the dataset, so the dashboard filled itself.
- **Ordinary tests.** Each layer is a JUnit test: a reasoning test class is about 60 lines, a prompt test about 95, a trajectory test about 105. Deterministic checks (did it finish, did it use the right tool, did it search
  for the right term, how many times was each agent called, did the workflow take the expected path) run first and are free; the judge is the one paid call per case.
- **Free first, then real, then cheap to repeat.** The same suite ran on mocks for $0 (the whole pipeline, 94 tests and the report, in about 17 seconds), then for real under a spend cap, with agent runs and judge
  verdicts cached so a repeat pays only for what changed. The `eval4j.testing` helpers (`SpendGuard`, `ScriptedClient`, `FakeJudge`, `AgentReplay`, `RecordedSearchTool`) ship in the library.
- **A dashboard with no setup.** One self-contained HTML file: quality dimensions against goals, an agents-by-dimension matrix with a page per agent, every agent run as a step-by-step trace, the debates' path against
  the expected path, judge reliability, cost and evidence.

## How the app was tested

| Layer | What ran | Models |
|---|---|---|
| Agent reasoning | 48 scenarios across the six personas: in-lane, fabricated premise, time-sensitive, cross-prong, source labelling, injected instruction, underspecified | Gemini agents, Claude judge |
| Prompts | 12 prompt tests, plus an A/B of candidate rewrites judged in both orders | same |
| Trajectory | the debate's path, branches, round counts, call count and budget stop on a scripted model (free, every build); three real debates on the Loom workflow | scripted, then real |
| Judge calibration | 30 cases judged three times each | Claude |

Search was recorded, not live: a library of short public facts answers each query by pattern, and a fabricated term finds nothing, on purpose. That keeps the run deterministic and gives the grounding judge the
same text the agent saw.

## The numbers (measured)

| | |
|---|---|
| Tests / evaluations | 94 tests producing 340 evaluations, 339 evaluated fresh |
| Spend | **$0.81** (about ₹71): 162 judge calls (72 graded cases and 90 calibration samples); Gemini on a free tier |
| Volume | 592 model calls, 1.26M input tokens and 275k output tokens in total |
| Judge | Claude Sonnet 5.5; mean latency about 3.3 s per call; no failed calls |
| Wall time | about 50 minutes, paced to about 10 Gemini calls a minute for free-tier limits |
| Free dry run | the full pipeline and report on mocks in about 17 seconds |
| Report | one 1.3 MB self-contained HTML file, plus a static edition, `summary.md` and CSV |
| Safety net | a $5 spend cap and per-stage ceilings; the cap was never reached |

## What it found

The evaluation surfaced behaviour that a handful of manual runs would not have shown:

- **Agents that never reach an answer.** 30 of 106 agent runs (28%) hit their iteration limit still searching, and the "answer" was a raw tool call. The judge scored these near zero, which pulled down
  grounding, fact-checking and safety. The runs were also reported as completed.
- **Honesty and grounding are the weak dimensions;** efficiency is the only one every agent meets (82% to 94% against an 80% goal).
- **What held up:** no redundant tool calls (48 of 48), searches contained the term they were asked to verify (6 of 6), tools used where expected (39 of 45), and all three real debates took the expected path with every agent
  delegated to and only the allowed tool used.
- **Prompts:** 5 of 12 met their rule on the first run; in 4 of 5 comparisons the candidate rewrite did not lose.
- **Which agent, on what:** the matrix shows it directly. For example, sasha, casey and rahul scored 0% on safety, while alex came closest overall (55%).

Along the way the same evaluation caught problems in the *workflow* (a variable named like an ordinary word was being substituted inside prompts, which silently corrupted a refinement step) before any money was spent
on it.

## Caveats

Search was recorded and thin, which affects how agents behave; judge verdicts are noisy (the report's Judges page shows a calibration pass); only 3 of 10 workflow scenarios were debated; and it is one run on one model pair.
Read each number with that in mind. The full list is in the [report README](reports/2026-10-03/README.md).

## Reproduce it

```
GEMINI_FREE_TIER=1 EVAL_CAP_USD=5 eval/run-all.sh      # real run; keys in the environment only
eval/run-all.sh --fake                                  # the same pipeline on mocks, for $0
```

Everything here is in [`examples/hexamind-hub/eval`](.): the [spec](SPEC.md), [run plan](RUN-PLAN.md), [cost model](COST.md), the [golden dataset](golden) and the [workflow](hexamind.loom).
