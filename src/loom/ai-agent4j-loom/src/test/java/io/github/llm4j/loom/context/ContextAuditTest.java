package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.CliProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R15, R23: weave audit lists each agent's strategy and window source, and the .loct mappings, without loading a class. */
class ContextAuditTest {

    @TempDir
    Path dir;

    @AfterEach
    void clean() {
        ContextFiles.clear();
    }

    private String audit(String format) throws Exception {
        Path f = dir.resolve("flow.loom");
        Files.writeString(f, """
                agent Researcher {
                  model: "claude-sonnet-5-5"
                  context { compact: chain(trim_tool_results, keep_newest, drop_oldest)  summarizer: "claude-haiku-5-5" }
                }
                agent Plain { model: "claude-haiku-5-5" }
                agent Odd { model: "mystery-model"  context { compact: drop_oldest } }
                workflow Main() { delegate "x" to Researcher -> out }
                """);
        Files.writeString(dir.resolve("flow.loct"), "keep_newest = io.github.llm4j.loom.context.Strategies$KeepNewest\n");
        Files.writeString(dir.resolve("flow.windows"), "mystery-model = 20000 / 1000\n");
        ContextFiles.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Strategies.calls.set(0);
        CliProbe.audit(f.toFile(), format, new PrintStream(out, true));
        return out.toString();
    }

    @Test
    void markdownListsStrategiesWindowsAndSources() throws Exception {
        String md = audit("md");
        assertThat(md).contains("## Context and compaction");
        assertThat(md).contains("Researcher").contains("trim_tool_results -> keep_newest (yours) -> drop_oldest").contains("1000000 tokens").contains("the seeded catalogue");
        assertThat(md).contains("claude-haiku-5-5");
        assertThat(md).contains("Odd").contains("20000 tokens (19000 usable)").contains("flow.windows");
        assertThat(md.substring(md.indexOf("## Context and compaction"), md.indexOf("## Your own compaction"))).doesNotContain("Plain");
        assertThat(md).contains("## Your own compaction strategies").contains("keep_newest").contains("Strategies$KeepNewest").contains("flow.loct line 1");
    }

    @Test
    void jsonHasTheSameInformation() throws Exception {
        String json = audit("json");
        assertThat(json).contains("\"context\"").contains("\"customStrategies\"").contains("\"keep_newest\"").contains("\"source\"");
    }

    @Test
    void theAuditNeverRunsTheStrategy() throws Exception {
        audit("md");
        assertThat(Strategies.calls.get()).isZero();
    }

    @Test
    void aScriptWithoutContextBlocksHasNoSuchSection() throws Exception {
        Path f = dir.resolve("plain.loom");
        Files.writeString(f, "agent A { model: \"claude-haiku-5-5\" }\nworkflow Main() { delegate \"x\" to A -> out }\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliProbe.audit(f.toFile(), "md", new PrintStream(out, true));
        assertThat(out.toString()).doesNotContain("Context and compaction").doesNotContain("compaction strategies");
    }
}
