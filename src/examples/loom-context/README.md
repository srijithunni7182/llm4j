# Loom context: a script that keeps its agent inside the model's window

`research.loom` has an agent that calls a lookup tool 30 times. Each result adds about 200 tokens, so by the end it would send about 7,000 tokens to a model whose window
(in `research.windows`, **not** in the script) is 6,000, 5,000 of them usable for the prompt. The agent's `context` block says how to keep it inside:

```loom
context { compact: chain(trim_tool_results, keep_newest, drop_oldest)  trigger: 80%  target: 50%  keep_recent: 4 }
```

`keep_newest` is a strategy of our own. In a project it is a class mapped to that name in a `.loct` file (`research.loct` shows the line); the demo registers it in code so
it runs from a single file.

No model and no key: the model is a script.

```bash
./run.sh              # builds the jar the first time
BUILD=1 ./run.sh      # rebuild first
```

You will see each compaction (which strategy, how many tokens before and after), how the run ended, the statistics of the run, and the part of `weave audit` that
lists every agent's strategy and where its window comes from. Change `research.windows` and run again: no script changes.

The guide is [Context and Compaction](../../loom/ai-agent4j-loom/LOOM_GUIDE.md#context-and-compaction) in the Loom guide.
