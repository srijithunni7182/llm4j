# Reports, baselines & regression gates

Turn judged scores into JSON/HTML reports, a trend history and a CI gate that fails when quality drops.

[← eval4j README](../README.md) · [All docs](README.md)

---

## Reporting — `EvalReportExtension`

```java
@ExtendWith(EvalReportExtension.class)
class MyAgentEvalTest {
    // ...
}
```

Prints a pass/fail summary (with judge reasons for failures) in `afterAll`, observing whatever
`@Test`/`@ParameterizedTest` methods in the class throw — eval4j's assertions or plain
JUnit/AssertJ ones. It's a standard JUnit 5 `TestWatcher`/`AfterAllCallback` extension, not a
bespoke "runner" you call explicitly.

## Reports, baselines and regression gates

```java
@ExtendWith(EvalReportExtension.class)
@EvalBaseline(file = "eval4j-baseline.json", maxRegression = 0.05)
class AgentEvalTest { ... }
```

- Run with `-Deval4j.report.dir=target/eval4j` to get `eval4j-report.json` and a self-contained
  `eval4j-report.html` (no external requests) once per run, plus a score history
  (`eval4j-history.jsonl`, or `-Deval4j.history.file=...`) for trend charts.
- Create/refresh the baseline with `-Deval4j.baseline.update=true` (never written otherwise). After that,
  the class fails if any metric's **suite average** drops by more than `maxRegression`
  (`granularity = CASE` compares each test individually, but judge scores are noisy — prefer `SUITE`
  and `samples(3)` on gated metrics).
- CI recipe: cache the history file, commit the baseline, run the tests.
