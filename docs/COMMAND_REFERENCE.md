# weave command reference

Written by `weave man --all --format markdown` from the commands themselves; do not edit it by hand. In a terminal, `weave man` lists the commands and `weave man <command>` prints one page.

## Which command when

### Start here

- [`weave try`](#weave-try) — Runs a bundled workflow on demo models (pre-written replies; no key, no network, nothing written) and explains each step.
- [`weave doctor`](#weave-doctor) — Checks the setup (Java, the script, the keys it needs by name, models, the run store, prices, Fabric) and says the one thing to do for each problem.
- [`weave init`](#weave-init) — Creates a starter project: a Maven project with the script and its prompts in src/main/resources, the golden dataset and eval4j JUnit tests under src/test, and a README.
- [`weave next`](#weave-next) — Looks at this project and says what to do next, in order, with the command for each step.
- [`weave guide`](#weave-guide) — Prints the guide that ships inside this jar: weave guide (list), weave guide 6, weave guide loom.
- [`weave man`](#weave-man) — Prints the reference for a command, like a man page: what it does, every option with its default, examples, exit codes and what to read next.

### Describe it and build it

- [`weave generate`](#weave-generate) — Turns a description into a plan (PLAN.md and plan.json) and a plan into a project that passes check, review and its own tests.

### Understand and check a script

- [`weave check`](#weave-check) — Checks a script — tools, knowledge, secrets, names — without running it.
- [`weave explain`](#weave-explain) — Describes a script in plain English (which agent is asked what, where a person is asked, what can spend money) without running anything or calling a model.
- [`weave graph`](#weave-graph) — Draws the workflows of a script and its imports as a graph, as JSON (default) or Mermaid, without running anything.
- [`weave symbols`](#weave-symbols) — Lists the definitions, uses and variable types of a script and its imports as JSON, without running anything.
- [`weave review`](#weave-review) — Reviews a script without running it: security, validity and the other lenses in one ranked list, each finding with why it matters and what to do.
- [`weave audit`](#weave-audit) — Reviews a script's security without running it: what each agent can reach, where untrusted content, private data and a way out meet in one agent, unapproved effects, budgets and outside dependencies.
- [`weave rules`](#weave-rules) — Works with review rules and rule packs, offline: list, facts, check, test, new, add, diff, docs, explain.

### Test and evaluate

- [`weave test`](#weave-test) — Runs test cases against a workflow with scripted models and no keys: routes, calls and results are checked, nothing is spent.
- [`weave eval`](#weave-eval) — Runs a script's golden dataset (eval/golden/*.yaml) against it and reports passed, failed and unjudged.
- [`weave gate`](#weave-gate) — Runs the checks your team chose (check, review, test, regress, eval) and prints one verdict, for CI.
- [`weave preflight`](#weave-preflight) — Sends one small probe per agent through its real prompts and checks: the key, the model, one whole answer, that the shared prompt reaches the model, sources for grounded agents, price and window.

### Run it

- [`weave run`](#weave-run) — Executes a .loom script immediately.
- [`weave step`](#weave-step) — Runs a workflow one statement at a time, with breakpoints, the variables and the way back.
- [`weave package`](#weave-package) — Packages a .loom workflow into a runnable JAR.
- [`weave resume`](#weave-resume) — Resumes a paused run now, from its run directory.

### Watch a run and ask why

- [`weave observe`](#weave-observe) — Looks after what observing leaves on disk: observe flush delivers an outbox, observe purge removes one.
- [`weave why`](#weave-why) — Explains a run: each choice it made, over which values, where they came from, and the way it did not go.
- [`weave whatif`](#weave-whatif) — Changes one thing in a past run and shows how it would have gone: the trajectory, the result and the effects that differ.
- [`weave timeline`](#weave-timeline) — Shows a run's history: each step with its attempt, state and cost, the checkpoints reached and every rewind.

### Answer people and fix a run

- [`weave questions`](#weave-questions) — Lists the questions waiting for a person (--all: answered and expired ones too).
- [`weave answer`](#weave-answer) — Answers a question that is waiting for a person, and leaves a trigger so the run carries on at the next tick.
- [`weave rewind`](#weave-rewind) — Sends a run back to a checkpoint (or to a step, which then runs again).
- [`weave reset`](#weave-reset) — Starts a run again from the top as a new attempt (spend history kept), or with --failed tries only the failed steps again.
- [`weave fork`](#weave-fork) — Copies a run into a new run directory (the original is only read), optionally going back to a point, with other values or another script.
- [`weave replay`](#weave-replay) — Replays a decision's past cases under a candidate script, with nothing sent and nothing changed, and reports how it would have done.

### From production back to tests

- [`weave capture`](#weave-capture) — Turns a real run into a test case with the recorded answers scripted in, so it reproduces what production did with no model.
- [`weave regress`](#weave-regress) — Tries a candidate script, prompt or model on a sample of real past runs and reports what changes, unsafe changes first.

### Triggers and schedules

- [`weave tick`](#weave-tick) — Fires every trigger that is due in a store, then exits (for cron, systemd, launchd).
- [`weave daemon`](#weave-daemon) — Keeps running and fires triggers from a store as they come due.
- [`weave triggers`](#weave-triggers) — Lists and manages stored triggers, and installs system triggers.%n  list | pause <id> | enable <id> | cancel <id> | fire <id> | install | uninstall
- [`weave schedule`](#weave-schedule) — schedule sync <script> --store <dir>: writes a script's schedule blocks to a trigger store.

### Decisions that earn autonomy

- [`weave autonomy`](#weave-autonomy) — Where each decision's ladder stands, and the ways a person moves it.%n  status | history | promote | demote | approve | reject | freeze | unfreeze | outcome | verify
- [`weave ladder`](#weave-ladder) — Describes a ladder in plain sentences, or tries it on a recorded history.%n  describe | test

### Ship it

- [`weave bindgen`](#weave-bindgen) — Writes typed Java bindings for a script: one method per workflow, a record per result, and an Outcome that is Done, Waiting or Stopped.
- [`weave deploy`](#weave-deploy) — Writes a container recipe, a compose file or Kubernetes manifests for a workflow, from the script: env names, hosts, volumes and schedules are read from it.
- [`weave secrets`](#weave-secrets) — Manages an encrypted secret store: create, set, list, remove, import-env, rekey.
- [`weave tools`](#weave-tools) — Gets the extra jars a deployment needs (the Fabric server, the inspect add-on) from Maven Central, after asking and checking their checksums.

### Editors and debuggers

- [`weave serve`](#weave-serve) — Serves check, graph and review to an editor over standard input and output (JSON-RPC with Content-Length framing).
- [`weave dap`](#weave-dap) — Speaks the Debug Adapter Protocol on standard input and output, so an editor can debug a workflow.

## Pages

### `weave run`

Executes a .loom script immediately.

```
weave run [OPTIONS] <scriptFile>
```

**Arguments**

- `<scriptFile>` — The .loom script file to execute.

**Options**

- `--ask-via=<telegram|command|console>` — Where to ask a person (default: the store's channel.json, else the console).
- `--classes=<dir|jar>` — Folder or jar with the classes of your own tasks and tools, repeatable (weave only sees what is on its class path). Default: target/classes of the project, when it has been built (mvn compile).
- `--context-windows=<file>` — A windows file: 'model = window / reserve' per line (the size of each model's context window). Repeatable; a later file wins. Layered over <script>.windows, ~/.llm4j/context-windows and the built-in catalogue. (repeatable)
- `--env-file=<file>` — Read keys from this file (NAME=value lines) instead of the .env beside the script (or at the project root). For development: keep it out of git.
- `--exit-degraded` — Exit with code 7 when the run finished but some step used its on_failure fallback (default: 0).
- `-i, --input <String=String>` — Initial context variables in key=value format. (repeatable)
- `--invoker-env=<NAME=VARIABLE>` — Supply invoker.NAME from an environment variable, e.g. --invoker-env token=MY_API_TOKEN (repeatable).
- `--journal=<journal>` — Run directory: makes the run durable, so it can pause on limits and resume later.
- `--lenient` — Treat features that aren't supported yet as warnings, not errors.
- `-l, --loot <lootFile>` — The .loot tool mapping file.
- `--loct=<file>` — A .loct file mapping a name to a class of your own compaction strategy ('keep_decisions = com.acme.KeepDecisions'), for use as 'compact: keep_decisions' in a script. Repeatable. (repeatable)
- `--max-calls=<maxCalls>` — Cap the run's LLM calls (replaces the script's run budget).
- `--max-cost=<maxCost>` — Cap the run's cost, e.g. 0.50 (needs --prices).
- `--max-rewinds=<maxRewinds>` — The most times the run may go back in all (default 20).
- `--max-tokens=<maxTokens>` — Cap the run's tokens (replaces the script's run budget).
- `--no-env-file` — Do not read a .env file, even if one is beside the script.
- `--observe-bind=<address>` — The address the page listens on (default 127.0.0.1).
- `--observe-ca-only` — Trust only the CA given with --observe-ca, not the default roots.
- `--observe-ca=<file>` — A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.
- `--observe-detail=<full|truncated|metadata>` — How much of what agents say and tools return may leave the process (default truncated: 500 characters).
- `--observe-fabric=<url>` — Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.
- `--observe-flush-timeout=<time>` — At exit, try this long to deliver what is left (default 5s).
- `--observe-key-secret=<name>` — The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).
- `--observe-linger=<time>` — Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.
- `--observe-name=<name>` — This process's name on the page or the server (required with --observe-fabric or --observe-otlp).
- `--observe-otlp-header=<Name=value>` — A header for the OTLP endpoint; a value may be null, read from the secret store. Repeatable. (repeatable)
- `--observe-otlp=<url>` — Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.
- `--observe-outbox=<dir>` — Where the local outbox is kept (default ./loom-inspect-outbox).
- `--observe-pin=<sha256:hex>` — Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.
- `--observe-rules=<file>` — What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).
- `--observe=<port>` — Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.
- `--prices=<prices>` — Price table: lines of 'model = input / output' per million tokens.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).
- `-q, --quiet` — Do not print the workflow's returned value after the run.
- `--review-notice=<high|medium|low|off>` — The lowest severity of open review findings mentioned in one line on standard error before the run (default: review.properties, else low). Never blocks the run.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.
- `--steer-via=<console>` — Where a person may steer this run's agents while they work (console: type commands here). Agents must be declared steerable.
- `--stop-at=<stopAt>` — Stop cleanly once this step (or checkpoint) has completed; resume the run to carry on.
- `--store=<store>` — Trigger store for resumes (default: <journal>/../.loom-triggers).
- `--trace=<text|json>` — Show what agents think and do, live, on stderr (--trace=json for JSON lines).
- `--wait` — When the run pauses, stay alive and resume it when its limit resets.
- `-w, --workflow <workflowName>` — The name of the workflow to run. (default: Main)

**Examples**

```
weave run main.loom
weave run main.loom --observe                   a live page of the run on localhost
weave run main.loom --max-cost 1.00 --prices prices.txt
weave run main.loom --journal runs/             make the run durable: it can pause, resume, be explained or captured
weave run main.loom --exit-degraded             exit 7 when the run finished but a step used its on_failure fallback
weave run main.loom --quiet                     do not print what the workflow returned
```

**Exit status**

0 done. 1 failed. 2 bad options. 3 stopped by a budget. 4 paused (waiting for a person or a time). 5 stopped where it was told to. 6 halted by the workflow. 7 finished, but a step used its fallback (only with --exit-degraded; without it a degraded run exits 0 and says which steps fell back).
A run in which every model step fell back is a failure (1). After the run, weave run prints what the workflow returned under "Result:".

**See also**

weave man why, weave man resume, weave man capture

### `weave check`

Checks a script — tools, knowledge, secrets, names — without running it.

```
weave check [OPTIONS] <scriptFile>
```

**Arguments**

- `<scriptFile>` — The .loom script to check.

**Options**

- `--classes=<dir|jar>` — Folder or jar with the classes of your own tasks and tools, repeatable (weave only sees what is on its class path). Default: target/classes of the project, when it has been built (mvn compile).
- `--context-windows=<file>` — A windows file: 'model = window / reserve' per line (the size of each model's context window). Repeatable; a later file wins. Layered over <script>.windows, ~/.llm4j/context-windows and the built-in catalogue. (repeatable)
- `--env-file=<file>` — Read keys from this file (NAME=value lines) instead of the .env beside the script (or at the project root). For development: keep it out of git.
- `--format=<format>` — text (default) or json: one JSON object with the problems in it, for tools (the editor reads this).
- `--lenient` — Treat features that aren't supported yet as warnings.
- `-l, --loot <lootFile>` — The .loot tool mapping file.
- `--loct=<file>` — A .loct file mapping a name to a class of your own compaction strategy ('keep_decisions = com.acme.KeepDecisions'), for use as 'compact: keep_decisions' in a script. Repeatable. (repeatable)
- `--no-env` — Check the script without keys: names that are not set in the environment are listed as 'not set yet' instead of being problems.
- `--no-env-file` — Do not read a .env file, even if one is beside the script.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.
- `--strict` — Treat warnings as errors (exit 2), so a build can require a clean script.

**Examples**

```
weave check main.loom                 tools, knowledge, secrets and names
weave check main.loom --no-env        without the keys (a stand-in is used for env.NAME)
weave check main.loom --format json   every problem with its line, for an editor
weave check main.loom --strict        warnings count as errors
```

**Exit status**

0 no errors. 2 errors (or warnings with --strict).

**See also**

weave man review, weave man test

### `weave resume`

Resumes a paused run now, from its run directory.

```
weave resume [OPTIONS] <runDir>
```

**Arguments**

- `<runDir>` — The run directory (as given to run --journal).

**Options**

- `--ask-via=<telegram|command|console>` — Where to ask a person (default: the store's channel.json, else the console).
- `--invoker-env=<NAME=VARIABLE>` — Supply invoker.NAME from an environment variable, e.g. --invoker-env token=MY_API_TOKEN (repeatable).
- `--observe-bind=<address>` — The address the page listens on (default 127.0.0.1).
- `--observe-ca-only` — Trust only the CA given with --observe-ca, not the default roots.
- `--observe-ca=<file>` — A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.
- `--observe-detail=<full|truncated|metadata>` — How much of what agents say and tools return may leave the process (default truncated: 500 characters).
- `--observe-fabric=<url>` — Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.
- `--observe-flush-timeout=<time>` — At exit, try this long to deliver what is left (default 5s).
- `--observe-key-secret=<name>` — The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).
- `--observe-linger=<time>` — Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.
- `--observe-name=<name>` — This process's name on the page or the server (required with --observe-fabric or --observe-otlp).
- `--observe-otlp-header=<Name=value>` — A header for the OTLP endpoint; a value may be null, read from the secret store. Repeatable. (repeatable)
- `--observe-otlp=<url>` — Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.
- `--observe-outbox=<dir>` — Where the local outbox is kept (default ./loom-inspect-outbox).
- `--observe-pin=<sha256:hex>` — Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.
- `--observe-rules=<file>` — What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).
- `--observe=<port>` — Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.
- `--stop-at=<stopAt>` — Stop cleanly once this step (or checkpoint) has completed.

### `weave tick`

Fires every trigger that is due in a store, then exits (for cron, systemd, launchd).

```
weave tick [OPTIONS] <store>
```

**Arguments**

- `<store>` — The trigger store directory.

**Options**

- `--ask-via=<telegram|command|console>` — Where to ask a person (default: the store's channel.json, else the console).
- `--observe-bind=<address>` — The address the page listens on (default 127.0.0.1).
- `--observe-ca-only` — Trust only the CA given with --observe-ca, not the default roots.
- `--observe-ca=<file>` — A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.
- `--observe-detail=<full|truncated|metadata>` — How much of what agents say and tools return may leave the process (default truncated: 500 characters).
- `--observe-fabric=<url>` — Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.
- `--observe-flush-timeout=<time>` — At exit, try this long to deliver what is left (default 5s).
- `--observe-key-secret=<name>` — The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).
- `--observe-linger=<time>` — Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.
- `--observe-name=<name>` — This process's name on the page or the server (required with --observe-fabric or --observe-otlp).
- `--observe-otlp-header=<Name=value>` — A header for the OTLP endpoint; a value may be null, read from the secret store. Repeatable. (repeatable)
- `--observe-otlp=<url>` — Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.
- `--observe-outbox=<dir>` — Where the local outbox is kept (default ./loom-inspect-outbox).
- `--observe-pin=<sha256:hex>` — Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.
- `--observe-rules=<file>` — What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).
- `--observe=<port>` — Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

### `weave daemon`

Keeps running and fires triggers from a store as they come due.

```
weave daemon [OPTIONS] <store>
```

**Arguments**

- `<store>` — The trigger store directory.

**Options**

- `--ask-via=<telegram|command|console>` — Where to ask a person (default: the store's channel.json, else the console).
- `--observe-bind=<address>` — The address the page listens on (default 127.0.0.1).
- `--observe-ca-only` — Trust only the CA given with --observe-ca, not the default roots.
- `--observe-ca=<file>` — A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.
- `--observe-detail=<full|truncated|metadata>` — How much of what agents say and tools return may leave the process (default truncated: 500 characters).
- `--observe-fabric=<url>` — Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.
- `--observe-flush-timeout=<time>` — At exit, try this long to deliver what is left (default 5s).
- `--observe-key-secret=<name>` — The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).
- `--observe-linger=<time>` — Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.
- `--observe-name=<name>` — This process's name on the page or the server (required with --observe-fabric or --observe-otlp).
- `--observe-otlp-header=<Name=value>` — A header for the OTLP endpoint; a value may be null, read from the secret store. Repeatable. (repeatable)
- `--observe-otlp=<url>` — Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.
- `--observe-outbox=<dir>` — Where the local outbox is kept (default ./loom-inspect-outbox).
- `--observe-pin=<sha256:hex>` — Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.
- `--observe-rules=<file>` — What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).
- `--observe=<port>` — Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.
- `--poll=<poll>` — How often to look for due triggers, e.g. 5s. (default: 5s)
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

### `weave observe`

Looks after what observing leaves on disk: observe flush delivers an outbox, observe purge removes one.

```
weave observe <command>
```

**Commands**

- `flush` — Delivers what an earlier process left in its local outbox to the Fabric server (the server must know this name's key).
- `purge` — Deletes a process name's local outbox that no running process is using.
- `rules` — Checks a rules file (what counts as trouble for each agent) against a script, without running anything.
- `hash-password` — Prints a salted hash of the admin password.

#### `weave observe flush`

Delivers what an earlier process left in its local outbox to the Fabric server (the server must know this name's key).

```
weave observe flush [OPTIONS]
```

**Options**

- `--observe-bind=<address>` — The address the page listens on (default 127.0.0.1).
- `--observe-ca-only` — Trust only the CA given with --observe-ca, not the default roots.
- `--observe-ca=<file>` — A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.
- `--observe-detail=<full|truncated|metadata>` — How much of what agents say and tools return may leave the process (default truncated: 500 characters).
- `--observe-fabric=<url>` — Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.
- `--observe-flush-timeout=<time>` — At exit, try this long to deliver what is left (default 5s).
- `--observe-key-secret=<name>` — The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).
- `--observe-linger=<time>` — Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.
- `--observe-name=<name>` — This process's name on the page or the server (required with --observe-fabric or --observe-otlp).
- `--observe-otlp-header=<Name=value>` — A header for the OTLP endpoint; a value may be null, read from the secret store. Repeatable. (repeatable)
- `--observe-otlp=<url>` — Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.
- `--observe-outbox=<dir>` — Where the local outbox is kept (default ./loom-inspect-outbox).
- `--observe-pin=<sha256:hex>` — Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.
- `--observe-rules=<file>` — What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).
- `--observe=<port>` — Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

#### `weave observe purge`

Deletes a process name's local outbox that no running process is using.

Undelivered events in it are lost.

```
weave observe purge [OPTIONS]
```

**Options**

- `--observe-bind=<address>` — The address the page listens on (default 127.0.0.1).
- `--observe-ca-only` — Trust only the CA given with --observe-ca, not the default roots.
- `--observe-ca=<file>` — A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.
- `--observe-detail=<full|truncated|metadata>` — How much of what agents say and tools return may leave the process (default truncated: 500 characters).
- `--observe-fabric=<url>` — Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.
- `--observe-flush-timeout=<time>` — At exit, try this long to deliver what is left (default 5s).
- `--observe-key-secret=<name>` — The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).
- `--observe-linger=<time>` — Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.
- `--observe-name=<name>` — This process's name on the page or the server (required with --observe-fabric or --observe-otlp).
- `--observe-otlp-header=<Name=value>` — A header for the OTLP endpoint; a value may be null, read from the secret store. Repeatable. (repeatable)
- `--observe-otlp=<url>` — Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.
- `--observe-outbox=<dir>` — Where the local outbox is kept (default ./loom-inspect-outbox).
- `--observe-pin=<sha256:hex>` — Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.
- `--observe-rules=<file>` — What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).
- `--observe=<port>` — Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.

#### `weave observe rules`

Checks a rules file (what counts as trouble for each agent) against a script, without running anything.

Default file: <script>.inspect beside the script.

```
weave observe rules [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script the rules are for.

**Options**

- `--observe-rules=<file>` — The rules file (default: <script>.inspect).

#### `weave observe hash-password`

Prints a salted hash of the admin password.

Store that as LOOM_INSPECT_ADMIN_PASSWORD instead of the password (weave secrets set ... then paste it); signing in still takes the password. The password is read from the environment variable named by --password-env (default LOOM_INSPECT_NEW_PASSWORD), or asked for.

```
weave observe hash-password [OPTIONS]
```

**Options**

- `--password-env=<VAR>` — The environment variable holding the password. (default: LOOM_INSPECT_NEW_PASSWORD)

### `weave triggers`

Lists and manages stored triggers, and installs system triggers.%n  list | pause <id> | enable <id> | cancel <id> | fire <id> | install | uninstall

```
weave triggers [OPTIONS] <action> <store> [<id>]
```

**Arguments**

- `<action>` — list, pause, enable, cancel, fire, install or uninstall.
- `<store>` — The trigger store directory.
- `<id>` — The trigger id (pause, enable, cancel, fire).

**Options**

- `--apply` — Make the change (without it, only show what would change).
- `--backend=<backend>` — cron, systemd, launchd, windows or cloud-scheduler (default: detected).
- `--env-file=<envFile>` — A file of KEY=VALUE lines (API keys) loaded before each tick.
- `--every=<every>` — Heartbeat interval, e.g. 5m. (default: 5m)
- `--mode=<mode>` — heartbeat (default) or exact.
- `--region=<region>` — cloud-scheduler: location, e.g. asia-south1.
- `--service-account=<serviceAccount>` — cloud-scheduler: service account for the OIDC token.
- `--url=<url>` — cloud-scheduler: the service URL exposing /loom/tick.
- `--weave=<weave>` — The command that runs weave (default: this Java and classpath).

### `weave schedule`

schedule sync <script> --store <dir>: writes a script's schedule blocks to a trigger store.

```
weave schedule [OPTIONS] <action> <script>
```

**Arguments**

- `<action>` — sync
- `<script>` — The .loom script.

**Options**

- `--store=<store>` — The trigger store directory.

### `weave package`

Packages a .loom workflow into a runnable JAR.

```
weave package [OPTIONS] <scriptFile>
```

**Arguments**

- `<scriptFile>` — The .loom script file.

**Options**

- `--fat` — Create a fat JAR containing all dependencies.
- `-l, --loot <lootFile>` — The .loot tool mapping file.
- `-o, --out <outputName>` — Output JAR filename. (default: loom-app.jar)
- `--review-notice=<high|medium|low|off>` — The lowest severity of open review findings mentioned in one line on standard error (default: review.properties, else low). Never blocks packaging.
- `--thin` — Create a thin JAR (default).

### `weave answer`

Answers a question that is waiting for a person, and leaves a trigger so the run carries on at the next tick.

```
weave answer [OPTIONS] <store> <code> <text>...
```

**Arguments**

- `<store>` — The run store.
- `<code>` — The question's code (weave questions lists them).
- `<text>...` — The answer.

**Options**

- `--by=<by>` — Who is answering (default: the operating system user).

### `weave questions`

Lists the questions waiting for a person (--all: answered and expired ones too).

```
weave questions [OPTIONS] <store>
```

**Arguments**

- `<store>` — The run store.

**Options**

- `--all` — Include answered and expired questions.
- `--json` — Print JSON.

### `weave timeline`

Shows a run's history: each step with its attempt, state and cost, the checkpoints reached and every rewind.

```
weave timeline [OPTIONS] <runDir>
```

**Arguments**

- `<runDir>` — The run directory (as given to run --journal).

**Options**

- `--json` — Print the timeline as JSON.

### `weave rewind`

Sends a run back to a checkpoint (or to a step, which then runs again).

Nothing is deleted: the old attempt stays as history.

```
weave rewind [OPTIONS] <runDir>
```

**Arguments**

- `<runDir>` — The run directory.

**Options**

- `--ask-again` — Ask people the questions again, even identical ones.
- `--effects=<effects>` — What to do about side effects already done: ask-first (the default: refuse), keep, repeat.
- `--force` — Change the run even if a process seems to be working on it.
- `--reason=<reason>` — Why (recorded in the journal).
- `--resume` — Carry on running the workflow from there.
- `--set=<String=String>` — A value to carry into the new attempt: name=value (repeatable).
- `--to=<to>` — A checkpoint name, "start", or the id of a step (the first one to run again).
- `--trigger` — Instead of running now, leave a resume trigger for `weave tick` or `weave daemon` to pick up.

### `weave reset`

Starts a run again from the top as a new attempt (spend history kept), or with --failed tries only the failed steps again.

```
weave reset [OPTIONS] <runDir>
```

**Arguments**

- `<runDir>` — The run directory.

**Options**

- `--effects=<effects>` — What to do about side effects already done: ask-first (the default: refuse), keep, repeat.
- `--failed` — Only the steps that failed are tried again; everything else stays as it was.
- `--force` — Change the run even if a process seems to be working on it.
- `--reason=<reason>` — Why (recorded).
- `--resume` — Carry on running the workflow afterwards.
- `--trigger` — Instead of running now, leave a resume trigger for `weave tick` or `weave daemon` to pick up.

### `weave fork`

Copies a run into a new run directory (the original is only read), optionally going back to a point, with other values or another script.

```
weave fork [OPTIONS] <runDir>
```

**Arguments**

- `<runDir>` — The run directory to copy.

**Options**

- `--allow-drift` — Accept a script whose earlier statements differ from the run's.
- `--at=<at>` — A checkpoint or step id to go back to in the copy.
- `--effects=<effects>` — keep (the default: identical effects are found, not repeated), simulate (nothing is performed in the copy, ever), or repeat.
- `--reason=<reason>` — Why (recorded in the copy).
- `--resume` — Run the copy now.
- `--script=<script>` — Run the copy under another script.
- `--set=<String=String>` — A value to carry into the new attempt: name=value (repeatable; needs --at).
- `--to=<newDir>` — The new run directory.
- `--until=<until>` — Stop the copy cleanly once this step has completed.

### `weave why`

Explains a run: each choice it made, over which values, where they came from, and the way it did not go.

Reads the run's journal; runs nothing.

```
weave why [OPTIONS] <run> [<what>]
```

**Arguments**

- `<run>` — The run directory (as given to run --journal), or a run id with --store.
- `<what>` — A statement to ask about: a step id (Main/s2), a graph node (n7) or a line number.

**Options**

- `--all` — Every step, not only the choices and the steps that did something.
- `--format=<format>` — text (default), md or json.
- `--line=<line>` — Ask about the statement on this line.
- `--node=<node>` — Ask about this graph node.
- `--script=<script>` — Explain against this script instead of the one the run used (needed when the script has changed since).
- `--since=<since>` — Only what happened after this step.
- `--step=<step>` — Ask about this step id.
- `--store=<store>` — The trigger store whose runs/ folder holds the run id.

### `weave whatif`

Changes one thing in a past run and shows how it would have gone: the trajectory, the result and the effects that differ.

Runs nothing; the run is not written.

```
weave whatif [OPTIONS] <run>
```

**Arguments**

- `<run>` — The run directory, or a run id with --store.

**Options**

- `--answer=<String=String>` — What an agent or classifier says there: Name=text, or Name={json} (a classifier takes a label for each question) (repeatable).
- `--at=<at>` — The moment to go back to: a step id, a graph node (n3) or a source line.
- `--effects=<effects>` — simulate (the only mode here: nothing is ever sent or written). (default: simulate)
- `--fail=<String=String>` — A failure to inject: Target=category[:times], for example Replier=rate_limit:2 (repeatable).
- `--format=<format>` — text (default), md or json.
- `--human=<humans>` — What a person answers there (repeatable, in order).
- `--max-cost=<maxCost>` — With --models live: the most it may cost.
- `--max-tokens=<maxTokens>` — With --models live: the most tokens it may use.
- `--model=<String=String>` — An agent's model in the candidate: Agent=model-id (repeatable).
- `--models=<models>` — recorded (default: reuse a recorded answer where its question is unchanged), scripted (only the answers given) or live (ask the real models where there is no recorded answer, under --max-cost or --max-tokens; tools that change things are only described and no person is asked; tools known to change nothing, such as search, still run).
- `--prices=<prices>` — The price table for --max-cost.
- `--prompt=<String=String>` — An agent's prompt in the candidate: Agent=@v2 (another version of its prompt file) or Agent=file (the text of a file) (repeatable).
- `--save=<save>` — Write the candidate as a new run directory, to open with weave why.
- `--script=<candidateScript>` — A candidate script to run forward instead of the one that ran (an edited copy).
- `--set=<String=String>` — A variable at that step: name=value (repeatable).
- `--store=<store>` — The trigger store whose runs/ folder holds the run id.
- `--tool=<String=String>` — What a tool returns: Name=text (repeatable).
- `--until=<until>` — Stop before this step.
- `--yes` — With --models live: go ahead and spend, up to the cap.

### `weave step`

Runs a workflow one statement at a time, with breakpoints, the variables and the way back.

Nothing has an effect.

```
weave step [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--break=<breaks>` — A breakpoint to start with: a line, n3, an Agent, or "when <condition>" (repeatable).
- `--effects=<effects>` — simulate (the only mode here: nothing is sent or written). (default: simulate)
- `-i, --input <String=String>` — An input of the workflow: name=value (repeatable).
- `--journal=<journal>` — A run directory to keep the steps in. If it holds a run, stepping starts from what that run did; on quit what was done is written there, so weave why and weave resume can use it.
- `--mask=<masked>` — A variable never shown (repeatable).
- `--max-cost=<maxCost>` — With --models live: the most it may cost.
- `--max-tokens=<maxTokens>` — With --models live: the most tokens it may use.
- `--models=<models>` — mock (default: every reply is a fixed, well-formed one) or live (ask the real models, under --max-cost or --max-tokens and --yes; tools that change things are still only described).
- `--prices=<prices>` — The price table for --max-cost.
- `--record=<record>` — Write the commands of this session to a file.
- `--replay=<replay>` — Run the commands of a recorded session instead of reading them.
- `-w, --workflow <workflow>` — The workflow to run (default: the first).
- `--yes` — With --models live: go ahead and spend, up to the cap.

### `weave dap`

Speaks the Debug Adapter Protocol on standard input and output, so an editor can debug a workflow.

Nothing has an effect.

```
weave dap
```

### `weave generate`

Turns a description into a plan (PLAN.md and plan.json) and a plan into a project that passes check, review and its own tests.

--gate-only runs the same gates on any project.

```
weave generate [OPTIONS] [<description>]
```

Describe a workflow in your own words. generate asks at most five questions (with defaults, skip them with --no-ask), writes a plan (PLAN.md and plan.json) and shows it, then writes the project: the script, a prompt file for each agent, a tool mapping if needed, tests, a golden-dataset stub and a README. The project is moved into place only when it has passed five gates: G1 check, G2 review (no high finding after the safe fixes), G3 tests, G4 coverage of the script's steps by those tests, and G5 the script matches the plan. A failing gate is fed back to the model with the line and the reason, for at most six tries per gate and twelve in all.
The tests are mostly written by the tool: tests/derived.test.yaml runs the script with scripted models down every classifier label, every enum answer, every failing step and every value its conditions compare with, and records the routes. The model writes only the cases the plan lists; one that does not hold is listed in tests/LEFT_OUT.md instead of being repaired. Nothing here calls a model during the tests.

**Arguments**

- `<description>` — What the workflow should do, in your words.

**Options**

- `--accept-worse` — With --edit --apply: apply a change that makes the review or the worst-case cost worse.
- `--allow-model=<allowedModels>` — A model the workflow's agents may use (repeatable; default: any).
- `--answer=<answer>` — With --edit: the answer to the question the model asked about an ambiguous change.
- `--apply` — With --edit: write the change. Without it the diff is only shown.
- `--apply-patch=<applyPatch>` — With --edit: apply a saved patch (checked by the same gates; no model).
- `--context=<context>` — Existing files the model may read (prompts, tools.loot, a sibling script). Given to the model as data. (repeatable)
- `--coverage=<coverage>` — G4: the share of the script's steps the generated tests must reach (default 80).
- `--edit=<edit>` — Change an existing project: the script whose project is changed (the description is the change).
- `--env-file=<file>` — Read keys from this file (NAME=value lines) instead of the .env beside the script (or at the project root). For development: keep it out of git.
- `--flat` — A plain folder (main.loom, prompts/, tests/ beside each other) instead of the Maven layout.
- `--format=<format>` — text (default) or json.
- `--from-plan=<fromPlan>` — Build from this (edited) plan.json instead of asking the model for one.
- `--gate-level=<gateLevel>` — Findings in the validity, reliability and prompts lenses at or above this fail G2 (default medium; high findings always fail).
- `--gate-only=<gateOnly>` — Run the gates on an existing project folder and say what fails.
- `--interactive` — Ask the questions and show the plan for a yes before building.
- `--into-existing` — Add the project to a folder that already holds files (nothing is replaced).
- `--keep-failing` — Leave a failing draft in <out>.draft for inspection.
- `--max-attempts=<maxAttempts>` — Most repair rounds for one gate (default 6; 12 in all).
- `--max-cost=<maxCost>` — The most the generation may cost, in dollars (default 0.50; needs --prices to count).
- `--max-tokens=<maxTokens>` — The most tokens the generation may use (default 500000).
- `--model=<model>` — The model that writes the files (default: LOOM_GENERATE_MODEL); also the one that writes the plan unless --plan-model is given.
- `--no-ask` — Do not ask questions; use the proposed answers and list them in the plan.
- `--no-env-file` — Do not read a .env file, even if one is beside the script.
- `--out=<out>` — Where the plan or the project goes (default: a folder named after the workflow).
- `--patch-out=<patchOut>` — With --edit: save the patch to a file, to apply later without the model.
- `--plan-model=<planModel>` — A different, usually stronger, model for the questions and the plan; --model still writes the files and repairs them. The plan is what the gates hold the files to, so a better plan costs little and helps most.
- `--plan-only` — Stop at the plan: write plan.json and PLAN.md and nothing else.
- `--prices=<prices>` — The price table that turns tokens into money for --max-cost.
- `--record=<record>` — Save the model's side of this generation to a file (no request text, no keys).
- `--replay-by-order` — With --replay: answer the n-th call with the n-th recorded reply whatever is asked, to run a recording through gates that have changed.
- `--replay=<replay>` — Answer from a recorded transcript instead of asking a model.
- `--timeout=<timeoutSeconds>` — Seconds to wait for each reply from the model (default 300: a whole project is one reply).
- `--worse-factor=<worseFactor>` — With --edit: the most the worst-case tokens of a workflow may grow, as a multiple (default 1.5).
- `--yes` — Build without asking whether the plan is right.

**Examples**

```
weave generate "Triage support emails; send refunds over 100 dollars to a person" --model gpt-6-luna
weave generate "..." --plan-model claude-sonnet-5-5 --model gpt-6-luna     a stronger model plans, a cheap one writes
weave generate "..." --plan-only                                         only the plan, to read and edit
weave generate --from-plan support/plan.json --model gpt-6-luna          build from an edited plan
weave generate --edit support/main.loom "Also escalate legal threats"    a change, shown as a diff; add --apply to write it
weave generate --gate-only support/                                      run the five gates on any project
```

**Exit status**

0 done. 1 usage. 2 could not run (no model, no key, a file in the way). 3 a gate or the plan did not pass (nothing is written; --keep-failing leaves <out>.draft). 4 the spend cap (--max-cost, needs --prices; --max-tokens) was reached.

**See also**

weave man check, weave man review, weave man test, weave man eval, wiki page "Generating a Workflow"

### `weave capture`

Turns a real run into a test case with the recorded answers scripted in, so it reproduces what production did with no model.

Personal data and secrets are masked first; the case is 'observed' until a person approves it.

```
weave capture [OPTIONS] [<run folder or id>]
```

Turns a real run from the run store into a test case: the answers it got are scripted in, so the case reproduces what production did with no model. Personal data and secrets are masked first. The case is marked observed until a person approves it with --approve. The tool never writes into a source repository you did not point it at, and never deletes captured text.

**Arguments**

- `<run folder or id>` — The run to capture (a run folder, or an id in --store).

**Options**

- `--amend=<amend>` — With --approve: change what the case expects, final=... or result=..., in the same step. (repeatable)
- `--approve=<approve>` — Approve a captured case file after reading it.
- `--as=<as>` — test (default): a test case; golden: an entry of the evaluation dataset; both.
- `--by=<by>` — Who approves or retires (default: git user.email, else the OS user).
- `--dry-run` — Print the case, write nothing.
- `--expect=<expect>` — observed: fill the expectations from what happened (default); none: only how it ends.
- `--limit=<limit>` — With --select: how many (default 5).
- `--list` — List the runs worth capturing, and why.
- `--mask-keep=<maskKeep>` — An input or agent whose values are kept as they are (you take the responsibility). (repeatable)
- `--mask-term=<maskTerm>` — A name or word to mask that no pattern can find (repeatable).
- `--mask=<mask>` — pii (default): mask personal data and secrets; none: leave personal data (secrets are always taken out).
- `--name=<name>` — The case's file name (default: from the workflow, the run and the date).
- `--note=<note>` — Why this run was captured.
- `--retire=<retire>` — Retire a captured case file (no longer applicable).
- `--select=<select>` — Capture the newest runs of a class: failed, diverted, unsure, overridden.
- `--since=<since>` — With --list or --select: only runs this recent (14d, 12h, or a date).
- `--store=<store>` — Where past runs are kept (default: .loom-triggers).
- `--tag=<tag>` — A tag for the case (repeatable).
- `--to=<to>` — The folder for the case (default: tests/captured beside the script).
- `--why=<why>` — Why, for the record.
- `--yes` — Agree to what capture does, once for this repository.

**Examples**

```
(run ids come from `weave capture --list`)
weave capture --list                             the runs that could be captured
weave capture <run-id> --dry-run                 show the masked case, write nothing
weave capture <run-id> --name late-refund --mask-term "Priya"
weave capture --approve tests/captured/late-refund.test.yaml     after reading it
```

**Exit status**

0 done. 1 usage. 2 could not (refused, or the case would not hold). 3 a different file is already there.

**See also**

weave man test, weave man regress, wiki page "From an Incident to a Regression Test"

### `weave regress`

Tries a candidate script, prompt or model on a sample of real past runs and reports what changes, unsafe changes first.

Uses recorded answers only: no model, no tool, no effect; the run store is only read.

```
weave regress [OPTIONS] <candidate.loom>
```

**Arguments**

- `<candidate.loom>` — The script to try.

**Options**

- `--baseline-git=<baselineGit>` — Take the baseline from this git revision.
- `--baseline=<baseline>` — The script that produced the runs (default: the version each run recorded, found in git history).
- `--cases=<cases>` — A folder of captured cases; the approved ones are always run against the candidate. (repeatable)
- `--fail-on=<failOn>` — flip-unsafe (default), flip-any, cost-up:<percent> or none: exit 1 on this.
- `--format=<format>` — text (default), json, md or junit.
- `--judge-model=<judgeModel>` — The model that judges text with rubric: (classifier: uses its own).
- `--judge=<judge>` — How free text is compared: none (default; only typed results), exact or label.
- `--limit=<limit>` — Most runs to compare (default 200).
- `--mask-term=<maskTerm>` — A name to mask in --show-text output (repeatable).
- `--max-cost=<maxCost>` — The most live models and judges may cost, in dollars, in all (needs --prices).
- `--max-tokens=<maxTokens>` — The most live models and judges may use, in tokens, in all.
- `--model=<String=String>` — Try a model: Agent=<model id> (repeatable).
- `--models=<models>` — recorded (default): only recorded answers; live: ask a real model for the runs whose question changed (needs --max-cost or --max-tokens).
- `--per-run-seconds=<perRun>` — Give up on one run after this long (default 30).
- `--prices=<prices>` — A price file (model = in / out per million tokens), to count money.
- `--prompt=<String=String>` — Try a prompt: Agent=<file> or Agent=@version (repeatable).
- `--report=<report>` — Also write a single self-contained HTML file.
- `--sample=<sample>` — random, recent, stratified (default) or failures-first.
- `--seed=<seed>` — The seed of the sample (default 7).
- `--show-text` — Include the (masked) result text of unsafe runs.
- `--since=<since>` — Only runs this recent (14d, 12h, or a date).
- `--store=<store>` — Where past runs are kept (default: .loom-triggers).

**Examples**

```
weave regress main.loom --since 7d     try a changed script on past runs (store: .loom-triggers by default; --store DIR)
weave regress main.loom --prompt Replier=prompts/replier-v2.md      try a changed prompt
weave regress main.loom --model Replier=claude-haiku-5-5 --fail-on flip-unsafe     try another model
```

**Exit status**

0 nothing at or above --fail-on. 1 a failing condition (default: a changed outcome that is unsafe). 2 could not run.

**See also**

weave man capture, weave man gate

### `weave gate`

Runs the checks your team chose (check, review, test, regress, eval) and prints one verdict, for CI.

Skipped stages are shown, and --require makes a skip a failure.

```
weave gate [OPTIONS] [<script.loom>]
```

**Arguments**

- `<script.loom>` — The workflow (default: main.loom here).

**Options**

- `--base-ref=<baseRef>` — Compare with this git revision (the base of a pull request).
- `--candidate-ref=<candidateRef>` — Check the script at this git revision instead of the working copy.
- `--config=<config>` — The gate configuration (default: gate.properties beside the script).
- `--format=<format>` — text (default), md, junit or sarif (the review's findings).
- `--include-observed` — Let captured cases that are still 'observed' fail the gate too.
- `--print-workflow=<printWorkflow>` — Print a starting CI configuration: github or jenkins.
- `--require=<require>` — Stages that must run: a skip of one fails the gate (check, review, test, regress, eval). (repeatable)
- `--store=<store>` — The run store for the regress stage (overrides regress.store).

**Examples**

```
weave gate                               the checks your team chose, one verdict
weave gate main.loom --require test,review    a skipped stage is a failure
weave gate --base-ref origin/main        compare with the base of a pull request
```

**Exit status**

0 passed. 1 failed. 2 could not run (a bad option or configuration).

**See also**

weave man check, weave man review, weave man test, weave man regress, weave man eval

### `weave bindgen`

Writes typed Java bindings for a script: one method per workflow, a record per result, and an Outcome that is Done, Waiting or Stopped.

--check fails when the files on disk are out of date.

```
weave bindgen [OPTIONS] <script.loom>
```

**Arguments**

- `<script.loom>` — The script.

**Options**

- `--check` — Write nothing; exit 1 when the files on disk differ from what would be written.
- `--facade=<facade>` — The facade's name (default: from the script's name).
- `--nullability=<nullability>` — How a field not set on every path is shown: none (plain, documented), jspecify (@Nullable; needs org.jspecify) or optional (Optional<T>). (default: none)
- `--out=<out>` — The source root to write into (default src/main/java).
- `--package=<pkg>` — The Java package of the generated files, like com.acme.triage.

**Examples**

```
weave bindgen main.loom --package com.acme.support --out src/main/java
weave bindgen main.loom --package com.acme.support --out src/main/java --check     CI: fail when out of date
```

**Exit status**

0 done (or up to date). 1 with --check, the files differ from what would be written. 2 could not run.

**See also**

weave man deploy, wiki page "Typed Workflows in Java and Deploying"

### `weave deploy`

Writes a container recipe, a compose file or Kubernetes manifests for a workflow, from the script: env names, hosts, volumes and schedules are read from it.

Writes text only; secrets never appear. --verify checks a directory against the script.

```
weave deploy [OPTIONS] <script.loom>
```

**Arguments**

- `<script.loom>` — The script.

**Options**

- `--base-image=<baseImage>` — The runtime image; give tag@sha256:... to pin it.
- `--dry-run` — Print the files, write nothing.
- `--expose=<expose>` — none, or http:PORT for an app that listens. (default: none)
- `--fabric-image=<fabricImage>` — The Fabric server image.
- `--fabric-publish=<fabricPublish>` — The address Fabric's page is published on (loopback only). (default: 127.0.0.1)
- `--force` — Replace files that were edited by hand.
- `--journal=<journal>` — volume, jdbc:URL or none. (default: volume)
- `--name=<name>` — The app and image name (default: the script's name).
- `--out=<out>` — Where to write (default ./deploy).
- `--require-clean` — Exit 1 when open high findings remain in the review.
- `--schedule=<schedule>` — keep (run the daemon for the script's schedules) or none. (default: keep)
- `--target=<target>` — docker, compose or k8s. (default: compose)
- `--verify=<dir>` — Check a directory against the script; exit 1 on drift.
- `--with-fabric` — Include a Fabric server and report to it (default yes for compose and k8s).

**Examples**

```
weave deploy main.loom --target docker --out deploy/
weave deploy main.loom --target compose --with-fabric --out deploy/
weave deploy main.loom --target k8s --out deploy/
weave deploy main.loom --verify deploy/      check that the files still match the script
```

**Exit status**

0 done. 1 with --verify, the directory differs from the script; with --require-clean, high findings remain in the review. 2 could not run.

**See also**

weave man bindgen, weave man audit

### `weave try`

Runs a bundled workflow on demo models (pre-written replies; no key, no network, nothing written) and explains each step.

--list shows the scenarios; --keep DIR leaves the project to edit.

```
weave try [OPTIONS] [<scenario>]
```

Runs a workflow that ships inside the jar on demo models whose replies are pre-written, so it needs no key and no network and writes nothing. It explains each step as it goes. Use it to see what Loom does before you write anything.

**Arguments**

- `<scenario>` — Which scenario (default triage).

**Options**

- `--fast` — No pauses, for scripts.
- `--keep=<dir>` — Write the scenario's project into this folder, to open and edit.
- `--list` — List the scenarios and what each shows.
- `--live` — Run with the models you have configured instead of the demo models (needs keys; spends up to the cap).
- `--max-tokens=<maxTokens>` — With --live: the most tokens it may use (default 30000, about five cents on a small model).
- `--no-color` — Plain text (also NO_COLOR).
- `--step` — Pause between steps; press Enter to go on.

**Examples**

```
weave try                      the support-triage demo, with pauses
weave try --list               the scenarios and what each shows
weave try flaky --fast         a step that fails and is retried, without pauses
weave try triage --keep demo   leave the project in ./demo to open and edit
```

**Exit status**

0 the scenario ran. 2 unknown scenario or bad option.

**See also**

weave man doctor, weave man init, weave man next

### `weave doctor`

Checks the setup (Java, the script, the keys it needs by name, models, the run store, prices, Fabric) and says the one thing to do for each problem.

--network also tests the connection to each provider. Exit 0 ready, 1 ready with warnings, 2 not ready.

```
weave doctor [OPTIONS] [<script.loom>]
```

**Arguments**

- `<script.loom>` — The script (default: a .loom in this folder).

**Options**

- `--fix` — Do the safe local fixes (create the run store folder, ignore .env in git, copy .env.example to .env), asking first.
- `--format=<format>` — text (default) or json (versioned).
- `--network` — Also test a TLS connection to each provider host the script uses (no request, no key is sent).
- `--yes` — With --fix: agree without asking.

**Examples**

```
weave doctor                      check this folder
weave doctor main.loom --network  also test the connection to each provider the script uses
weave doctor --fix                create the run store, ignore .env in git, copy .env.example (asks first)
weave doctor --format json        for CI or an editor
```

**Jars**

Doctor lists the Loom jars it can reach (the one running, the local Maven repository, the editor extension's bundled jar, WEAVE_JAR) and, only when two share a version number but are different builds, says which and how to fix it. It reads local files and says nothing when they agree.

**Exit status**

0 ready. 1 ready with warnings. 2 not ready.

**See also**

weave man check, weave man try

### `weave autonomy`

Where each decision's ladder stands, and the ways a person moves it.%n  status | history | promote | demote | approve | reject | freeze | unfreeze | outcome | verify

```
weave autonomy <command>
```

**Commands**

- `status` — Per decision and scope: the level, the evidence, agreement and its lower bound, what is missing for the next step, stale cases, the last change.
- `history` — Every level change, promotion proposal and freeze, with time, scope, from, to, reason and who approved.
- `promote` — Moves a scope up by hand.
- `demote` — Moves a scope down by hand, at once.
- `approve` — Approves the open promotion proposal of a scope (only the approver the script names may).
- `reject` — Rejects the open promotion proposal of a scope; it is not asked again until the evidence has grown.
- `freeze` — Stops act at once for a decision (or all): cases that begin after this run at suggest at most.
- `unfreeze` — Lifts a freeze; each scope runs at the level it earned.
- `outcome` — Records a later fact about a case (reversed, upheld, …).
- `verify` — Checks that the record of levels, promotions and freezes is as the runtime wrote it.

#### `weave autonomy status`

Per decision and scope: the level, the evidence, agreement and its lower bound, what is missing for the next step, stale cases, the last change.

```
weave autonomy status [OPTIONS] <store>
```

**Arguments**

- `<store>` — The run store (the directory that holds the triggers and the autonomy files).

**Options**

- `--decision=<decision>` — Only this decision.
- `--json` — Print JSON.
- `--script=<script>` — The script that declares the decision (default: the one it was last run from).

#### `weave autonomy history`

Every level change, promotion proposal and freeze, with time, scope, from, to, reason and who approved.

```
weave autonomy history [OPTIONS] <store>
```

**Arguments**

- `<store>` — The run store.

**Options**

- `--decision=<decision>` — Only this decision.

#### `weave autonomy promote`

Moves a scope up by hand.

Beyond the ceiling or beyond what the evidence supports it needs --force, and is then shown as forced.

```
weave autonomy promote [OPTIONS] <store> <decision>
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision.

**Options**

- `--by=<by>` — Who is doing this (default: the operating system user).
- `--force` — Do it even though the ceiling or the evidence says no.
- `--reason=<reason>` — Why (recorded).
- `--scope=<scope>` — The scope (the value the cases are grouped by).
- `--script=<script>` — The script that declares the decision.
- `--to=<to>` — The level to move to (default: one step up).

#### `weave autonomy demote`

Moves a scope down by hand, at once.

```
weave autonomy demote [OPTIONS] <store> <decision>
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision.

**Options**

- `--by=<by>` — Who is doing this (default: the operating system user).
- `--reason=<reason>` — Why (recorded).
- `--scope=<scope>` — The scope (the value the cases are grouped by).
- `--script=<script>` — The script that declares the decision.
- `--to=<to>` — The level to move to (default: one step down).

#### `weave autonomy approve`

Approves the open promotion proposal of a scope (only the approver the script names may).

```
weave autonomy approve [OPTIONS] <store> <decision>
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision.

**Options**

- `--by=<by>` — Who is doing this (default: the operating system user).
- `--reason=<reason>` — Why (recorded).
- `--scope=<scope>` — The scope (the value the cases are grouped by).
- `--script=<script>` — The script that declares the decision.

#### `weave autonomy reject`

Rejects the open promotion proposal of a scope; it is not asked again until the evidence has grown.

```
weave autonomy reject [OPTIONS] <store> <decision>
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision.

**Options**

- `--by=<by>` — Who is doing this (default: the operating system user).
- `--reason=<reason>` — Why (recorded).
- `--scope=<scope>` — The scope (the value the cases are grouped by).
- `--script=<script>` — The script that declares the decision.

#### `weave autonomy freeze`

Stops act at once for a decision (or all): cases that begin after this run at suggest at most.

A case in progress finishes as it began.

```
weave autonomy freeze [OPTIONS] <store> [<decision>]
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision (default: all decisions).

**Options**

- `--reason=<reason>` — Why (recorded).

#### `weave autonomy unfreeze`

Lifts a freeze; each scope runs at the level it earned.

```
weave autonomy unfreeze [OPTIONS] <store> [<decision>]
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision (default: all decisions).

**Options**

- `--reason=<reason>` — Why (recorded).

#### `weave autonomy outcome`

Records a later fact about a case (reversed, upheld, …).

A reversal of a verdict the agent made counts against it.

```
weave autonomy outcome [OPTIONS] <store> <decision> <caseId>
```

**Arguments**

- `<store>` — The run store.
- `<decision>` — The decision.
- `<caseId>` — The case id (shown by status and in the ledger).

**Options**

- `--note=<note>` — A note (recorded).
- `--result=<result>` — reversed, upheld, or another word (the name of the outcome).
- `--script=<script>` — The script that declares the decision.
- `--value=<value>` — A value that goes with the result, such as a number of views (a number if it looks like one).

#### `weave autonomy verify`

Checks that the record of levels, promotions and freezes is as the runtime wrote it.

With --accept an operator who has looked says the record, as it stands, is to be trusted; the runtime goes on from there.

```
weave autonomy verify [OPTIONS] <store>
```

**Arguments**

- `<store>` — The run store.

**Options**

- `--accept` — Accept the record as it stands (needs --reason).
- `--by=<by>` — Who accepts it (default: you).
- `--decision=<decision>` — Only this decision.
- `--reason=<reason>` — Why the record is accepted.

### `weave ladder`

Describes a ladder in plain sentences, or tries it on a recorded history.%n  describe | test

```
weave ladder <command>
```

**Commands**

- `describe` — Says in plain sentences what a ladder requires to climb, what makes it slip and who is asked: generated from the parsed ladder, so what is read is what is enforced.
- `test` — Tries a ladder on the history in a store: for each scope the rung it would hold and each condition's value.

#### `weave ladder describe`

Says in plain sentences what a ladder requires to climb, what makes it slip and who is asked: generated from the parsed ladder, so what is read is what is enforced.

```
weave ladder describe <script> [<name>]
```

**Arguments**

- `<script>` — The .loom script.
- `<name>` — Only this ladder.

#### `weave ladder test`

Tries a ladder on the history in a store: for each scope the rung it would hold and each condition's value.

Writes nothing.

```
weave ladder test [OPTIONS] <script> <decision>
```

**Arguments**

- `<script>` — The .loom script.
- `<decision>` — The decision.

**Options**

- `--store=<store>` — The run store whose recorded history is used.

### `weave replay`

Replays a decision's past cases under a candidate script, with nothing sent and nothing changed, and reports how it would have done.

```
weave replay [OPTIONS] [<script>]
```

**Arguments**

- `<script>` — The script that declares the decision (the candidate, unless --candidate is given).

**Options**

- `--allow-drift` — Accept a candidate whose script differs before the decide step (the cases are flagged).
- `--candidate=<candidate>` — A changed script to try instead of the one given.
- `--decision=<decision>` — The decision to replay.
- `--format=<format>` — md or json. (default: md)
- `--limit=<limit>` — At most this many cases, after a seeded shuffle (default 500).
- `--live-reads` — A read the case did not record runs live (flagged non-deterministic) instead of making the case unreplayable.
- `--max-cost=<maxCost>` — Stop cleanly after spending this much (replayed models need a price table).
- `--max-tokens=<maxTokens>` — Stop cleanly after this many tokens.
- `--no-memory` — Do not run memory or knowledge reads in the replay.
- `--policy=<policy>` — A file that replaces the file of the same name the candidate's agent reads, for the candidate only.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).
- `--repeat=<repeat>` — Replay each case this many times and report how stable the choice is. (default: 1)
- `--report=<report>` — Write the report to this file as well.
- `--resume=<resume>` — Carry on a replay that was cut short, by its id.
- `--scope=<scope>` — Only this scope.
- `--seed=<seed>` — The seed of the shuffle: the same seed gives the same sample. (default: 1)
- `--since=<since>` — Only cases decided since: a duration (14d, 12h) or a date (2026-03-01).
- `--store=<store>` — The run store the decision's cases are in.

### `weave audit`

Reviews a script's security without running it: what each agent can reach, where untrusted content, private data and a way out meet in one agent, unapproved effects, budgets and outside dependencies.

Findings name the OWASP Top 10 for LLM Applications risks.

```
weave audit [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--context-windows=<file>` — A windows file: 'model = window / reserve' per line (the size of each model's context window). Repeatable; a later file wins. Layered over <script>.windows, ~/.llm4j/context-windows and the built-in catalogue. (repeatable)
- `--fail-on=<failOn>` — Exit 1 when a finding is at least this severe: high (default), medium, low, info or none.
- `--format=<format>` — md (default) or json.
- `-l, --loot <lootFile>` — The .loot tool mapping file: its Name.reach lines say what your own tools reach (the audit never loads their classes).
- `--loct=<file>` — A .loct file mapping a name to a class of your own compaction strategy ('keep_decisions = com.acme.KeepDecisions'), for use as 'compact: keep_decisions' in a script. Repeatable. (repeatable)
- `--out=<out>` — Also write the report to this file.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).

### `weave review`

Reviews a script without running it: security, validity and the other lenses in one ranked list, each finding with why it matters and what to do.

Advisory: it exits 1 on findings only when you ask with --fail-on.

```
weave review [OPTIONS] [<script>]
```

Reads the script, never runs it. Findings are ranked, each with why it matters and what to do; some come with a fix the tool can apply (--fix, --dry-run first). Advisory by default: it exits 1 only when you give --fail-on.

**Arguments**

- `<script>` — The .loom script to review.

**Options**

- `--accept=<fingerprint|RULE>` — Accept open findings with a reason: one finding by its fingerprint, or every open finding of a rule. Written to review.properties; run and deploy are not affected.
- `--assist` — Go through the findings that have options with you: choose, answer, see the change, confirm.
- `--assume=<name=value>` — Override a cost assumption, such as loop.iterations=5 (repeatable). weave review --explain cost lists them.
- `--baseline-include-high` — Let --baseline-write record serious findings too.
- `--baseline-note=<baselineNote>` — The note --baseline-write stores. (default: Existing findings when review was adopted.)
- `--baseline-prune` — Remove baseline entries whose finding is gone.
- `--baseline-write` — Record today's open findings as known in review.baseline.json.
- `--baseline=<baselineFile>` — The baseline file (default: review.baseline.json beside the script).
- `--by=<by>` — Who accepts it (default: the user name).
- `--calibrate=<run-dir>` — Compare the cost estimate with what a journaled run (a folder made with a run store) really spent, and say which assumptions to correct.
- `--classes=<dir|jar>` — Folder or jar with the classes of your own tasks and tools, repeatable (weave only sees what is on its class path). Default: target/classes of the project, when it has been built (mvn compile).
- `--confirm=<confirm>` — The number of serious findings --accept is about to accept.
- `--context-windows=<file>` — A windows file: 'model = window / reserve' per line (the size of each model's context window). Repeatable; a later file wins. Layered over <script>.windows, ~/.llm4j/context-windows and the built-in catalogue. (repeatable)
- `--dry-run` — With a fix option: print the diff and change nothing; exit 1 if fixes are pending.
- `--env-file=<file>` — Read keys from this file (NAME=value lines) instead of the .env beside the script (or at the project root). For development: keep it out of git.
- `--explain=<RULE>` — Explain one rule (such as LA01): what it checks, why, and what to do.
- `--fail-on=<failOn>` — Exit 1 when a finding is at least this severe: high, medium, low, info or none. Default: none, because review is advisory.
- `--fix` — Apply every safe fix, checking each result, then review again.
- `--fix-finding=<fingerprint>` — Apply the fix of one finding (with --yes if it needs your judgement).
- `--fix-rule=<RULE>` — Apply the fixes of one rule, including ones that need your judgement (with --yes).
- `--format=<format>` — text (default), md, json or sarif (for code scanning).
- `--lens=<lens>` — Which lenses run: 'all', exact names (security,cost), or +name / -name to change the defaults.
- `-l, --loot <lootFile>` — The .loot tool mapping file: its Name.reach lines say what your own tools reach.
- `--loct=<file>` — A .loct file mapping a name to a class of your own compaction strategy ('keep_decisions = com.acme.KeepDecisions'), for use as 'compact: keep_decisions' in a script. Repeatable. (repeatable)
- `--min-severity=<minSeverity>` — Show findings at least this severe: high, medium, low or info (default).
- `--no-env` — Review without keys: names that are not set are not problems.
- `--no-env-file` — Do not read a .env file, even if one is beside the script.
- `--noise-report` — Say which rules are noisy: accepted in many of the places they fire.
- `--noise-share=<noiseShare>` — Percent of places accepted that makes a rule noisy. (default: 50)
- `--on=<on>` — The agent, tool or workflow for --scope thing.
- `--option=<id>` — With --fix-finding: apply this option of the finding (see its options).
- `--out=<out>` — Also write the report to this file.
- `--prices=<prices>` — The price table (lines of 'model = input / output' per million tokens), as for weave run. Without it the cost is shown in tokens.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).
- `--reason=<reason>` — Why the finding is accepted (required with --accept).
- `--rules-jar=<rulesJars>` — A jar of Java rules (io.github.llm4j.loom.review.engine.ReviewRule, found with ServiceLoader). Repeatable. Nothing is loaded unless you name it. (repeatable)
- `--rules=<rulesFile>` — The settings file (default: review.properties beside the script).
- `--scope=<scope>` — How much --accept covers: finding (default), thing, script or project.
- `--set=<name=value>` — With --option: the answer to one of the option's questions. Repeatable. (repeatable)
- `--strict-accepts` — Count accepted findings when deciding --fail-on.
- `--until=<until>` — Look at an accepted finding again on this date, such as 2026-12-31.
- `--yes` — Confirm fixes that need your judgement.

**Examples**

```
weave review main.loom
weave review main.loom --fail-on high         exit 1 on a high finding, for CI
weave review main.loom --fix --dry-run        show the safe fixes as a diff
weave review main.loom --explain LA01         what a rule means
```

**Exit status**

0 unless --fail-on (or --dry-run with fixes pending) says otherwise. 2 it could not run.

**See also**

weave man audit, weave man rules, weave man check

### `weave rules`

Works with review rules and rule packs, offline: list, facts, check, test, new, add, diff, docs, explain.

```
weave rules <command>
```

**Commands**

- `list` — Lists the rule packs in force and their rules.
- `facts` — Prints the fact catalog that rules are written against.
- `check` — Checks that a rule pack is sound and prints its hash.
- `test` — Runs the firing and quiet examples of every rule in a pack.
- `new` — Adds a rule skeleton and its two example scripts to a pack.
- `add` — Copies a rule pack into this project's review-rules/ and pins its version and hash in review.properties.
- `diff` — Compares two versions of a rule pack: rules added, removed and changed, the version bump the change needs, and (with --on) the findings that would appear or disappear on a script.
- `docs` — Writes the rule reference (one page per rule and an index) generated from a pack and the built-in rules.
- `explain` — Shows, for one rule at one place, every condition and the value it saw, so you can see why the rule did or did not fire.

#### `weave rules list`

Lists the rule packs in force and their rules.

```
weave rules list [OPTIONS]
```

**Options**

- `--dir=<dir>` — The project folder (review.properties and review-rules/). Default: here.

#### `weave rules facts`

Prints the fact catalog that rules are written against.

```
weave rules facts
```

#### `weave rules check`

Checks that a rule pack is sound and prints its hash.

```
weave rules check <pack>
```

**Arguments**

- `<pack>` — The pack folder, or 'core'.

#### `weave rules test`

Runs the firing and quiet examples of every rule in a pack.

```
weave rules test <pack>
```

**Arguments**

- `<pack>` — The pack folder, or 'core'.

#### `weave rules new`

Adds a rule skeleton and its two example scripts to a pack.

```
weave rules new [OPTIONS] <id>
```

**Arguments**

- `<id>` — The new rule's id, such as ACME-001.

**Options**

- `--pack=<pack>` — The pack folder. Default: here.

#### `weave rules add`

Copies a rule pack into this project's review-rules/ and pins its version and hash in review.properties.

Nothing is downloaded.

```
weave rules add [OPTIONS] <source>
```

**Arguments**

- `<source>` — The pack folder to copy in.

**Options**

- `--dir=<dir>` — The project folder. Default: here.

#### `weave rules diff`

Compares two versions of a rule pack: rules added, removed and changed, the version bump the change needs, and (with --on) the findings that would appear or disappear on a script.

```
weave rules diff [OPTIONS] <from> <to>
```

**Arguments**

- `<from>` — The older pack folder, or 'core'.
- `<to>` — The newer pack folder, or 'core'.

**Options**

- `--markdown` — Print a changelog entry.
- `--on=<script>` — A script to review with both versions.

#### `weave rules docs`

Writes the rule reference (one page per rule and an index) generated from a pack and the built-in rules.

```
weave rules docs [OPTIONS] <pack>
```

**Arguments**

- `<pack>` — The pack folder, or 'core'.

**Options**

- `--out=<out>` — The folder to write to. Default: docs/review/rules.

#### `weave rules explain`

Shows, for one rule at one place, every condition and the value it saw, so you can see why the rule did or did not fire.

```
weave rules explain [OPTIONS] <rule>
```

**Arguments**

- `<rule>` — The rule id.

**Options**

- `--at=<file:line>` — Where to look, such as main.loom:42.

### `weave graph`

Draws the workflows of a script and its imports as a graph, as JSON (default) or Mermaid, without running anything.

```
weave graph [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--format=<format>` — json (default) or mermaid.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).
- `--run=<runs>` — A run directory to draw on the graph: how often each statement ran, the edges taken, what was chosen, and (with several runs) the statements none reached (repeatable).
- `--workflow=<workflow>` — Only this workflow.

### `weave symbols`

Lists the definitions, uses and variable types of a script and its imports as JSON, without running anything.

```
weave symbols [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--format=<format>` — json (the only format). (default: json)

### `weave explain`

Describes a script in plain English (which agent is asked what, where a person is asked, what can spend money) without running anything or calling a model.

```
weave explain [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `-l, --loot <lootFile>` — Accepted so the same options work for check, audit, eval and explain; explain describes the script and does not load your tools.
- `--workflow=<workflow>` — Only this workflow.

### `weave next`

Looks at this project and says what to do next, in order, with the command for each step.

Free: it calls no model and reads no key.

```
weave next [OPTIONS] [<folder or script>]
```

**Arguments**

- `<folder or script>` — The project (default: here).

**Options**

- `--format=<text|json>` — The steps as text (default) or as JSON, for a coding agent or the editor.

**Examples**

```
weave next            what to do next in this folder
weave next support/   for another project
```

**See also**

weave man doctor, weave man gate

### `weave eval`

Runs a script's golden dataset (eval/golden/*.yaml) against it and reports passed, failed and unjudged.

--mock costs nothing; --check validates the dataset without calling a model; --init creates one.

```
weave eval [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--agent=<agent>` — Only this agent's scenarios.
- `--allow-version-change=<why>` — With --baseline: compare with a baseline taken on a different dataset anyway; the reason is recorded in the report.
- `--approver=<name>` — With --save-baseline: who approves this baseline.
- `--baseline` — Compare this run with the saved baselines (the current one and the one before it) and list the cases that regressed. Refused when the dataset differs, unless --allow-version-change is given.
- `--check` — Only validate the dataset: names, fields, dimensions, fixtures. No model is called.
- `--classes=<dir|jar>` — Folder or jar with the classes of your own tasks and tools, repeatable (weave only sees what is on its class path). Default: target/classes of the project, when it has been built (mvn compile).
- `--context-windows=<file>` — A windows file: 'model = window / reserve' per line (the size of each model's context window). Repeatable; a later file wins. Layered over <script>.windows, ~/.llm4j/context-windows and the built-in catalogue. (repeatable)
- `--dataset=<dir>` — The folder of dataset files (default: eval/golden beside the script).
- `--env-file=<file>` — Read keys from this file (NAME=value lines) instead of the .env beside the script (or at the project root). For development: keep it out of git.
- `--init` — Create eval/golden with one starter scenario per agent and one for the workflow. Never overwrites a file.
- `--json=<file>` — Also write the results as JSON.
- `--judge-samples=<n>` — Judge each rubric line this many times and use the median (default 1, at most 9). Samples that disagree widely leave the line unjudged. Each sample is a paid call.
- `--judge=<model>` — The model that judges rubric and expect lines (default: the agent's own model).
- `-l, --loot <lootFile>` — The .loot tool mapping file, for a script that uses your own tools (a real run calls them; --mock does not).
- `--loct=<file>` — A .loct file mapping a name to a class of your own compaction strategy ('keep_decisions = com.acme.KeepDecisions'), for use as 'compact: keep_decisions' in a script. Repeatable. (repeatable)
- `--max-calls=<maxCalls>` — Cap the model calls of a real run.
- `--max-cost=<maxCost>` — Cap the cost of a real run, e.g. 0.50 (needs --prices).
- `--max-tokens=<maxTokens>` — Cap the tokens of a real run (default 500000).
- `--mock` — Run on a model that costs nothing and answers every step with a fixed, well-formed reply: it checks the wiring, not the quality.
- `--no-env-file` — Do not read a .env file, even if one is beside the script.
- `--prices=<prices>` — Price table: lines of 'model = input / output' per million tokens.
- `--prompt=<id@vN>` — Run this version of a prompt for this run only, e.g. --prompt researcher@v2 (repeatable).
- `--prompts=<dir>` — The folder of prompt files (default: the script's prompts: line, else a prompts/ folder beside the script).
- `--release-gate` — This run is the release gate: held-out cases (split:heldout) run too, and the report records that it was a gate run. Without it held-out cases never run.
- `--repetitions=<n>` — Run each case this many times (default 1, at most 10): the report then gives pass@1, pass^N, consistency and the unstable cases. Every repetition is a paid run.
- `--report=<file>` — Also write the results as a single HTML page.
- `--save-baseline` — After a finished real run, save its results as a new baseline. Needs --approver and --why; a baseline is never replaced.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.
- `--split=<development|validation|heldout>` — Run only the cases tagged split:<name> (a case with no split tag is development). heldout needs --release-gate.
- `--verbose` — Show what each agent is doing as it runs (by default only the result is shown).
- `--why=<text>` — With --save-baseline: why this run is the new baseline.
- `--workflow=<workflow>` — Only this workflow's scenarios (and which workflow workflow.yaml is for).
- `-y, --yes` — Do not ask before a real run.

**Examples**

```
weave eval main.loom --init          create eval/golden/ with a starter dataset
weave eval main.loom --check         validate the dataset; calls no model
weave eval main.loom --mock          run it with mock models; costs nothing
weave eval main.loom --max-cost 0.50 --prices prices.txt     against real models, capped
```

**More options**

weave eval main.loom --repetitions 5              run every case 5 times: pass@1, pass^N, consistency, unstable cases
weave eval main.loom --judge-samples 3            judge each rubric line 3 times; wide disagreement leaves it unjudged
weave eval main.loom --release-gate               also run the held-out cases (split:heldout); recorded in the report
weave eval main.loom --save-baseline --approver ana --why "release 6.2"
weave eval main.loom --baseline                   compare with the current and the previous baseline

**The report**

Nine sections, always, in the terminal, in --report and in --json: Read this first, Overall, By agent, By dimension, By slice, Cases, Cost, Cautions, How to reproduce. By agent has a row for every agent that took part, with no tag. Cases shows each input and output. A dashboard bundle is written too (target/eval4j/runs/<id>/ or eval-runs/runs/<id>/).

**Exit status**

0 nothing failed. 1 something failed (an empty output is an error case). 2 a dataset or option problem.

**See also**

weave man test, weave man capture

### `weave test`

Runs test cases against a workflow with scripted models and no keys: routes, calls and results are checked, nothing is spent.

```
weave test [OPTIONS] [<script>]
```

Test files (tests/*.test.yaml) hold cases: an input, what each agent, classifier, tool or person answers, failures to inject, and what must hold (the result, the route, the calls, what was asked of a person). Models and tools are always scripted, so a run takes milliseconds, costs nothing and is the same every time. A case with no script for a call fails rather than getting a default answer.

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--case=<name|glob>` — Run the cases whose name matches.
- `--check` — Validate the test files only; run nothing.
- `--coverage` — Print the nodes no case reached, with a hint for a case that would.
- `--exclude-tag=<tag>` — Skip cases with this tag. Repeatable. (repeatable)
- `--fail-fast` — Stop at the first failing case.
- `--format=<format>` — text (default), json, junit or tap.
- `--ignore-observed` — Report, but do not fail on, captured cases that are still 'observed' (only approved cases gate; weave gate uses this).
- `--lenient` — Let an unscripted model call answer neutrally (it is reported).
- `--min-coverage=<percent>` — Exit 1 when node coverage is below this percent.
- `--parallel=<n>` — Run this many cases at once (default: the number of processors).
- `--require-approved` — Fail when a captured case is still 'observed' (for CI: nothing unreviewed is merged).
- `--seed=<n>` — The seed of --shuffle, to repeat an order.
- `--show-case=<name>` — Print the merged case.
- `--shuffle` — Run the cases in a random order, to show they do not depend on it.
- `--steps` — List the steps a test can name, then exit.
- `--tag=<tag>` — Run only cases with this tag. Repeatable. (repeatable)
- `--tests=<file|dir>` — Test files (default: tests/ beside the script, *.test.yaml). Repeatable. (repeatable)
- `--watch` — Run again whenever the script, its prompts or the tests change.

**Examples**

```
weave test main.loom                         run every case beside the script
weave test main.loom --coverage              also list the steps no case reaches
weave test main.loom --min-coverage 80       exit 1 below 80 % of the steps
weave test main.loom --steps                 list the step names a case may use
weave test main.loom --case "refund*" --watch
weave test main.loom --format junit          for CI
```

**Exit status**

0 all passed. 1 a case failed (or coverage is below --min-coverage). 2 it could not run (a test file or the script is unsound).

**See also**

weave man capture (a real run as a case), weave man eval, weave man gate

### `weave serve`

Serves check, graph and review to an editor over standard input and output (JSON-RPC with Content-Length framing).

Runs nothing, writes nothing.

```
weave serve [OPTIONS]
```

**Options**

- `--memory-mb=<memoryMb>` — Memory the kept answers may use (default 64).
- `--stdio` — Talk over standard input and output (the only mode).
- `--timeout-ms=<timeoutMillis>` — Longest a request may take (default 10000).

### `weave init`

Creates a starter project: a Maven project with the script and its prompts in src/main/resources, the golden dataset and eval4j JUnit tests under src/test, and a README.

Run 'weave init --list' to see the templates.

```
weave init [OPTIONS] [<template>] [<dir>]
```

**Arguments**

- `<template>` — pipeline, approval, classifier or empty.
- `<dir>` — Where to create it (default: a folder named after the template, here).

**Options**

- `--flat` — Create the older flat folder (main.loom, prompts/, eval/golden beside each other, no pom.xml) instead of a Maven project. Add --with-java-tests to put the Java tests in it.
- `--list` — List the templates.
- `--name=<name>` — The project's name, written into its files (default: the folder's name).
- `--with-java-tests` — With --flat: also add the pom.xml and the JUnit tests. (A Maven project always has them.) They run every golden scenario as its own JUnit test on a model that costs nothing; they do not call a real model.

**Examples**

```
weave init --list                  the templates
weave init pipeline support        a pipeline project in ./support
weave init approval refunds        a project with a person's approval step
weave init classifier triage       a project built round a classifier
```

**See also**

weave man generate (a project from a description), weave man next

### `weave guide`

Prints the guide that ships inside this jar: weave guide (list), weave guide 6, weave guide loom.

--install-skill adds the agent skill to a project.

```
weave guide [OPTIONS] [<page>]
```

**Arguments**

- `<page>` — A chapter number (1 to 14), readme, loom (the reference), llms, deploy (the deployer skill's map) or d1 to d8.

**Options**

- `--for=<forAgents>` — With --install-skill: which coding agents to set up, separated by commas: claude (the skills in .claude/skills; the default), agents (AGENTS.md, read by Codex, Copilot, Gemini, Cursor and others), copilot (.github/copilot-instructions.md), cursor (.cursor/rules/loom.mdc) or all. The instruction files keep what you wrote in them: Loom's text sits between two marker lines.
- `--force` — With --install-skill: replace the skill if it is already there.
- `--install-skill=<installTo>` — Write the skills and the guide into <dir>/.claude/skills (default: this folder): llm4j-harness-design (design the harness), llm4j-workflow-guide (build, test, secure, ship) and llm4j-loom-deployer (run it, watched).
- `--skill=<which>` — With --install-skill: harness, workflow, deployer or all (default all).

### `weave man`

Prints the reference for a command, like a man page: what it does, every option with its default, examples, exit codes and what to read next.

With no command it lists all of them by what you are doing. --all prints every page; --format markdown writes the repository's COMMAND_REFERENCE.md.

```
weave man [OPTIONS] [<command>...]
```

**Arguments**

- `<command>...` — The command, and the command under it if any (weave man observe flush).

**Options**

- `--all` — Print the page of every command.
- `--format=<format>` — text (default) or markdown.

**Examples**

```
weave man                       every command, by what you are doing
weave man generate              one page
weave man observe flush         a command under a command
weave man --all > weave.txt     everything, to read or search
weave man --all --format markdown > docs/COMMAND_REFERENCE.md
```

### `weave secrets`

Manages an encrypted secret store: create, set, list, remove, import-env, rekey.

Values are never printed or taken from arguments.

```
weave secrets <command>
```

**Commands**

- `create` — Creates an empty store.
- `set` — Stores a secret.
- `list` — Lists the names, allowed hosts and update times.
- `remove` — Removes a secret.
- `import-env` — Copies secrets into the store: from variables in the environment, or with --from from a file of NAME=value lines (a template such as .env.example is then offered to be blanked).
- `rekey` — Re-encrypts the store under a new master key (asked for at the console, or --new-key-file).

#### `weave secrets create`

Creates an empty store.

The file must not exist yet.

```
weave secrets create [OPTIONS]
```

**Options**

- `--iterations=<iterations>` — PBKDF2 iterations (default 600000).
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

#### `weave secrets set`

Stores a secret.

The value is asked for at the console (no echo), or read from standard input with --stdin.

```
weave secrets set [OPTIONS] <name>
```

**Arguments**

- `<name>` — The secret's name, e.g. GEMINI_API_KEY.

**Options**

- `--allow-host=<host>` — Only send this secret to this host (repeatable; *.example.com allowed).
- `--description=<description>` — A note shown by 'list'.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.
- `--stdin` — Read the value from standard input instead of the console.

#### `weave secrets list`

Lists the names, allowed hosts and update times.

Never the values.

```
weave secrets list [OPTIONS]
```

**Options**

- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

#### `weave secrets remove`

Removes a secret.

```
weave secrets remove [OPTIONS] <name>
```

**Arguments**

- `<name>` — The secret's name.

**Options**

- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

#### `weave secrets import-env`

Copies secrets into the store: from variables in the environment, or with --from from a file of NAME=value lines (a template such as .env.example is then offered to be blanked).

Values are never printed.

```
weave secrets import-env [OPTIONS] [<variables>...]
```

**Arguments**

- `<variables>...` — The variables to import (with --from: only these; default: every secret-named value in the file).

**Options**

- `--blank` — With --from a template file (.example, .sample, .template): blank the imported values without asking. A real .env is never changed.
- `--from=<file>` — Read NAME=value lines from this file instead of the environment.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

#### `weave secrets rekey`

Re-encrypts the store under a new master key (asked for at the console, or --new-key-file).

```
weave secrets rekey [OPTIONS]
```

**Options**

- `--new-key-file=<file>` — A file whose first line is the new master key.
- `--secrets-key-env=<VARIABLE>` — The environment variable that holds the store's master key (you choose its name).
- `--secrets-key-file=<file>` — A file whose first line is the store's master key (protect it with an ACL).
- `--secrets=<file>` — An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.

### `weave tools`

Gets the extra jars a deployment needs (the Fabric server, the inspect add-on) from Maven Central, after asking and checking their checksums.

```
weave tools <command>
```

**Commands**

- `fetch` — Downloads fabric, inspect or all for this weave's version into ~/.llm4j/tools/<version>/.

**Examples**

```
weave tools fetch fabric         get the Fabric server for this weave's version (asks first)
weave tools fetch inspect --yes  get the inspect add-on and its core without the question
weave tools fetch all --dir ./tools
```

**Notes**

Only repo1.maven.org is contacted, over https. Each file is kept only if its published SHA-256 (or, where Central offers only that, SHA-1) matches, and is written under ~/.llm4j/tools/<version>/. What is downloaded is never run. A development build is refused: build the module or install a release.

**Exit status**

0 done. 1 a download failed or its checksum did not match. 2 refused (development build, a no, or a bad option).

**See also**

weave man doctor, weave man deploy

#### `weave tools fetch`

Downloads fabric, inspect or all for this weave's version into ~/.llm4j/tools/<version>/.

Lists what it will download and asks first. Refuses a development build.

```
weave tools fetch [OPTIONS] <fabric|inspect|all>
```

**Arguments**

- `<fabric|inspect|all>` — What to fetch.

**Options**

- `--dir=<folder>` — Where to put them (default: ~/.llm4j/tools).
- `--yes` — Do not ask first.

### `weave preflight`

Sends one small probe per agent through its real prompts and checks: the key, the model, one whole answer, that the shared prompt reaches the model, sources for grounded agents, price and window.

Asks first and stays under a small cap. --mock does it with no model.

```
weave preflight [OPTIONS] <script>
```

**Arguments**

- `<script>` — The .loom script.

**Options**

- `--format=<text|json>` — The report as text (default) or JSON.
- `--input=<text>` — The probe input (default: the agent's first dataset case, else a greeting).
- `--max-cost=<amount>` — Stop when the probes have cost this much (needs --prices). Default 0.05.
- `--max-tokens=<n>` — Stop when the probes have used this many tokens (default 20000 when there are no prices).
- `--mock` — Run the same checks on models that cost nothing.
- `--prices=<file>` — Price table: lines of 'model = input / output' per million tokens.
- `-y, --yes` — Do not ask before sending the probes.

**Examples**

```
weave preflight main.loom                  list what it will send, ask, then probe each agent
weave preflight main.loom --yes --max-cost 0.05 --prices prices.txt
weave preflight main.loom --mock           the same checks on models that cost nothing
weave preflight main.loom --format json    for a coding agent or CI
```

**Checks**

C1 the key resolves. C2 the provider accepts the model. C3 one answer is whole (not cut, not empty). C4 the shared prompt reaches the model (a random canary placed in it, in memory only). C5 a grounded agent returns sources. C6 the price and the context window are known. C7 the run budget is larger than one answer.

**Notes**

It sends real prompts, so it asks first, stays under its own cap and stores no answer text. The result is written to .loom/preflight.json, which weave next reads.

**Exit status**

0 nothing failed. 1 a check failed. 2 refused or a bad option. 3 it stopped at its own cap.

**See also**

weave man next, weave man eval, weave man doctor

