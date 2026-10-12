# inspect-soak

A Loom workflow of about 30 minutes, built to be watched with loom-inspect. It is the acceptance test for
the observability add-on in [`docs/loom-inspect/DESIGN.md`](../../../docs/loom-inspect/DESIGN.md): every
state the console is meant to show appears on a schedule you can read ahead of time.

The agents in `soak.loom` are **deliberately badly behaved**: a reviewer that never converges, a writer that
invents statistics, a tool pointed at pages that do not exist. They are fixtures. Do not copy them.

## What it does

A short-video studio: a brief, parallel research, a review loop that does not converge, thumbnails and
captions with a rewind, then a save. Between the model steps the workflow `sleep`s, so the run
lasts as long as a real long-running one, and each sleep is a real pause of the durable run. The timeline, and what each moment should look like in the
console, is in [`CHECKLIST.md`](./CHECKLIST.md).

| Phase | What happens | Why it is there |
|---|---|---|
| A | `broadcast` to Researcher, TrendScout and Competitor; Competitor's fetches fail; `sleep 90s` | parallel fan-out, tools in flight, a failing tool, a sleep |
| B | `loop … max 5`: Scriptwriter, then Editor, FactChecker and SEO in `parallel`, then Lead, who is never satisfied | a loop that stops converging, a repeated tool call, invented figures |
| C | a `checkpoint`, a Planner, `parallel for each` over thumbnails and captions, an Auditor, one `rewind` | a rewind, a budget-window pause, `for each` routing |
| D | the Publisher saves `report.md`; with `ASK_HUMAN=yes` each write waits for you | an approval wait |

## Run it

You need a JDK, the weave jar (build it from the repository root with
`mvn -DskipTests -pl src/loom/ai-agent4j-loom -am package`) and a Gemini key. The soak uses Loom's `sleep`, so build the jar from this branch or later.

```bash
cd src/examples/inspect-soak
export GEMINI_API_KEY=...
./run-soak.sh                 # about 30 minutes
# for a shorter run, lower the `sleep` durations in soak.loom
```

`run-soak.sh` runs the workflow with a durable journal (`runs/soak`) and `--wait`. Every `sleep` longer than
30 seconds pauses the run and holds no thread, and `--wait` wakes it at the recorded time; the budget-window
pause in phase C resumes the same way. Use `weave check soak.loom` and `weave audit soak.loom` any time; both pass today.

**Cost.** Every agent uses `gemini-2.5-flash`. The run budget is 900,000 tokens and 500 calls, with a warning
at 80%. Expect something in the low hundreds of thousands of tokens, a few tens of US cents at flash prices.
Check the first run's `spend` report and adjust.

## Watching it

`soak.inspect` beside `soak.loom` says how loom-inspect watches this workflow (when a looping or ungrounded agent counts as trouble); the script itself says nothing about watching. Check it with `weave observe rules soak.loom`.


- **Today:** `--trace` prints every event on stderr, which is also the raw material loom-inspect will read.
- **With loom-inspect, embedded** (works today): build the add-on (`mvn -DskipTests -pl src/loom/loom-inspect -am package`), put
  `loom-inspect-*.jar` and `loom-inspect-core-*.jar` on the class path beside the weave jar, and add `--observe`
  (page at `http://127.0.0.1:8787`) and, to keep the page up after the run, `--observe-linger 5m`:
  `java -cp "$WEAVE_JAR:loom-inspect.jar:loom-inspect-core.jar" io.github.llm4j.loom.cli.WeaveCLI run soak.loom … --observe`.
  Today it shows totals, agents with the tools they are using, tool counts, latest events and the sleeping state.
- **With Fabric** (works today): create a server (`java -jar loom-fabric-*-all.jar init --data fdata`, `clients add soak-1`, `serve`),
  store the printed key (`weave secrets set LOOM_FABRIC_CLIENT_KEY --secrets keys.store`) and pass
  `--observe-fabric http://127.0.0.1:9090 --observe-name soak-1` (see `src/loom/loom-fabric/README.md` for TLS). Run two copies with
  different names to test it with more than one process. The outage, restart and kill steps in `CHECKLIST.md` are the acceptance test.

## What is not verified yet

The script parses and passes `weave check` and `weave audit`. It has **not been run against a live model**:
the timings in the checklist are estimates from the number of steps and the pauses, and the first real run
will correct them. The per-minute budget on `Captioner` (the pause) is the least certain part: tune
`tokens: 2500 per minute` if the run never pauses, or pauses too often.
