package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan, Requirement 8 (V8.3, V8.4): `weave run` budget flags and the spend table. */
class WeaveCliBudgetTest {

    @TempDir
    Path dir;

    private final AtomicInteger calls = new AtomicInteger();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final LLMClientFactory factory = model -> new LLMClient() {
        @Override
        public LLMResponse chat(LLMRequest request) {
            int n = calls.incrementAndGet();
            return LLMResponse.builder().content("```json\n{\"final_answer\": \"answer " + n + "\"}\n```")
                    .model(model).tokenUsage(100, 50, 150).build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }
    };

    private File script(String budget) throws Exception {
        Path file = dir.resolve("test.loom");
        Files.writeString(file, budget + """
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                agent Critic { model: "test/model" system: "You are Critic." budget { per_call: 50 } }
                workflow Main() {
                    delegate "one" to Writer -> a
                    delegate "two" to Critic -> b
                    delegate "three" to Writer -> c
                }
                """);
        return file.toFile();
    }

    private int weave(File script, Long maxTokens, String maxCost, File prices) throws Exception {
        return WeaveCLI.execute(script, null, "Main", Map.of(), factory, maxTokens, null, maxCost, prices,
                message -> "", new PrintStream(out, true), new PrintStream(err, true));
    }

    @Test
    void v8_3_theRunEndsWithTheSpendTable() throws Exception {
        assertThat(weave(script("budget { tokens: 1000 }\n"), null, null, null)).isZero();
        String text = out.toString();
        assertThat(text).contains("✅ Workflow completed successfully.").contains("💸 Spend");
        String table = text.substring(text.indexOf("💸 Spend"));
        assertThat(table).containsPattern("agent\\s+calls\\s+prompt\\s+completion\\s+cost")
                .containsPattern("Writer\\s+2\\s+200\\s+100\\s+\\$0")
                .containsPattern("Critic\\s+1\\s+100\\s+50\\s+\\$0")
                .containsPattern("total\\s+3\\s+300\\s+150\\s+\\$0");
    }

    @Test
    void v8_4_maxTokensReplacesTheScriptsBudget() throws Exception {
        assertThat(weave(script("budget { tokens: 1000 }\n"), 300L, null, null)).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(out.toString()).contains("⛔ Stopped: budget exhausted: run (tokens 300/300)")
                .containsPattern("total\\s+2\\s+200\\s+100");
    }

    @Test
    void v8_4b_maxCostNeedsPrices() throws Exception {
        assertThat(weave(script(""), null, "0.50", null)).isEqualTo(2);
        assertThat(err.toString()).contains("--max-cost needs --prices");
        assertThat(calls.get()).isZero();

        Path prices = dir.resolve("prices.properties");
        Files.writeString(prices, "test/model = 1.00 / 2.00\n");
        assertThat(weave(script(""), null, "$0.0004", prices.toFile())).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(out.toString()).containsPattern("total\\s+2\\s+200\\s+100\\s+\\$0.0004");
    }

    @Test
    void aScriptWithoutBudgetsPrintsNoTable() throws Exception {
        Path file = dir.resolve("plain.loom");
        Files.writeString(file, """
                agent Writer { model: "test/model" system: "You are Writer." }
                workflow Main() { delegate "one" to Writer -> a }
                """);
        assertThat(weave(file.toFile(), null, null, null)).isZero();
        assertThat(out.toString()).doesNotContain("💸 Spend");
    }
}
