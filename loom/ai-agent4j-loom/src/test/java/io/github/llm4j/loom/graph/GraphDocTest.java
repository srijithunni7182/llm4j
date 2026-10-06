package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.CliProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** V9.4: the example in the guide is what {@code weave graph} really prints. */
class GraphDocTest {

    private static final Pattern EXAMPLE = Pattern.compile(
            "<!-- graph-example: (\\S+) (\\S+) -->\\n```\\n\\$ weave graph [^\\n]*\\n(.*?)```", Pattern.DOTALL);

    @Test
    void theGuidesExampleIsTheRealOutput() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        Matcher m = EXAMPLE.matcher(guide);
        assertThat(m.find()).as("the guide has a graph example").isTrue();
        String script = m.group(1);
        String workflow = m.group(2);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = CliProbe.graph(Path.of(script).toFile(), "mermaid", workflow, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        String actual = out.toString(StandardCharsets.UTF_8).replace(Path.of("").toAbsolutePath().toString(), "/path/to");
        assertThat(actual.stripTrailing()).isEqualTo(m.group(3).stripTrailing());
    }

    @Test
    void theGuideNamesTheCommandAndItsOptions() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));

        assertThat(guide).contains("weave graph").contains("--format mermaid").contains("--workflow").contains("graph-result.schema.json");
    }
}
