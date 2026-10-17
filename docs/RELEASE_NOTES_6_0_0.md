# Release notes: 6.0.0

6.0.0 is the first release since 5.0 (23 May 2026). It is a major version because the stack has grown a great deal and carries one deliberate behaviour change
(see "Upgrading"). Everything else is additive; the API policy is in [API_COMPATIBILITY.md](API_COMPATIBILITY.md).

## New artifacts (`io.github.srijithunni7182`)

| Artifact | What it is |
|---|---|
| `ai-agent4j-tools` | Built-in and generic tools: webhook, email, HTTP, file, shell and SQL, each with guards |
| `eval4j`, `eval4j-report` | Evaluation and testing for agents in plain JUnit, trajectory tests, and the local report dashboard |
| `loom-inspect-core`, `loom-inspect`, `loom-fabric` | Live observability for Loom runs: the run page, incidents and controls, and the Fabric server that collects several applications |
| `llm4j-spring-boot-starter` | Spring Boot auto-configuration for clients, budgets, agents, tools, RAG and the Loom runtime. Nothing in llm4j depends on it |

`llm4j-parent`, `ai-agent4j`, `ai-agent4j-addons` and `ai-agent4j-loom` keep their names.

## Highlights since 5.0

- **Loom:** checkpoints, rewind and fork; ladders (earned autonomy as a written policy); `weave audit` and its security guide; deterministic journaled `Task` steps;
  a graph view; the onboarding kit (`weave init`, `guide`, `eval`, `explain`, `next`); steering, pausing and cancelling a running workflow; context windows and compaction;
  supervise rules; invoker credentials (tools that act as the person running the workflow); `halt`, `set` and `return` statements.
- **`LoomHost`** (`io.github.llm4j.loom.host`): runs workflows from an application, suspends at a question, resumes from a file or JDBC store after a restart, schedules
  and wakes runs, and shuts down gracefully. Usable without any framework.
- **Spring Boot starter:** configure it from `application.yml`; every bean backs off to one of your own; nothing starts by itself. See
  [the developer guide](SPRING_BOOT_LOOM_GUIDE.md).
- **ai-agent4j:** native tool calling for Gemini and Claude, a secret store, cost budgets and prices, run hooks, run controls (`RunHandle`), context compaction.
- **Licence:** relicensed from MIT to Apache License 2.0.

## Upgrading from 5.0

1. Change every `io.github.srijithunni7182` coordinate from `5.0` to `6.0.0`.
2. **Loom now checks an agent's answer against its `output_schema` / `expecting`.** Before, an answer that was not JSON (or did not match) reached the script as raw
   text; now the step is retried once with a correction, then fails into `on_failure`. A script that relied on the raw text must handle the failure or drop the schema.
3. The repository's folder layout changed (`src/loom/...`, `src/spring/...`); published coordinates did not.
4. Scripts that do not use the new statements and settings behave as before.

Run `weave check` and `weave audit` over your scripts after upgrading.

## Publishing

How a release is made: [PUBLISHING.md](PUBLISHING.md).
