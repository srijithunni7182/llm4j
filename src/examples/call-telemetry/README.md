# Call telemetry: what every call did

An agent calls three tools (one returns its error as plain text, one fails with an explicit category, one works) and a model reached through a routing
client whose first provider is down. No model and no key: the model is a script.

```bash
./run.sh              # builds the jar the first time (offline is fine once the dependencies are in your Maven cache)
BUILD=1 ./run.sh      # rebuild first
```

You will see:

1. **One line per call, as it happens**, from a listener: for a model call, how long it took, the tokens, how many attempts it needed and which providers
   failed over; for a tool call, what became of it and, when it failed, the category (`PERMISSION_DENIED`, ...) and whether the tool returned the error as text.
2. **The statistics of the run**, read from `AgentResult.getStats()`: per tool the success rate, the latency percentiles and the failures by category; per model
   the calls, tokens, retries and fail-overs.

The guide is [Telemetry and Statistics](../../ai-agent4j/wiki/Telemetry-and-Statistics.md).
