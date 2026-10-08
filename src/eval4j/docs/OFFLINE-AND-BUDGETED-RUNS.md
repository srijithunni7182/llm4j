# Offline and budgeted runs

See the [quick start](QUICKSTART.md) for where these fit in the workflow.

Evaluating an agent means calling real models many times. These helpers (package `io.github.llm4j.eval.testing`)
let you build and check the whole evaluation for free first, then run it for real with a hard cap on spend.

| You want | Use |
|---|---|
| Stop a run at a dollar cap, or when one stage costs more than expected | `SpendGuard` |
| Exercise your agent's control flow without calling a model | `ScriptedClient` |
| Exercise judged checks, the cache and the report without calling a judge | `FakeJudge` |
| A repeated or resumed run to pay only for what is missing | `AgentReplay` |
| Search results that are deterministic, free and the same text the grounding judge sees | `RecordedSearchTool` |

## Cap the spend

```java
SpendGuard guard = SpendGuard.withPrices(Path.of("prices.properties"))   // model = usdPerMillionIn, usdPerMillionOut
        .cap(5.00)                                                       // stop the whole run here
        .maxOutputTokensPerCall(20_000);                                 // a runaway generation stops it too
LLMClient judge = guard.guard(realJudge, "claude-sonnet-5-5");
LLMClient agentModel = guard.guard(realAgentModel, "gemini-3.5-flash");

guard.stage("reasoning", 2.00);   // this stage may spend $2 more; passing that stops the run
```

Once stopped, every guarded call fails with `SpendGuard.SpendStopped`, so a loop or a retry storm cannot keep
spending. A model with a price of `0, 0` (a free-tier key) costs nothing against the cap.

## Test the flow for free

```java
LLMClient model = new ScriptedClient()
        .whenSeen("Round 1", ScriptedClient.reactFinal("It does not exist."))
        .otherwise(ScriptedClient.reactFinal("ok"));

LlmJudgeCondition.llmJudged("Correctness").criteria("...").judge(FakeJudge.varied()).build();
```

`FakeJudge` verdicts are deterministic and meaningless; use them to check wiring, never to read results.

## Pay once for the same case

```java
AgentReplay replay = AgentReplay.at(Path.of("target/eval/replay"));
String key = AgentReplay.key(scenario.id(), "prompt-v1", "gemini-3.5-flash", fixtureText);
AgentResult result = replay.run(key, () -> agent.run(task));   // runs once; later runs read it back, steps included
```

A `CURRENT TIME: ...` line is ignored in keys, because personas that embed the clock would otherwise never hit the store.

## Deterministic search

```java
Tool search = RecordedSearchTool.fromYaml("WebSearch", Path.of("search-fixtures.yaml"))
        .suppressing("QLL-7");                                    // a fabricated term must find nothing
Tool oneCase = RecordedSearchTool.fixed("WebSearch", scenario.retrievalContext());
```

The YAML library is a list of `id`, `match` (a case-insensitive regex over the query) and `snippets`. A query that
matches nothing returns `No results found.`
