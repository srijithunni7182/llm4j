package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generate.ReviewGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R15: a fix that is SAFE is applied by the tool, not asked of the model. */
class SafeFixByToolTest {

    @TempDir Path dir;

    private WeaveEnv env() {
        return new WeaveEnv(
                m -> {
                    throw new IllegalStateException("no model");
                },
                q -> "",
                System.out,
                System.err,
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                k -> null);
    }

    @Test
    void aToolAgentWithoutRetryOrTimeoutIsFixedInTheFile() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(
                script,
                "budget { tokens: 1000  calls: 5 }\nagent A { model: \"m\"  system: \"x\"  tools: [calculator] }\nworkflow Main(question) {\n    delegate \"Work out {question}\" to A -> answer\n}\n");
        ReviewGate.Reviewed r = GenerateReview.reviewer(env()).reviewAndFix(script);
        assertThat(r.fixed()).anyMatch(f -> f.startsWith("LR01"));
        assertThat(Files.readString(script)).contains("retry").contains("timeout");
        assertThat(r.open()).noneMatch(i -> i.rule().equals("LR01"));
    }
}
