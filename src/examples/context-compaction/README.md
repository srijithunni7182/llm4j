# Context compaction: an agent that would outgrow its window

An agent calls a search tool 25 times. Each result adds about 340 tokens to the prompt, so by the end it would be sending about 8,500 tokens to a model
whose window (here, deliberately small) is 4,000. With compaction it never goes above about 3,200.

No model and no key: the model is a script, and the "summariser" is a stand-in for the cheaper model you would use.

```bash
./run.sh              # builds the jar the first time (offline is fine once the dependencies are in your Maven cache)
BUILD=1 ./run.sh      # rebuild first
```

You will see one line per model call with how full the context is (`REPORTED` is the provider's own count plus an estimate of what was added since,
`ESTIMATED` is a guess from the text), and a line for every compaction: which strategy ran, the size before and after, and what a summary cost. The
chain is the one recommended in the guide: shorten big tool results first (free), then have a model write notes on the oldest steps, then drop the
oldest if that is still not enough.

Change the numbers in `ContextCompactionDemo.java` (`WINDOW`, `STEPS`, the strategies) and run it again; the guide is
[Context and Compaction](../../ai-agent4j/wiki/Context-and-Compaction.md).
