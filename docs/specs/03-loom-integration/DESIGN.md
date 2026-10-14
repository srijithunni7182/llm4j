# Phase 3 design: Loom integration

Status: **draft for review; decisions to confirm in §9**. Layer: Loom (`ai-agent4j-loom`). Loom depends on ai-agent4j; ai-agent4j knows nothing of Loom.
Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md). Builds on
[Phase 1](../01-context-management/DESIGN.md) (context and compaction) and [Phase 2](../02-call-telemetry/DESIGN.md) (call telemetry).

## 1. Problem

ai-agent4j agents can now keep their prompt inside the model's window and report every model and tool call. A Loom workflow gets none of it:

- Loom builds each agent in `HarnessExecutor` without a context policy, so a long tool loop still dies of a "prompt too long" error.
- Loom's `TraceEvent` stream has `thought`, `action`, `observation`, `budget` and so on, but nothing about context fill, compaction, model-call latency or a
  tool's real outcome. `TraceMapper` in loom-inspect still decides a tool "failed" by looking for the word "error" in the observation text.
- Loom's own memory (`TranscriptAccumulationEngine`) appends every task and answer to a transcript that is pasted in front of the next task, without limit.

## 2. Goals and non-goals

**Goals**

- G1. A script says **how an agent compacts** (the strategy and its knobs); it does not say how big the model's window is.
- G2. The **window of each model is configured outside the script**, once, for the whole deployment: a file in the existing `ContextWindows` format given to the
  CLI or the host, layered over the seeded catalogue. Changing a model's window never edits a script.
- G3. The executor builds each agent with the resulting `ContextPolicy` and window, so compaction and overflow recovery work in Loom runs, in both loops.
- G4. The trace carries new events for what the core now reports: context use, compaction, model calls, and tool calls with their real outcome and category.
- G5. Loom's transcript memory stops growing without limit when a policy is configured: it is compacted by the same strategies.
- G6. Everything is opt-in: a script with no `context` block and a deployment with no windows file behave exactly as before (golden traces unchanged).
- G7. `weave check` and `weave audit` understand the new block (unknown strategy, bad numbers, a model with no known window when compaction is on).

**Non-goals**

- Mapping to OpenTelemetry records and incidents (phase 4) and Fabric (phase 5). This phase produces the events; it does not interpret them.
- A window setting in the script, or a per-agent window override (decision 1).
- New compaction strategies (Phase 1 has them; a host can still register its own through `ServiceLoader`).
- Changing how budgets are enforced. Budgets stay as they are; this phase only adds context awareness beside them.

## 3. What a Loom author and a deployer write

### 3.1 In the script: the `context` block on an agent

It follows the existing settings blocks (`guard { … }`, `memory { … }`):

```
agent Researcher {
    model: "claude-sonnet-5-5"
    tools: [search, fetch]
    context {
        compact: chain(trim_tool_results, summarize, drop_oldest)   // a strategy, or a chain of strategies, by name
        summarizer: "claude-haiku-5-5"                               // the cheaper model that writes notes (for summarize)
        trigger: 80%                                                 // start compacting above this share of the usable window
        target: 55%                                                  // compact down to this share
        keep_recent: 6                                               // messages never touched
        max_compactions: 20                                          // per run, a safety stop
    }
}
```

All keys are optional; omitted keys take ai-agent4j's defaults (`ContextPolicy`). `compact: none` (or no block) switches compaction off for that agent.
A bare `context { compact: drop_oldest }` is a complete, useful declaration.

A strategy name is resolved with `ContextCompactors.byName` (so a host can add its own through `ServiceLoader`). A chain is written `chain(a, b, c)`.
Strategy options that have a key (`summarize` takes `max_summary_tokens`) are written as further keys of the block, not inside the chain.

### 3.2 Outside the script: the windows file

A file in the format ai-agent4j already reads (`ContextWindows.load`), one model per line:

```
claude-sonnet-5-5 = 1000000 / 128000      # window / reserve for the answer
my-finetune        = 32768 / 4096
```

Given to the CLI with `--context-windows <file>` (repeatable; later files win), to a host in code with `HarnessExecutor.setContextWindows(...)`, and by default
looked up beside the script as `<script>.windows` or in `~/.llm4j/context-windows` when present. Precedence, highest first: files named on the command line,
the file beside the script, the user file, the seeded catalogue. A model in none of them has **no known window**: compaction cannot trigger by fill, only
reactively (after a provider's overflow error), and `weave check` warns once per such agent (decision 3).

## 4. Behaviour

### 4.1 Building the agent

For each agent with a `context` block, `HarnessExecutor` resolves the agent's `model` (the name the provider will answer to; the routing client's candidates
are all resolved) against the windows, builds a `ContextPolicy` from the block and passes both to `ReActAgent.Builder`. The summariser, when named, is built
by the same `LLMClientFactory` as the agent's own client, so its credentials, rate limits and budgets are those of the script. An agent without a block is
built exactly as today.

### 4.2 Trace events

New constants in `TraceEvent`, each with numbers and names only (no prompt or result text), produced by a new listener adapter beside `traceAdapter`:

| type | when | `data` |
|---|---|---|
| `context` | after each model call, when a window is known | `used`, `window`, `usable`, `fraction`, `source` (`REPORTED` or `ESTIMATED`) |
| `compaction` | when a compaction ran, was skipped, or failed | `strategy`, `outcome`, `before`, `after`, `messages_before`, `messages_after`, `cost`, `reason` |
| `model_call` | after each model call | `model`, `requested_model`, `duration_ms`, `waited_ms`, `attempts`, `failovers`, `prompt_tokens`, `completion_tokens`, `finish_reason`, `truncated`, `outcome`, `category` |
| `tool_call` | after each tool call | `tool`, `outcome`, `duration_ms`, `approval_ms`, `result_chars`, `soft_failure`, `category` |

`TraceEvent.text` is a one-line human summary built from the numbers. The existing `action` and `observation` events are unchanged, so a consumer that does not
know the new types is unaffected. `trace()` is the only writer (so the journal, console and observers receive them through the existing `TraceListener` path).

### 4.3 Run statistics

`HarnessExecutor` keeps one `StatsCollector` for the run (added to every agent) and exposes the snapshot through `ObservableRun` (`stats()`), so phase 4 can
read it without touching agents. The final `run_end` event carries the totals (`model_calls`, `tool_calls`, `tool_failures`, `retries`, `failovers`, `compactions`).

### 4.4 Loom's transcript memory

`TranscriptAccumulationEngine` gains an optional `ContextCompactor` and a size limit in estimated tokens. When the transcript would exceed the limit, the
compactor is applied to the transcript (as a list of messages: one per task and one per answer), keeping the most recent entries. The limit is set by the
script's own `memory { … }` block as `max_tokens`, and the strategy by `compact`, **or**, when a `memory` block is absent, by the agent's `context` block.
`mark()` and `restore()` (used by `rewind`) keep working on the compacted form.

### 4.5 Checks

`weave check`: unknown strategy name, a chain with an unknown member, `trigger <= target`, non-positive numbers, `summarizer` naming a model that is not a
declared provider model, `compact: summarize` without a summariser (warning: falls back to `drop_oldest`), a model with no known window (warning).
`weave audit` lists, per agent, its strategy and its window source (file, catalogue, none). Both only read; neither calls a model.

## 5. Requirements (testable)

| Id | Requirement |
|---|---|
| R1 | The parser accepts a `context { … }` block on an agent, with every key in §3.1, and records the line of the block and of each key |
| R2 | A script without a `context` block parses and executes exactly as before; golden traces and requests are unchanged |
| R3 | `compact` accepts a strategy name or `chain(a, b, …)`; names are resolved by `ContextCompactors.byName`; an unknown name is a check error naming the agent, the line and the known names |
| R4 | `trigger`/`target` accept percentages and are validated by `ContextPolicy` (target below trigger); invalid values are check errors with the line |
| R5 | The windows file is read with `ContextWindows.load`; files are layered with the precedence in §3.2; the seeded catalogue is the base |
| R6 | `HarnessExecutor` gives each agent with a block its policy and the window of its model; an agent without a block gets neither |
| R7 | For a routing agent every candidate model's window is resolved; the smallest governs (a conversation must fit whichever answers) |
| R8 | A model with no known window: no fill-based compaction; reactive recovery still works; a check warning is produced once per agent |
| R9 | The summariser client is built by the same factory as the agent's client and shares its credentials; a missing summariser model falls back as in Phase 1 |
| R10 | `context`, `compaction`, `model_call`, `tool_call` trace events are emitted with the data in §4.2 and no prompt, answer, argument or result text |
| R11 | Trace events are produced only when something listens (`tracing()`), as today; agents' statistics are kept regardless |
| R12 | A run in a Loom script with a tool loop longer than the window completes with compaction on, and fails as before with it off (the soak scenario) |
| R13 | The transcript memory compacts when over its limit, keeps the latest entries, never splits a task from its answer, and `rewind` restores a mark taken before compaction |
| R14 | `ObservableRun.stats()` returns the run's `RunStats`; `run_end` carries the totals |
| R15 | `weave check` and `weave audit` report per §4.5 and never call a model |
| R16 | A resumed run (journal replay) does not re-emit events for replayed delegates and does not double-count statistics |
| R17 | Secrets never appear in the new events (the redaction audit of loom-inspect passes with the new types included) |
| R18 | No new runtime dependency in `ai-agent4j-loom` |

## 6. Sub-phases

Each ends with its tests passing and its documents updated (see [the rule](../README.md#documents-to-update-at-the-end-of-every-sub-phase)); each is a commit.

| Step | Delivers | Requirements |
|---|---|---|
| 3a | Parser and AST for `context`; `ScriptValidator` checks; `weave check` output | R1, R2, R3, R4, R15 (check part) |
| 3b | Windows files and their layering; CLI option; building the agent with policy and window; summariser client | R5, R6, R7, R8, R9, R12 |
| 3c | Trace events, the listener adapter, run statistics on `ObservableRun`, `run_end` totals | R10, R11, R14, R16, R17 |
| 3d | Transcript memory compaction and `rewind` | R13 |
| 3e | `weave audit`, Loom guide and reference pages, VS Code grammar and hover for `context`, a runnable example, the verification run | R15, R18, all |

## 7. Compatibility

- Language: `context` is a new key inside an agent block; scripts without it parse as before. The keyword list at `LoomParser` gains `context`, so a variable or
  agent *named* `context` would clash: the parser accepts it as a settings block only directly inside an agent, and a test pins that existing scripts that use
  the word elsewhere still parse.
- Trace: new event types only; consumers ignore unknown types (loom-inspect's mapper already does).
- Journals: nothing new is written to the run journal; replay is unaffected.

## 8. Risks

| Risk | Mitigation |
|---|---|
| A window the deployer got wrong silently over- or under-compacts | `weave audit` shows the window and its source; the `context` event shows `window` on every call |
| The routing agent's candidate windows differ widely | The smallest governs (R7) and the audit says so |
| The summariser doubles the spend | It uses the script's budgets; its cost is in the `compaction` event and in `AgentResult.Usage`; `weave check` warns when `summarize` is chosen without a budget on the agent |
| Compacting the transcript loses a fact a later agent needs | Default strategies keep the recent entries and a marked summary; the pinned marker from Phase 1 is honoured; documented in the guide |
| Event volume in long runs | One `context` and one `model_call` event per model call, small; consumers can filter by type |

## 9. Decisions to confirm

| # | Question | Recommendation |
|---|---|---|
| 1 | Window in the script too (an override per agent)? | **No.** As you said: the window belongs to the model and is configured outside; only the strategy is in the script |
| 2 | Where is the outside file found? | `--context-windows` (repeatable), then `<script>.windows`, then `~/.llm4j/context-windows`, then the seeded catalogue; same format as `ContextWindows.load` |
| 3 | A model with no known window and compaction on | Warn at check time; at run time compact reactively only. Not an error, because a new model should not block a run |
| 4 | Syntax | A `context { … }` settings block in the agent, matching `guard`/`memory`/`voice`; `chain(a, b)` for several strategies |
| 5 | Does the transcript memory share the agent's strategy? | Yes by default; a `memory { max_tokens, compact }` can override |
| 6 | Statistics on `ObservableRun` now, interpretation later | Yes: this phase exposes `stats()` and the events; incidents and gauges are phases 4 and 5 |
