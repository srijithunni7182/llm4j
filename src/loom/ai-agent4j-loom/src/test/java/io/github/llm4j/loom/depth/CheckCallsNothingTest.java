package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.CliProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan N6: weave check on a script using every new feature calls no model and embeds nothing. */
class CheckCallsNothingTest {

    @TempDir
    Path dir;

    @Test
    void n6_checkingTouchesNoModelNoEmbeddingNoSpeechService() throws Exception {
        Path f = dir.resolve("all.loom");
        Files.writeString(f, """
                provider Box { use: ollama  base_url: "http://127.0.0.1:1" }
                persona Mentor { role: "mentor" }
                tool Graph { use: knowledge_graph  store: "g.json" }
                agent A {
                    model: "Box/llama3"
                    persona: Mentor
                    tools: [Graph, translate, speak]
                    memory { conversation: "chats"  facts: "facts.json"  embedding: "gemini/text-embedding-004"  session: "{user}" }
                    voice { listen: "sarvam/saarika:v2.5"  speak: "sarvam/bulbul:v2" }
                    guard { pii: mask  bias: block  bias_model: "gemini-2.5-flash" }
                }
                workflow Main() { delegate "{message}" to A -> out }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger clients = new AtomicInteger();
        int code = CliProbe.check(f.toFile(), false, new PrintStream(out, true),
                Map.of("SARVAM_API_KEY", "s", "GEMINI_API_KEY", "g")::get, clients);
        assertThat(out.toString()).contains("✓ all.loom: ready to run");
        assertThat(code).isZero();
        assertThat(clients).hasValue(0);
        assertThat(dir.resolve("g.json")).doesNotExist();
        assertThat(dir.resolve("facts.json")).doesNotExist();
        assertThat(dir.resolve("chats")).doesNotExist();
    }
}
