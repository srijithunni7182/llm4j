package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.CliProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R3, R4, R15: weave check understands the context block and calls no model. */
class ContextBlockCheckTest {

    @TempDir Path dir;

    private String check(String block) throws Exception {
        Path f = dir.resolve("flow.loom");
        Files.writeString(
                f,
                "agent A {\n  model: \"gemini-2.5-flash\"\n  "
                        + block
                        + "\n}\nworkflow Main() { delegate \"x\" to A -> out }\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("GEMINI_API_KEY", "g", "ANTHROPIC_API_KEY", "a");
        CliProbe.check(
                f.toFile(),
                new PrintStream(out, true),
                env::get,
                new io.github.llm4j.loom.execution.DefaultLLMClientFactory(env::get));
        return out.toString();
    }

    @Test
    void aGoodBlockIsReady() throws Exception {
        assertThat(
                        check(
                                "context { compact: chain(trim_tool_results, drop_oldest)  trigger: 80%  target: 55%  keep_recent: 6 }"))
                .contains("ready to run");
    }

    @Test
    void anUnknownStrategyNamesTheLineAndTheKnownOnes() throws Exception {
        String out = check("context { compact: drop_oldst }");
        assertThat(out)
                .contains("line 3")
                .contains("agent A")
                .contains("drop_oldst")
                .contains("drop_oldest")
                .contains("summarize");
    }

    @Test
    void anUnknownMemberOfAChainIsReported() throws Exception {
        assertThat(check("context { compact: chain(trim_tool_results, nope) }")).contains("nope");
    }

    @Test
    void triggerMustBeAbovetarget() throws Exception {
        assertThat(check("context { compact: drop_oldest  trigger: 50%  target: 60% }"))
                .contains("target")
                .contains("trigger");
    }

    @Test
    void aFractionIsExplained() throws Exception {
        assertThat(check("context { compact: drop_oldest  trigger: 0.8 }")).contains("80%");
    }

    @Test
    void percentOutOfRange() throws Exception {
        assertThat(check("context { compact: drop_oldest  trigger: 120% }"))
                .contains("between 1% and 99%");
    }

    @Test
    void numbersMustBePositive() throws Exception {
        assertThat(check("context { compact: drop_oldest  keep_recent: -1 }"))
                .contains("keep_recent");
        assertThat(check("context { compact: drop_oldest  max_compactions: 0 }"))
                .contains("max_compactions");
    }

    @Test
    void anUnknownKeyListsTheAllowedOnes() throws Exception {
        assertThat(check("context { compact: drop_oldest  windw: 100 }"))
                .contains("unknown context setting windw")
                .contains("keep_recent");
    }

    @Test
    void theWindowIsNeverWrittenInTheScript() throws Exception {
        assertThat(check("context { compact: drop_oldest  window: 100000 }"))
                .contains("windows file");
    }

    @Test
    void summarizeWithoutASummarizerWarnsAboutTheFallback() throws Exception {
        assertThat(check("context { compact: summarize }"))
                .contains("summarizer")
                .contains("drop_oldest");
    }

    @Test
    void theSummarizerMustBeAKnownModel() throws Exception {
        assertThat(check("context { compact: summarize  summarizer: \"no-such-model-xyz\" }"))
                .contains("no-such-model-xyz");
    }

    @Test
    void referencesToEnvAreRefused() throws Exception {
        assertThat(check("context { compact: drop_oldest  keep_recent: env.N }"))
                .contains("written in the script");
    }

    @Test
    void noBlockNoMessage() throws Exception {
        assertThat(check("")).contains("ready to run").doesNotContain("context");
    }
}
