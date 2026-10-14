# Phase 3 test strategy: Loom integration

Status: **draft for review**. Requirements (`R1`…`R18`) are in [DESIGN.md](DESIGN.md) §5; the traceability matrix in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Tests first** for each sub-phase (3a–3e), failing for the right reason.
- **No network, no keys, no real time.** Loom's existing test doubles (scripted `LLMClientFactory`, mock providers, the executor's injectable clock) stand in for models.
- **Nothing changes unless asked**: the existing Loom suites (1,735 tests) pass unmodified, and a golden trace for a script without `context` is recorded **before** any change and compared after (R2).
- **Events are checked against the numbers they summarise**: a `model_call` event's tokens equal the scripted response's; the `run_end` totals equal the sum of the events.
- Tests live beside the code in `src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/` (`parser`, `execution`, `cli`, `memory`); JUnit and AssertJ as in use; **no new test dependency**.

## 2. Test levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Parser / AST** | Every key; line numbers; a block missing keys; `chain(...)` forms; the word `context` elsewhere in a script still parses | `ContextBlockParserTest` | R1, R2 |
| **Validation** | Table of bad blocks → exact message and line (unknown strategy lists the known ones; `trigger <= target`; non-numeric; `summarize` without summariser; unknown window) | `ContextBlockCheckTest`, `WeaveCheckContextTest` | R3, R4, R8, R15 |
| **`.loct` loader** | Valid file; blank lines and comments; shadowing a built-in; missing class; wrong type; no no-arg constructor; duplicate; later file wins; `<script>.loct` found beside the script; a provider-style class gets options and a context; the loader never runs the class in `check`/`audit` | `LoctLoaderTest`, `LoctProviderOptionsTest`, `LoctCheckTest` | R19, R20, R21, R23 |
| **Custom strategy faults** | A `.loct` strategy that throws, grows the conversation, splits a pair, or loops: contained by the manager and visible in the event | `LoctFaultTest` | R22 |
| **Windows files** | Layering and precedence over temp files; later file wins; missing file is a clear error; the seeded catalogue as base | `ContextWindowsLayeringTest` | R5 |
| **Agent building** | A scripted run asserts the `ReActAgent` got the policy and window (observable through the requests sent and the `context` events); routing agent: smallest window governs; summariser built by the same factory | `ContextAgentBuildTest` | R6, R7, R9 |
| **End to end (soak)** | A script whose tool loop is far longer than a small window: completes with compaction, fails with `ContextOverflowException` without; requests never exceed the window after the first compaction | `ContextLoomSoakTest` | R12 |
| **Trace events** | A listener records every event of a scripted run; exact data per type; no text fields; events only when tracing; replayed delegates emit none | `ContextTraceEventsTest`, `ReplayNoDuplicateEventsTest` | R10, R11, R16 |
| **Statistics** | `ObservableRun.stats()` equals the events summed; `run_end` totals | `RunStatsInLoomTest` | R14 |
| **Transcript memory** | Compaction at the limit, pairs kept, latest kept, `mark`/`restore` across a compaction, rewind scenario | `TranscriptCompactionTest`, `RewindWithCompactionTest` | R13 |
| **Redaction** | The loom-inspect redaction audit run over the new event types with secrets planted in prompts and tools | extend the existing audit test | R17 |
| **Compatibility** | Golden trace/requests for a script without `context`; an old observer ignoring unknown types | `LoomBackwardCompatTest` | R2, R11 |
| **Build checks** | Dependency diff of the module `pom.xml`; public API diff | build | R18 |

## 3. Points to cover

- Percent syntax: `80%`, `80 %`, `0.8` (rejected with a hint), `120%` (rejected).
- `chain(a)` with one member equals `a`; `chain()` is an error; nested chains are an error.
- An agent whose `model` is a provider/model path (`vertex/gemini-2.5-pro`): the window lookup tolerates the prefix as `ContextWindows` does.
- The summariser model equal to the agent's model (allowed, warned: no saving).
- Events in a `parallel` block: ordering within an agent is preserved; each carries its agent name and step id.
- A tool that returns `Error: …` text appears as `tool_call` with `soft_failure=true` while the `observation` event is unchanged.
- A delegate that fails with a provider error: `model_call` with `outcome=FAILED` and the category, and the run's own failure handling unchanged.
- Reproducibility: the same script and scripted model give byte-identical event data (excluding timestamps).

## 4. What is deliberately not tested here

- Real providers (manual checks in VERIFICATION.md).
- The quality of a summary (an eval4j task, as in Phase 1).
- Interpreting events as incidents or gauges (phases 4 and 5).

## 5. Quality gates

- All existing ai-agent4j and Loom tests pass unmodified.
- Line coverage of the new Loom classes ≥ 90 %, branch ≥ 80 %; a `mutation-loom-context` PIT profile over them with survivors reviewed.
- No new runtime dependency; public API additions only.
