package starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalDataset;
import io.github.llm4j.loom.eval.DatasetFolder;
import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The golden dataset in eval/golden is well formed and every file in it is for something the script has. */
class GoldenDatasetTest {

    private static final Path SCRIPT = Path.of("main.loom").toAbsolutePath();
    private static final Path DATASET = Path.of("eval", "golden").toAbsolutePath();

    @Test
    void theDatasetHasNoProblems() throws Exception {
        var script = new LoomLoader().load(SCRIPT.toString());

        DatasetFolder.Plan plan = DatasetFolder.plan(script, DATASET, null);

        assertThat(plan.problems()).as("problems in eval/golden").isEmpty();
        assertThat(plan.scenarioCount()).as("scenarios").isGreaterThan(0);
    }

    @Test
    void everyScenarioHasAnInputAndAnIdThatIsNotRepeated() {
        EvalDataset dataset = EvalDataset.load(DATASET);

        assertThat(dataset.problems()).isEmpty();
        assertThat(dataset.all()).allSatisfy(s -> assertThat(s.input()).isNotBlank());
    }
}
