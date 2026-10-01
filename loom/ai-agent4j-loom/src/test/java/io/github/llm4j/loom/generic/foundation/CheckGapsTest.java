package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.loom.generic.support.RecordingEffects;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Option and argument rules that cut across kinds (verification V1.3, V1.13, V3.6). */
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

    @Test
    @Tag("V1.13")
    void aListOrObjectWhereTextIsExpectedIsRefusedAndANumberIsReadAsText() throws Exception {
        RecordingEffects ctx = new RecordingEffects();
        Declared d = new Declared(Map.of("HOOK", "http://localhost:9/hook"), dir);
        Tool hook = d.create("tool H { use: webhook  url: env.HOOK  allow_http: true  retries: 0  timeout: 1s }", ctx);
        assertThat(hook.execute(Map.of("text", List.of("a", "b")))).startsWith("Error:").contains("text");
        assertThat(hook.execute(Map.of("text", Map.of("k", "v")))).startsWith("Error:").contains("text");
        // A number is text, so it gets as far as the (unreachable) endpoint rather than being refused as the wrong type.
        assertThat(hook.execute(Map.of("text", 42))).startsWith("Error:").doesNotContain("must be text");
    }

    @Test
    @Tag("V3.6")
    void aRefusalNamesItsRuleButDoesNotEchoTheUrlPathOrQuery() {
        Declared d = new Declared(Map.of("HOOK", "https://169.254.169.254/SECRETPATH123?token=SECRETQUERY456"), dir);
        List<String> problems = d.problems("tool H { use: webhook  url: env.HOOK  retries: 0  timeout: 1s }");
        assertThat(problems).isNotEmpty().anyMatch(p -> p.contains("link-local"));
        assertThat(String.join(" ", problems)).doesNotContain("SECRETPATH123").doesNotContain("SECRETQUERY456");
        Declared plain = new Declared(Map.of("HOOK", "http://hooks.example.com/SECRETPATH123?token=SECRETQUERY456"), dir);
        assertThat(String.join(" ", plain.problems("tool H { use: webhook  url: env.HOOK }"))).doesNotContain("SECRETPATH123").doesNotContain("SECRETQUERY456");
    }
}
