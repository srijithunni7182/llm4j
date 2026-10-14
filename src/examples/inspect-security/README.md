# Inspect: security signals

A real Loom run through loom-inspect, with no model, no key and no server. A support agent reads a customer's page (which carries a planted instruction), looks the customer up and sends mail: all three legs of the "lethal trifecta". A second agent is meant to read the web and nothing else, and is refused access to the customer database.

```bash
./run.sh              # builds the jars the first time
BUILD=1 ./run.sh      # rebuild first
```

It prints the same run three ways:

1. **As it is.** The trifecta is *exercised* and high; the planted page is an injection finding, high because the next call was one the agent had not made before; the refused access is a warning; the unknown tool is information.
2. **With the trifecta accepted** in [`support.inspect`](support.inspect), with a written reason. The trifecta becomes information and still shows the reason; the injection finding stays high, because an accepted trifecta is only safe while nothing untrusted steers it.
3. **With a clean page.** No injection finding; the trifecta is still there.

Then the roll-up a Fabric server would show across the three runs.

The files: [`support.loom`](support.loom) (the script), [`support.loot`](support.loot) (what the tools reach: the audit and the trace both read it) and [`support.inspect`](support.inspect) (the rules).

Read [Security signals](../../../wiki/Observability-Security-Signals.md) first; the design is in [`docs/specs/04-inspect-and-incidents`](../../../docs/specs/04-inspect-and-incidents/DESIGN.md).
