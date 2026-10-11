package io.github.llm4j.loom.inspect.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The example's rules must stay valid for the example's script, as the guide's example must stay valid Loom. */
class SoakRulesTest {

    private static final Path DIR = Path.of("..", "..", "examples", "inspect-soak");

    @Test
    void theSoakRulesCheckCleanAgainstTheSoakScript() throws Exception {
        Path script = DIR.resolve("soak.loom");
        assertThat(Files.isRegularFile(script)).isTrue();
        var loaded = new LoomLoader().load(script.toAbsolutePath().toString());
        RulesFile.Parsed parsed = RulesFile.read(RulesFile.sidecarFor(script));
        assertThat(RulesCheck.check(loaded, parsed)).isEmpty();
        assertThat(parsed.rules()).containsOnlyKeys("Editor", "Lead", "Scriptwriter");
    }

    @Test
    void theRulesStayOutOfTheScript() throws Exception {
        assertThat(Files.readString(DIR.resolve("soak.loom"))).doesNotContain("inspect {").doesNotContain("same_action").doesNotContain("no_progress");
    }
}
