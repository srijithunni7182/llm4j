# Golden datasets & synthesis

Author eval scenarios as data (YAML) — by hand or generated from your documents — and feed them through ordinary JUnit parameterized tests.

[← eval4j README](../README.md) · [All docs](README.md)

---

## Golden datasets — `EvalScenario` / `EvalScenarios`

Hardcoding every case in Java doesn't scale, and it locks non-engineers out of authoring or
reviewing eval scenarios. `EvalScenario` is a plain data record (`name`, `input`,
`expectedOutputContains`, `expectedOutput`, `expectedTools`, `context`, `retrievalContext`) loaded
from YAML — but loading data is *all* eval4j does here; you still write the actual assertion chain
by hand in a normal `@ParameterizedTest`:

```yaml
# src/test/resources/scenarios.yaml
- name: percentage-calculation
  input: "What is 15% of 240?"
  expectedOutputContains: "36"
  expectedTools: [calculator]
- name: capital-lookup
  input: "What's the capital of France?"
  expectedOutputContains: "Paris"
```

```java
@ParameterizedTest(name = "{0}")
@MethodSource("scenarios")
void agentHandlesGoldenScenarios(EvalScenario scenario) {
    AgentResult result = agent.run(scenario.input());
    assertThat(result).hasFinalAnswerContaining(scenario.expectedOutputContains());
}

static Stream<EvalScenario> scenarios() {
    return EvalScenarios.fromYamlResource("scenarios.yaml").stream();
}
```

`EvalScenarios` also has `fromYaml(Path)` and `fromYaml(InputStream)` overloads. This is
deliberately *not* a config-driven test runner — the YAML supplies data, JUnit still owns the test.

### A case in steps

A case that takes more than one turn ("create a page, then add a form to it") lists `steps:`, each with its own
`input` (or `inputs`) and its own expectations. `scenario.stepList()` gives them in order, and
`step.asScenario(scenario, n)` turns one into a scenario of its own (named `<case> · step n`) for code that reads
scenarios. `weave eval` runs them in order, each as a run of its own on the same project, and names each step's checks.

```yaml
- name: page then form
  steps:
    - input: "Create a page with a title"
      expected_output_contains: "created"
    - input: "Add a contact form to it"
      expected_output_not_contains: "error"
```

### Evidence: a check's result as data

When the output to score is the result of a check rather than text (a test report or a schema check, through the
`verify` tool), read it as `Evidence`: `Evidence.fromVerify(result)` keeps the counts and the status, which the tool
parsed, and the item texts (test names, messages), which a model may have written and which no criterion should rest
on. `Criteria.evidencePassed(name)` passes when the check passed; `Criteria.evidenceAtMost(name, "failed", 0)` bounds
one count. A result that cannot be read is a failure, never a pass.


## Dataset synthesis

```java
DatasetSynthesizer synth = DatasetSynthesizer.using(generatorClient);

SynthesisResult result = synth.fromDocuments(chunks, SynthesisOptions.defaults()
        .scenariosPerDocument(2)
        .evolutions(Evolution.REASONING, Evolution.MULTI_CONTEXT)
        .seed(42));
EvalScenarios.toYaml(result.scenarios(), Path.of("src/test/resources/generated.yaml"));
// result.report(): generated / filtered / duplicates / failed + warnings
```

Also `fromDescription(...)` and `fromSeeds(...)` for agent goldens. Generate once, commit the YAML, then
load it with `EvalScenarios.fromYamlResource(...)` like any hand-written dataset. Candidates pass a
quality judge and de-duplication, so you may get fewer scenarios than requested — nothing is padded.
