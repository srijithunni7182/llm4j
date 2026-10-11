# inspect-soak

A Loom workflow of about 30 minutes, built to be watched with loom-inspect. It is the acceptance test for
the observability add-on in [`docs/loom-inspect/DESIGN.md`](../../../docs/loom-inspect/DESIGN.md): every
state the console is meant to show appears on a schedule you can read ahead of time.

The agents in `soak.loom` are **deliberately badly behaved**: a reviewer that never converges, a writer that
invents statistics, a tool pointed at pages that do not exist. They are fixtures. Do not copy them.

## What it does

A short-video studio: a brief, parallel research, a review loop that does not converge, thumbnails and
captions with a rewind, then a save. Between the model steps a deterministic `Pace` task waits, so the run
lasts as long as a real long-running one. The timeline, and what each moment should look like in the
console, is in [`CHECKLIST.md`](./CHECKLIST.md).

| Phase | What happens | Why it is there |
|---|---|---|
| A | `broadcast` to Researcher, TrendScout and Competitor; Competitor's fetches fail | parallel fan-out, tools in flight, a failing tool |
| B | `loop … max 5`: Scriptwriter, then Editor, FactChecker and SEO in `parallel`, then Lead, who is never satisfied | a loop that stops converging, a repeated tool call, invented figures |
| C | a `checkpoint`, a Planner, `parallel for each` over thumbnails and captions, an Auditor, one `rewind` | a rewind, a budget-window pause, `for each` routing |
| D | the Publisher saves `report.md`; with `ASK_HUMAN=yes` each write waits for you | an approval wait |

## Run it

You need a JDK, the weave jar (build it from the repository root with
`mvn -DskipTests -pl src/loom/ai-agent4j-loom -am package`) and a Gemini key.

```bash
cd src/examples/inspect-soak
export GEMINI_API_KEY=...
./run-soak.sh                 # about 30 minutes
PACE=30 ./run-soak.sh         # about 12 minutes, for a quick look
```

`run-soak.sh` builds `soak-tasks.jar` (the `Pace` task, found through `META-INF/services`) on first use and
runs the workflow with a durable journal (`runs/soak`) and `--wait`, so the budget-window pause in phase C
resumes by itself. Use `weave check soak.loom` and `weave audit soak.loom` any time; both pass today.

**Cost.** Every agent uses `gemini-2.5-flash`. The run budget is 900,000 tokens and 500 calls, with a warning
at 80%. Expect something in the low hundreds of thousands of tokens, a few tens of US cents at flash prices.
Check the first run's `spend` report and adjust.

## Watching it

- **Today:** `--trace` prints every event on stderr, which is also the raw material loom-inspect will read.
- **With loom-inspect** (when it exists): add `--observe` for the embedded page, or
  `--observe-server <url> --observe-name soak-1` to report to an inspect server. Run two copies with
  different names to test the server with more than one process.

## What is not verified yet

The script parses and passes `weave check` and `weave audit`. It has **not been run against a live model**:
the timings in the checklist are estimates from the number of steps and the pauses, and the first real run
will correct them. The per-minute budget on `Captioner` (the pause) is the least certain part: tune
`tokens: 2500 per minute` if the run never pauses, or pauses too often.
