# 5. Test each agent, with spend caps

**Goal:** every agent meets its goals on the golden dataset, you know how noisy your judge is, and the run cost what you expected.

## Why

The workflow in chapter 6 is only as good as its agents. Testing them one at a time, cheaply, tells you which agent to fix; testing the
whole debate tells you only that something is wrong.

## The order that keeps money safe

1. **Free first, on mocks.** Swap the agent model for `ScriptedClient` and the judge for `FakeJudge`, run the whole suite, and open the report.
   You are checking wiring, not quality. Fix everything here: it costs nothing. (Hexamind's `eval/run-all.sh --fake` runs 94 tests and renders the
   full report in about 17 seconds.)
2. **One real case (smoke test).** Run a single scenario end to end for about a cent. It proves keys, tools, judge and report work, and it
   *measures* tokens per call so you can check your cost model before the big run.
3. **The full run, under a cap.** Put every real client behind `SpendGuard`, run with `AgentReplay` and a judge cache, and give each stage a ceiling.

```java
SpendGuard guard = SpendGuard.withPrices(Path.of("prices.properties")).cap(5.00).maxOutputTokensPerCall(20_000);
LLMClient judge  = guard.guard(realJudge, "claude-sonnet-5-5");
LLMClient model  = guard.guard(realAgentModel, "gemini-3.5-flash");
guard.stage("agent reasoning", 2.00);                           // passing this stops the whole run

AgentResult result = AgentReplay.at(Path.of("target/eval/replay"))
        .run(AgentReplay.key(scenario.id(), "agent_analyze:v1", "gemini-3.5-flash", fixtureText),
             () -> agent.run(task));
```

## What to assert per case

```java
SoftAssertions soft = new SoftAssertions();
soft.check(() -> AgentAssertions.assertThat(result)
        .completedSuccessfully().hasRedundantActionCountAtMost(1).completesWithinIterations(8));   // free
for (String tool : scenario.expectedTools())
    soft.check(() -> AgentAssertions.assertThat(result).usesTool(tool));                           // free
soft.check(() -> AgentAssertions.assertThat(result)
        .usesToolWithArgumentContaining("WebSearch", "query", "QLL-7"));                           // searched for the right thing
soft.assertThat((Object) result).is(llmJudged("Rubric adherence")
        .criteria(rubric).scenario(scenario).judge(judge).cache(cache).threshold(0.7).build());   // one judge call
soft.assertAll();
```

Deterministic checks run first and are free; the judge is the only paid call per case.

## Know your judge

Run a **calibration** pass: judge a sample of cases several times (`samples(3)`, a separate cache and judge id) so the report's
Judges page shows how much the judge disagrees with itself. Read every other number with that noise in mind.

## Cost: model it, then measure it

Keep a small cost model (calls x tokens x price) and compare it with the smoke test before the big run; Hexamind's is
[`cost_model.py`](https://github.com/srijithunni7182/llm4j/blob/main/examples/hexamind-hub/eval/cost_model.py). Measured on the real run: a smoke case cost about $0.004, and one
judge call about $0.005. A stage ceiling that is too tight fails the stages after it, so set ceilings from measured costs, with margin.
A free-tier model key costs nothing; price it at `0, 0` so the cap counts only what you really pay.

## Gate

Each agent meets its dimension goals (set in `eval4j-report.yaml`), the judge's noise is known, and spend landed near the model. Failures are
findings: read them in the report's case drawers, fix the prompt or tool, re-run (replay and cache make the re-run cheap).
