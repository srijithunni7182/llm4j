# fabric-sim

Try out Fabric (and the console) with **no model, no network and no API key**. A simulated studio workflow runs on the real Loom executor and the real loom-inspect add-on; only the models and tools are scripted. A run takes about four minutes and shows, on a schedule you can read ahead of time:

| Time (speed 1) | What the console shows |
|---|---|
| 0:00 | Showrunner writes a brief; then `broadcast` to Researcher, TrendScout and Competitor in parallel; Competitor's fetches both fail (a failing tool) |
| ~0:30 | a `sleep` (the run shows *sleeping*, with its wake time) |
| ~0:40–2:30 | a loop that never converges: Scriptwriter invents figures (a *high* "suspect output" incident), Editor, FactChecker and SEO run in parallel and repeat the same Wiki call (*looping*), Lead gives the same answer and `review.fix` never changes (*no progress*) |
| ~3:00 | thumbnails and captions for three shots in parallel; the first audit fails and the run **rewinds** once |
| ~4:00 | the Publisher saves the report; the run finishes |

## Run it

From the repository root, once (builds the jars):

```bash
mvn -q -DskipTests -pl src/loom/ai-agent4j-loom,src/loom/loom-inspect,src/loom/loom-fabric -am package
```

Then:

```bash
cd src/examples/fabric-sim
./run.sh                           # a Fabric server on :9090 and three simulated processes, until Ctrl-C
PROCESSES=5 SPEED=0.3 ./run.sh     # five processes, runs about three times faster
BUILD=1 ./run.sh                   # build first
```

Open `http://127.0.0.1:9090` and sign in with the printed password (`simulate-admin-1` unless you set `ADMIN_PASSWORD`). You get the Processes page and one tab per process and workflow. `run.sh` makes a new data directory each time (`./fabric-sim-data`) and starts the simulators a couple of seconds apart so they are at different steps.

## Things to try

- **Controls**: Pause a run (it holds before its next step; Resume carries on), Suspend, Stop. Each needs the confirm dialog; the audit log is `fabric-sim-data/audit.log`. The request is queued for that process and collected on its long-poll, so it takes effect at the next step, not instantly. A suspended simulated run is not resumed (the simulator keeps no journal on disk); the next run starts after the gap.
- **A process dies**: `kill` a simulator. Its row goes offline in about 15 seconds; its runs stay on the page.
- **The server dies**: stop the Fabric Java process and start it again with `java -jar loom-fabric-*-all.jar serve --data fabric-sim-data --port 9090`. The processes' outboxes (`fabric-sim-data/outbox`) fill while it is away and drain when it returns: the page shows *catching up*, then no gaps.
- **A key is revoked**: `java -jar loom-fabric-*-all.jar clients revoke sim-2 --data fabric-sim-data`. The server notices within a second (no restart) and refuses sim-2 from then on; the simulated workflow carries on regardless.
- **Detail levels**: `DETAIL=metadata ./run.sh` keeps all text out of what is sent; the incidents still appear because they use hashes and counts.

## One simulator by hand

```bash
SIM_FABRIC_KEY=<the key from 'fabric clients add sim-1'> \
java -cp "$WEAVE_JAR:loom-inspect.jar:loom-inspect-core.jar" io.github.llm4j.loom.inspect.sim.Simulate \
     --fabric http://127.0.0.1:9090 --name sim-1 --runs 0 --speed 0.5

# no server: just the page, served by this process
java -cp "..." io.github.llm4j.loom.inspect.sim.Simulate --embedded 8787 --admin-password-env MY_PASSWORD
```

`--help` lists every option. `--embedded` with `--admin-password-env` turns the page's pause, suspend and stop controls on for the simulated run, the same as for a real app.

## Cost

The page shows the cost the Loom runtime reports; loom-inspect and Fabric have no prices of their own. The simulated models are priced from `sim.prices` (packaged in the jar; `--prices FILE` replaces it), in the same `model = input / output` per million tokens format a real workflow uses. For a real workflow put `flow.prices` or `prices.properties` beside the script (or pass `--prices`, or set `LOOM_PRICES`).

Try the server-side price table: `NO_PRICES=1 ./run.sh` starts the simulated processes without a price file, so they report tokens and no cost. Open **Configuration** on the Fabric page, paste prices for the `sim-*` models (copy them from `sim.prices`), and **Save prices**: the next steps are costed by the server.

The same page edits the inspect rules: try `Editor { same_action: 2 times }` and watch the *looping* incident come sooner.

## What it is for, and not

It exercises transport, the server, the page and the controls with realistic traffic; it says nothing about real agents. The workflow is `sim.loom` inside the `loom-inspect` jar (with `sim.inspect`, its thresholds). Its agents are deliberately badly behaved fixtures; do not copy them. The 30-minute soak in `../inspect-soak` is the same idea with a real model.
