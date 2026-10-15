# Inspect: the tools view and the rest of what a run tells you

A real Loom run with three tools (one always fails, one always works, one is flaky), a small context window and compaction, mapped by loom-inspect to
OpenTelemetry-shaped records and fed to the same state a Fabric server keeps. No model, no key, no server.

```bash
./run.sh              # builds the jars the first time
BUILD=1 ./run.sh      # rebuild first
```

You will see, for one run, the **tools table** (health: `healthy`, `degraded`, `failing` or `unused`, with the reason; success rate; failures by category; which agent
uses the tool), the **models** (calls, tokens, retries, latency), each agent's **context** (use, window, compactions), the **incidents**, and then the **roll-up across
runs** that a server computes for a tools-centric view: failing tools first.

The guide is [Observing a workflow](../../../docs/OBSERVABILITY.md); the design is in [`docs/specs/04-inspect-and-incidents`](../../../docs/specs/04-inspect-and-incidents/DESIGN.md).
