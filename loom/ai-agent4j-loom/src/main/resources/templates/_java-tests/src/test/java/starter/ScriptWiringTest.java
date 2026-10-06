package starter;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;

/**
 * Runs every scenario of the dataset through the script on a model that costs nothing, to check the wiring: steps, prompts, branches and
 * schemas. It does not judge answers; that needs a real model (weave eval, with a cap).
 */
class ScriptWiringTest {

    private static final Path SCRIPT = Path.of("main.loom").toAbsolutePath();
    private static final Path DATASET = Path.of("eval", "golden").toAbsolutePath();

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

    @Test
    void everyScenarioRunsToTheEndOnAMockModel() throws Exception {
        var script = new LoomLoader().load(SCRIPT.toString());
        DatasetFolder.Plan plan = DatasetFolder.plan(script, DATASET, null);
        assertThat(plan.problems()).isEmpty();
        EvalRunner runner = new EvalRunner(ScriptWiringTest::executor, null, name -> null, true);

        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (DatasetFolder.Target target : plan.targets()) {
            for (var scenario : target.scenarios()) {
                ran++;
                ScenarioResult r = target.isAgent()
                        ? runner.agent(target.file(), target.name(), scenario)
                        : runner.workflow(target.file(), target.name(), script.getWorkflows().stream().filter(w -> w.getName().equals(target.name())).findFirst().orElseThrow().getParameters().stream().findFirst().orElse(null), scenario, null);
                if (r.status() == Status.FAIL) failures.add(target.name() + " " + r.label() + ": " + r.error());
            }
        }

        assertThat(ran).as("scenarios run").isGreaterThan(0);
        assertThat(failures).isEmpty();
    }
}
