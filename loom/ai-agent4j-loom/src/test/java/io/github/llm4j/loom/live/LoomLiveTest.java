package io.github.llm4j.loom.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verification plan L11: the whole stack against real models — a script run by {@code weave run --trace},
 * using a tool and an output schema. Runs for each provider whose key is set; never in a default build
 * ({@code mvn -pl loom/ai-agent4j-loom -Plive test}).
 */
@Tag("live")
class LoomLiveTest {

    @TempDir
    Path dir;

    static Stream<String> models() {
        List<String> models = new ArrayList<>();
        if (set("ANTHROPIC_API_KEY")) {
            String first = System.getenv().getOrDefault("ANTHROPIC_TEST_MODELS", "claude-opus-5-5").split(",")[0].strip();
            models.add("anthropic/" + first);
        }
        if (set("GEMINI_API_KEY")) models.add(System.getenv().getOrDefault("GEMINI_TEST_MODELS", "gemini-2.5-flash").split(",")[0].strip());
        if (set("SARVAM_API_KEY")) models.add("sarvam/" + System.getenv().getOrDefault("SARVAM_TEST_MODELS", "sarvam-m").split(",")[0].strip());
        return models.isEmpty() ? Stream.of("none") : models.stream();
    }

    static boolean set(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank();
    }

    @ParameterizedTest(name = "L11 {0}")
    @MethodSource("models")
    void l11_aScriptRunsEndToEnd(String model) throws Exception {
        assumeTrue(!model.equals("none"), "no provider key set");
        Path script = dir.resolve("orders.loom");
        Files.writeString(script, """
                budget { tokens: 40000 }
                agent Clerk {
                    model: "%s"
                    system: "You are a careful warehouse clerk."
                    tools: [calculator]
                    max_iterations: 6
                    output_schema: { boxes: number, items_per_box: number, total: number }
                }
                workflow Main() {
                    delegate "We received 48 boxes with 37 items each. Use the calculator for the total." to Clerk -> order
                    note "Total: {order.total}"
                }
                """.formatted(model));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = CliProbe.run(script.toFile(), "text", new PrintStream(out, true), new PrintStream(err, true),
                System::getenv, new DefaultLLMClientFactory());
        System.out.println("L11 " + model + " stdout:\n" + out + "\nL11 trace:\n" + err);
        assertThat(code).as("weave exit code").isZero();
        assertThat(err.toString()).contains("🔧 calculator").contains("✔");
        assertThat(err.toString().replace(",", "")).contains("1776");
    }
}
