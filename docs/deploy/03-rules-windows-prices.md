# D3. The rules, the windows and the prices

**Goal:** what counts as trouble is written down, every model's context window is known, and cost is reported.

## Rules: what counts as trouble

A rules file beside the script, named after it (`flow.inspect` for `flow.loom`), says per agent how sensitive each signal is. Everything is optional; the defaults are sensible. Ask the user which agents are touchy and write only what they decide.

```text
Researcher { context_nearly_full: warn at 80%, high at 92% }
Editor     { same_action: 3 times }
*          { paused_longer_than: 90s
             compaction_thrash: 4 in 10 calls
             tool_success_rate: below 70% over 10 calls
             access_denied: 3 in 60s }
Support    { trifecta: accepted "customer support: reads the page, looks up the account, replies by mail; reviewed 2026-10-14" }
```

The behaviours: `same_action`, `same_output`, `tool_failures`, `ungrounded_figures`, `paused_longer_than`, `no_progress`, `context_nearly_full`, `truncated_answers`, `compaction_thrash`, `tool_success_rate`, `latency_spike`, `access_denied`, `injection_scan`, `trifecta`. Check the file against the script, free:

```
weave observe rules flow.loom
```

A Fabric keeps rules centrally too: on its **Configuration** page an admin picks an agent, a behaviour and a threshold (no syntax to type); they are laid over each process's own rules and apply at once to every run, running ones included.

## Windows: the size of each model's context window

An agent's context gauge, and the *context nearly full* incident, need to know the window of its model. It is a fact about the model, written once, outside the script: a file `flow.windows` (`model = window / reserve for the answer`), the `--context-windows` option, `~/.llm4j/context-windows`, or the catalogue that ships with the jar (the current Claude models). On a **Fabric**, the Configuration page's *Context windows* card keeps the same table for every process, so a process nobody told still gets a gauge (a window a process reports itself wins). Never guess a number: ask the user or read the provider's documentation. An agent with no known window shows a hatched bar and says so.

## Prices: so cost is reported

A step that reports tokens but no cost is costed from a price table: `model = input / output` in dollars per million tokens, in `flow.prices` beside the script, `--prices`, or `LOOM_PRICES`; on a Fabric, the Configuration page's price table. A model with no price has no cost; say so rather than showing zero as if it were free.

## Gate

`weave observe rules flow.loom` has no errors; each warning is explained; every model has a window and a price (or the user knows it does not).
