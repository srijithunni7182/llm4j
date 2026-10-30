# Loom starter set

Fifteen small, complete workflows, each one a step up from the one before. Every one **loads** (`weave check`), has **tests that need no key and cost nothing** (`weave test`), and carries a picture of itself drawn by `weave graph`. Copy a folder, change the prompts, and you have a project.

New to Loom? Read the [tutorial](../../../docs/tutorial/README.md) alongside these: each starter links to the chapter that explains it.

## How to use one

```bash
cp -r 03-review-loop my-project && cd my-project
cp .env.example .env            # put your keys in .env (it is git-ignored; never put keys in a script)
weave check main.loom           # does it load? what is missing?
weave test  main.loom           # scripted models: no key, no network, no cost
weave run   main.loom --input product="a steel water bottle" --max-tokens 50000 --max-calls 20
```

The models are named `gemini-2.5-flash` in all of them. Change the `model:` lines to whichever provider you use (`claude-haiku-5-5`, `openai/gpt-…`, `ollama/llama3`, and others: see [Models and Providers](../ai-agent4j-loom/LOOM_GUIDE.md#models-and-providers)).

## The set

| # | Starter | What it does | Concepts |
|---|---|---|---|
| 01 | [hello](01-hello/) | One agent answers one question | agent, workflow, `delegate`, `return` |
| 02 | [chain](02-chain/) | Summarize, critique, rewrite | passing results along, `budget` |
| 03 | [review-loop](03-review-loop/) | Write, review, rewrite until approved | `loop until ... max`, `on_exhausted`, `expecting` |
| 04 | [router](04-router/) | A classifier picks the specialist | `output_schema`, `alt`, routing in the script |
| 05 | [parallel-research](05-parallel-research/) | Two researchers at once; one may fail | `parallel`, `retry`, `timeout`, `on_failure` |
| 06 | [plan-and-fan-out](06-plan-and-fan-out/) | A plan becomes parallel work | `parallel for each`, runtime routing |
| 07 | [approval](07-approval/) | A person approves before anything is sent | `human_prompt`, `approve:`, `webhook` tool, PII `guard` |
| 08 | [extract-and-validate](08-extract-and-validate/) | Free text to checked fields | `output_schema`, `halt`, `return { }` |
| 09 | [handbook-qa](09-handbook-qa/) | Answers from your own documents | `knowledge` (checked, not run: needs an embedding key) |
| 10 | [memory-assistant](10-memory-assistant/) | Remembers each person | `memory`, `persona`, `session` |
| 11 | [daily-digest](11-daily-digest/) | Runs itself on weekday mornings | `schedule`, `cron`, `weave daemon` |
| 12 | [wait-for-a-person](12-wait-for-a-person/) | Waits days for an answer | durable runs, `sleep`, `rate_limits` |
| 13 | [rewind-and-retry](13-rewind-and-retry/) | Goes back to a checkpoint with feedback | `checkpoint`, `rewind` |
| 14 | [supervised-agent](14-supervised-agent/) | Rules that watch an agent | `supervise` |
| 15 | [earned-autonomy](15-earned-autonomy/) | An agent earns the right to act alone | `decision`, `trust`, `decide` |

## What keeps these honest

`StartersTest` (in the Loom module) runs on every build. For each starter it checks that:

- `main.loom` passes `weave check`, and every test in `tests/` passes with at least 80% of the steps reached (the one exception is 09, whose documents are indexed with an embedding model that a hermetic test cannot script);
- the script shown in the README is the file itself, and the picture is what `weave graph` draws now (`scripts/make-starter-diagrams.sh` redraws them);
- `.env.example` names the keys and holds none;
- this table lists every folder.

So a starter cannot quietly stop working when the language moves.
