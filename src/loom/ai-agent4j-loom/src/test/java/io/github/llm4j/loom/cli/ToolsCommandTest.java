package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code weave tools fetch} is on the command line, and a development build is refused through it. */
class ToolsCommandTest {
    @Test
    void theCommandExistsAndAnUnknownTargetIsABadOption() {
        var result = FieldCli.weave("tools", "fetch", "nonsense");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("fabric, inspect or all");
    }

    @Test
    void runFromClassesItRefusesBeforeContactingAnyone() {
        var result = FieldCli.weave("tools", "fetch", "fabric", "--yes");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("development build");
    }
}
