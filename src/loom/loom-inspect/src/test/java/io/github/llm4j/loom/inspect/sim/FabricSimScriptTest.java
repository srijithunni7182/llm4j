package io.github.llm4j.loom.inspect.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * R34: the simulator has a calm preset (one process, one run) that the documentation recommends
 * first.
 */
class FabricSimScriptTest {

    private static final Path DIR = Path.of("../../examples/fabric-sim");

    @Test
    void theCalmPresetIsOneProcessAndOneRun() throws IOException {
        String script = Files.readString(DIR.resolve("run.sh"));
        assertThat(script)
                .contains("if [ \"${1:-}\" = \"calm\" ]; then PROCESSES=1; RUNS=1; shift; fi");
        // the preset is read before the defaults are filled in, so it wins
        assertThat(script.indexOf("\"calm\"")).isLessThan(script.indexOf(": \"${PROCESSES:=3}\""));
    }

    @Test
    void theReadmeRecommendsItFirstAndSaysWhatTheDefaultIs() throws IOException {
        String readme = Files.readString(DIR.resolve("README.md"));
        assertThat(readme).contains("./run.sh calm").contains("busy fleet");
        assertThat(readme.indexOf("Start calm")).isLessThan(readme.indexOf("## Run it"));
    }
}
