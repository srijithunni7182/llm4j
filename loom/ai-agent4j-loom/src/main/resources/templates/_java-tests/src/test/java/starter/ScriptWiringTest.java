package starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.export.EvalChecks;
import io.github.llm4j.eval.export.EvalStatus;
import io.github.llm4j.eval.export.Evaluation;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.MetricRef;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.loom.eval.DatasetFolder;
import io.github.llm4j.loom.eval.EvalRunner;
import io.github.llm4j.loom.eval.Fixtures;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.eval.ScenarioResult;
import io.github.llm4j.loom.eval.Status;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every scenario of the golden dataset is its own JUnit test case here, run through the script on a model that costs nothing. A case fails when its run
 * breaks (a step, a prompt, a branch or a schema is wired wrong), never because an answer is poor: a mock model says nothing about content, and no real
 * model is called. Each run is recorded under the dimension "wiring" (never under the dataset's quality dimensions, which stay "no results" until a real
 * run judges them), and {@code mvn test} writes the eval4j dashboard to target/eval4j/report/index.html. To judge answers for real, run {@code weave eval src/main/resources/main.loom --max-tokens 200000}, which asks before it spends.
 */
@ExtendWith(EvalReportExtension.class)
class ScriptWiringTest {

    /**
     * What this test records in the dashboard: a deterministic check under the "wiring" dimension (never under the dataset's quality dimensions), filed
     * under the group "agents" or "workflows" and, inside it, under the agent or workflow itself, so the dashboard shows one card per agent.
     * Each target needs its own metric id: two targets sharing one id all land in the group registered first.
     */
    private static MetricRef wiring(String target, boolean agent) {
        return MetricRef.assertion(target.toLowerCase(java.util.Locale.ROOT) + "-runs-to-the-end", "Runs to the end", agent ? "agents" : "workflows", target, "wiring");
    }

    private static final Path SCRIPT = Project.script();
    private static final Path DATASET = Project.dataset();

    private static HarnessExecutor executor(java.util.function.Consumer<HarnessExecutor> beforeInitialize) throws Exception {
        var script = new LoomLoader().load(SCRIPT.toString());
        ToolRegistry tools = new ToolRegistry();
        Fixtures.none().apply(script, tools, true);
        HarnessExecutor e = new HarnessExecutor(script, tools, new MockModels());
        e.setHumanInterface(message -> "yes");
        e.setSimulateTasks(true);   // a free test must not save, send or pay: tasks that change things are described, not run
        e.setBaseDir(SCRIPT.getParent());
        e.setEnvLookup(name -> "mock");
        e.setPromptCatalog(PromptSupport.catalog(script, SCRIPT, PromptSettings.NONE));
        beforeInitialize.accept(e);
        e.initialize();
        return e;
    }

    private static DatasetFolder.Plan plan() throws Exception {
        return DatasetFolder.plan(new LoomLoader().load(SCRIPT.toString()), DATASET, null);
    }

    @TestFactory
    Stream<DynamicTest> eachScenarioRunsToTheEndOnAMockModel() throws Exception {
        var script = new LoomLoader().load(SCRIPT.toString());
        DatasetFolder.Plan plan = plan();
        assertThat(plan.problems()).as("problems in the golden dataset").isEmpty();
        EvalRunner runner = new EvalRunner(ScriptWiringTest::executor, null, name -> null, true);
        EvalRun.get().declareDataset("golden", "Golden dataset", DATASET.toString(), plan.targets().stream().flatMap(t -> t.scenarios().stream().map(s -> labelled(t.name(), s))).toList());

        List<DynamicTest> tests = new ArrayList<>();
        for (DatasetFolder.Target target : plan.targets()) {
            List<String> parameters = script.getWorkflows().stream().filter(w -> w.getName().equals(target.name())).findFirst()
                    .map(w -> List.copyOf(w.getParameters())).orElse(List.of());
            for (EvalScenario scenario : target.scenarios()) {
                tests.add(dynamicTest(target.name() + " · " + scenario, () -> {
                    EvalRun.get().bindTest("ScriptWiringTest", target.name() + " " + scenario);
                    EvalRun.get().bindScenario(scenario);
                    try {
                        ScenarioResult r = target.isAgent()
                                ? runner.agent(target.file(), target.name(), scenario)
                                : runner.workflow(target.file(), target.name(), parameters, scenario, null);
                        EvalChecks.check(wiring(target.name(), target.isAgent()),
                                () -> assertThat(r.status()).as(target.name() + " " + r.label() + ": " + r.error()).isNotEqualTo(Status.FAIL));
                    } finally {
                        EvalRun.get().unbind();
                    }
                }));
            }
        }
        // An agent (or the Main workflow) with no examples must still be listed, so a gap is visible in the dashboard and not just absent from it
        java.util.Set<String> covered = plan.targets().stream().map(DatasetFolder.Target::name).collect(java.util.stream.Collectors.toSet());
        script.getAgents().stream().map(a -> a.getName()).filter(n -> !covered.contains(n)).forEach(n -> tests.add(noExamples(n, true)));
        if (!covered.contains("Main") && script.getWorkflows().stream().anyMatch(w -> w.getName().equals("Main"))) tests.add(noExamples("Main", false));
        return tests.stream();
    }

    /** Records "not evaluated: no examples written" for a target, under its own card. It does not fail the build: it makes the gap visible. */
    private static DynamicTest noExamples(String target, boolean agent) {
        return dynamicTest(target + " · no examples written", () -> {
            EvalRun.get().bindTest("ScriptWiringTest", target + " no examples written");
            try {
                EvalRun.get().record(Evaluation.builder(wiring(target, agent)).status(EvalStatus.NOT_EVALUATED)
                        .reason("no examples are written for " + target + " in the golden dataset (add some: weave guide 11)"));
            } finally {
                EvalRun.get().unbind();
            }
        });
    }

    /** The same scenario named after the agent or workflow it tests ("Verifier · Flags a false claim"), so the dashboard says whose test each row is. */
    private static EvalScenario labelled(String target, EvalScenario s) {
        String own = s.name() != null ? s.name() : s.id() != null ? s.id() : "scenario";
        return new EvalScenario(target + " · " + own, s.input(), s.expectedOutputContains(), s.expectedOutput(), s.expectedTools(), s.context(), s.retrievalContext(),
                s.id(), s.dimensions(), s.tags(), s.rubric(), s.expect(), s.inputs(), s.expectedOutputNotContains(), s.expectedMinWords(), s.expectedMaxWords());
    }

    @Test
    void thereAreScenariosToRun() throws Exception {
        assertThat(plan().scenarioCount()).as("scenarios").isGreaterThan(0);
    }
}
