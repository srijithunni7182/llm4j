package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Option rules that go through the script runtime's tool factory (verification V1.3). */
class CheckGapsTest {

    @TempDir
    Path dir;

    @Test
    @Tag("V1.3")
    void aSecretWrittenAsALiteralIsALoadErrorThatSuggestsAnEnvVar() {
        Declared d = new Declared(Map.of(), dir);
        assertThat(d.problems("tool H { use: webhook  url: \"https://hooks.slack.com/services/T0/B0/XXXXXXXX\" }"))
                .anyMatch(p -> p.contains("env."));
        assertThat(d.problems("tool M { use: email  host: \"smtp.example.com\"  from: \"a@example.com\"  to: \"b@example.com\"  password: \"hunter2hunter2\" }"))
                .anyMatch(p -> p.contains("env."));
    }

    @Test
    @Tag("V1.3")
    void anUnsetEnvVarIsALoadErrorNamingIt() {
        Declared d = new Declared(Map.of(), dir);
        assertThat(d.problems("tool H { use: webhook  url: env.NO_SUCH_HOOK }")).anyMatch(p -> p.contains("NO_SUCH_HOOK"));
    }
}
