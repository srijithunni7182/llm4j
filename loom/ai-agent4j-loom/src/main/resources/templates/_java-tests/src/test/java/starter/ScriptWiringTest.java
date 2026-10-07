package starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.llm4j.eval.dataset.EvalScenario;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every scenario of the golden dataset is its own JUnit test case here, run through the script on a model that costs nothing. A case fails when its run
 * breaks (a step, a prompt, a branch or a schema is wired wrong), never because an answer is poor: a mock model says nothing about content, and no real
 * model is called. To judge answers for real, run {@code weave eval src/main/resources/main.loom --max-tokens 200000}, which asks before it spends.
 */
class ScriptWiringTest {

    private static final Path SCRIPT = Project.script();
    private static final Path DATASET = Project.dataset();

    private static HarnessExecutor executor(java.util.function.Consumer<HarnessExecutor> beforeInitialize) throws Exception {
        var script = new LoomLoader().load(SCRIPT.toString());
        ToolRegistry tools = new ToolRegistry();
        Fixtures.none().apply(script, tools, true);
        HarnessExecutor e = new HarnessExecutor(script, tools, new MockModels());
        e.setHumanInterface(message -> "yes");
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

        List<DynamicTest> tests = new ArrayList<>();
        for (DatasetFolder.Target target : plan.targets()) {
            List<String> parameters = script.getWorkflows().stream().filter(w -> w.getName().equals(target.name())).findFirst()
                    .map(w -> List.copyOf(w.getParameters())).orElse(List.of());
            for (EvalScenario scenario : target.scenarios()) {
                tests.add(dynamicTest(target.name() + " · " + scenario, () -> {
                    ScenarioResult r = target.isAgent()
                            ? runner.agent(target.file(), target.name(), scenario)
                            : runner.workflow(target.file(), target.name(), parameters, scenario, null);
                    assertThat(r.status()).as(target.name() + " " + r.label() + ": " + r.error()).isNotEqualTo(Status.FAIL);
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void thereAreScenariosToRun() throws Exception {
        assertThat(plan().scenarioCount()).as("scenarios").isGreaterThan(0);
    }
}
