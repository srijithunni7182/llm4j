# Probe P18: a measured coding-agent run

**Status: harness built; one smoke trial run; no result about any framework yet.**

The question: when a coding agent builds the same customer-support workflow in Loom, LangGraph or ADK, does the
feedback from `weave check` and `weave review` help it end up with safer behaviour? "Safer" is scored by running the
finished artifact against a fake model, fake sender and fake person: one send normally (S0), no second send after a
lost reply (S1), no send after a person says no (S2), and no send of a reply that promises a refund (S3).

The agent has file tools and a short allow-list of commands, no shell, and a hard spend cap. Arms: `loom`,
`langgraph`, `adk`, `langgraph_informed` (the same task with the practices spelled out).

## What has been run

One trial per arm with gpt-4o-mini as the coding agent (14 turns each, $0.033 in all). All four sessions scored zero:

- Loom: 14 `weave check` runs, all failing with accurate parse errors the agent did not fix.
- LangGraph (both arms): guessed imports that do not exist and never got past line 1.
- ADK: wrote a file with no working `build()`.

The model is too weak to write code that loads in any arm, so this run cannot compare frameworks, and no claim is
made from it. Raw session records are in `results/`. A stronger model is needed before more trials are worth the cost.

## Run it

```
PROBE_PYTHON=venv/bin/python OPENAI_API_KEY=... python3 run_experiment.py --trials 1
python3 summarize.py
```

`--max-tokens` and `--max-dollars` set the cap (defaults 4M tokens, $2). The scorers are `score_python.py` and
`loom/Score.java`. Known confounds for any later run: the model knows LangGraph better than Loom or ADK 2.x, the Loom
arm is given the model-oriented reference, and small trial counts give counts, not statistics.
