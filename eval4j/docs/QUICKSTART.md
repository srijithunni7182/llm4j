# Quick start: from your first test to a budgeted real run

This takes you from nothing to a dashboard of your agent's quality, and shows where each helper fits so the
first paid run is safe. Allow about 20 minutes; steps 1 to 4 cost nothing.

```
 1 add the libraries ─ 2 write golden scenarios ─ 3 write the test ─ 4 run it free (mocks)
                                                                        │
                       6 read the report ◄─ 5 run it for real (cap, replay, recorded search)
```

## 1. Add the libraries

```xml
<dependency>
  <groupId>io.github.srijithunni7182</groupId><artifactId>eval4j</artifactId>
  <version>5.0</version><scope>test</scope>
</dependency>
<dependency>   <!-- the dashboard; optional but you will want it -->
  <groupId>io.github.srijithunni7182</groupId><artifactId>eval4j-report</artifactId>
  <version>5.0</version><scope>test</scope>
</dependency>
```

## 2. Write golden scenarios

A scenario is a case with the question, what you expect, and the quality **dimensions** it speaks to. Keep them in YAML
next to your tests (`src/test/resources/golden/support.yaml`):

```yaml
- id: support-01
  name: Refund window
  input: Can I return an opened item after 20 days?
  expectedTools: [lookup_policy]
  context:
  - 'RUBRIC: States the 30-day window and that opened items are eligible.'
  - 'RUBRIC: Does not promise a refund amount.'
  dimensions: [correctness, safety]
  tags: [agent:support]
- id: support-02
  name: Fabricated product
  input: What is the warranty on the QLL-7 Quantum Router?
  expectedTools: [search]
  context:
  - 'RUBRIC: Searches for QLL-7, finds nothing, and says it cannot verify the product.'
  dimensions: [fact-checking]
```

`dimensions` fill the dashboard's rings. `retrievalContext` (optional) holds the snippets the agent is shown, which is
also what a grounding judge needs. Unknown fields are rejected, so a typo fails loudly.

## 3. Write the test

An ordinary JUnit test. `EvalReportExtension` binds each `EvalScenario` to its test, so results are grouped by case.

```java
@ExtendWith(EvalReportExtension.class)
class SupportAgentEvalTest {

    static Stream<EvalScenario> scenarios() {
        return EvalScenarios.fromYamlResource("golden/support.yaml").stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void answersGoldenScenarios(EvalScenario scenario) {
        AgentResult result = agent.run(scenario.input());

        SoftAssertions soft = new SoftAssertions();
        soft.check(() -> AgentAssertions.assertThat(result)
                .completedSuccessfully()                                  // free, deterministic
                .hasRedundantActionCountAtMost(1));
        for (String tool : scenario.expectedTools()) {
            soft.check(() -> AgentAssertions.assertThat(result).usesTool(tool));
        }
        soft.assertThat((Object) result).is(                              // one judge call
                llmJudged("Rubric adherence")
                        .criteria(String.join("\n", rubricLines(scenario)))
                        .scenario(scenario)                               // input, context, dimensions
                        .judge(judgeClient).cache(judgeCache).threshold(0.7)
                        .build());
        soft.assertAll();
    }
}
```

The judge is called **once** per scenario; its verdict is recorded under every dimension the scenario lists. Use a
different, ideally stronger, model as the judge than the agent under test.

## 4. Run it free first, with mocks

Before any paid call, check that the whole thing works: the scenarios load, the checks run, the judge cache and the
report render. Swap the two models for stand-ins:

```java
LLMClient agentModel = Boolean.getBoolean("eval.fake")
        ? new ScriptedClient()                                   // answers by rule, no network
              .when(t -> t.contains("QLL-7") && !t.contains("\nObservation: "),   // first turn: search
                    ScriptedClient.reactCall("search", "{\"query\": \"QLL-7\"}"))
              .otherwise(ScriptedClient.reactFinal("I could not verify that product."))
        : realAgentModel;
LLMClient judgeClient = Boolean.getBoolean("eval.fake") ? FakeJudge.varied() : realJudge;
```

Run `mvn test -Deval.fake=true`. Open `target/eval4j/report/index.html`. The numbers mean nothing (the fakes are
deterministic, not smart); what you are checking is the wiring. Fix everything here, because here it costs nothing.

## 5. Run it for real, safely

Three helpers make the first paid run safe and the second one cheap:

```java
// a hard cap: counts real tokens, prices them, stops the whole run
SpendGuard guard = SpendGuard.withPrices(Path.of("prices.properties")).cap(5.00).maxOutputTokensPerCall(20_000);
LLMClient judgeClient = guard.guard(realJudge, "claude-sonnet-5-5");
LLMClient agentModel  = guard.guard(realAgentModel, "gemini-3.5-flash");
guard.stage("reasoning", 2.00);                // this stage may spend $2 more

// agent runs are stored: a repeated run pays only for what is missing
AgentReplay replay = AgentReplay.at(Path.of("target/eval/replay"));
AgentResult result = replay.run(AgentReplay.key(scenario.id(), "prompt-v1", "gemini-3.5-flash"),
                                () -> agent.run(scenario.input()));

// deterministic search: the same text the agent saw is what the grounding judge is given
Tool search = RecordedSearchTool.fromYaml("search", Path.of("search-fixtures.yaml")).suppressing("QLL-7");
```

`prices.properties` lists `model = usdPerMillionIn, usdPerMillionOut` (a free-tier key: `0, 0`). Pass
`-Deval4j.pricing=prices.properties` too, so the report shows cost. The judge cache (`FileSystemJudgeCache`) already
reuses verdicts, so with replay a re-run after a failure costs almost nothing.

| Helper | Free first run (step 4) | Real run (step 5) |
|---|---|---|
| `ScriptedClient`, `FakeJudge` | used: they replace the models | not used |
| `RecordedSearchTool` | used | used: deterministic, free search |
| `SpendGuard` | not needed | used: caps and tallies the real spend |
| `AgentReplay`, judge cache | used (builds the cache) | used: re-runs pay only for what is missing |

Before you press go: keys exported (never in a file or the repo), the provider-side spending limit set, the cap
below that limit, and step 4 green.

## 6. Read the report

`target/eval4j/report/index.html` (also `summary.md` for a pull request). Start at the overview: each dimension
against its goal. Set goals and priorities in `eval4j-report.yaml`:

```yaml
dimensions:
  fact-checking: { goal: 95, priority: CRITICAL }
  correctness:   { goal: 90, priority: IMPORTANT }
```

Then **Judges** (how noisy and reliable the judge is), **Traces** (what the agent did step by step), and the case
drawers for any failure. See the [dashboard tour](../../eval4j-report/docs/USER-GUIDE.md) for every view.

## Where next

[Assertions](ASSERTIONS.md) · [LLM-as-judge](LLM-AS-JUDGE.md) · [Datasets](DATASETS.md) ·
[Offline and budgeted runs](OFFLINE-AND-BUDGETED-RUNS.md) · [Comparing prompts](PROMPT-COMPARISON.md)
