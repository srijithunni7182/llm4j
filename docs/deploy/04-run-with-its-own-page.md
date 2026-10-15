# D4. Launch the application with its own page

**Goal:** the workflow runs, and the user sees it on a page served by the run itself. This is the quickest way to see what observing gives, with nothing to install on a server.

## Do

From the project folder, with this skill's launcher (it puts the add-on on the class path for you):

```
scripts/run-observed.sh --script src/main/resources/main.loom --embedded 8787 --linger 5m -- --max-cost 0.50 --prices prices.properties
```

(Windows: `scripts\run-observed.ps1 -Script ... -Embedded -Port 8787 -Linger 5m -More '--max-cost','0.50'`.) What it runs is `weave run <script> --observe=8787 --observe-linger 5m ...`: the page is at `http://127.0.0.1:8787` while the run lasts and for five minutes after. A run that costs money keeps the caps from [chapter 9 of the workflow guide](https://github.com/srijithunni7182/llm4j/blob/main/docs/guide/09-go-live.md): ask the user for the cap and pass it after the `--`.

When the user runs the application themselves, the line to give them is `weave run flow.loom --observe`. The add-on's two jars (`loom-inspect` and `loom-inspect-core`) must be on weave's class path; without them `--observe` says so and nothing runs.

For an application written in Java that embeds Loom, the same add-on attaches to the executor: `Observe.open(...)` and `session.attach(executor.observable(), scriptFile)` before `initialize()` (the workflow guide's Java reference has the method).

## Check

- The console prints the page's address. Open it (`http://127.0.0.1:8787`); the run appears the moment it begins.
- The page is **read-only** until an admin password is set (chapter 2, section 6).
- If the page does not open: is the port taken (`--embedded 8790`)? did the run already end with no `--linger`?

## Tell the user what they are looking at

Walk the page top to bottom (chapter 7 has the details): the header (status, Live/Paused), the cards (tokens, cost, context, tool calls, tasks), the workflow graph with each step's state, the incidents, the agents, and the live trace. Open the **Context**, **Tools & tasks** and **Security** views of the run. Do not skip this: a page nobody has been shown is a page nobody reads.

## Gate

The page opened, showed the run, and the user can say what each card means.
