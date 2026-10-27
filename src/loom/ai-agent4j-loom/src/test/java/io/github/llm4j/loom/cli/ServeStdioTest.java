package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.serve.Framing;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R13, R18 of spec 10: {@code weave serve --stdio} as a real process. Standard output carries
 * frames and nothing else, however much the code under it prints.
 */
class ServeStdioTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private JsonNode call(
            OutputStream out, InputStream in, int id, String method, ObjectNode params)
            throws Exception {
        ObjectNode m = JSON.createObjectNode();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        m.set("params", params);
        Framing.write(out, m.toString());
        return JSON.readTree(Framing.read(in));
    }

    @Test
    void theProcessSpeaksTheProtocolAndExitsOnShutdown() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(
                script,
                "agent A { model: \"gemini-2.5-flash\"  system: \"x\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  return r }\n");
        Process p =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                "io.github.llm4j.loom.cli.WeaveCLI",
                                "serve",
                                "--stdio")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            OutputStream out = p.getOutputStream();
            InputStream in = p.getInputStream();
            long start = System.nanoTime();
            ObjectNode init = JSON.createObjectNode();
            init.putArray("roots").add(dir.toString());
            JsonNode hello = call(out, in, 1, "initialize", init).path("result");
            long coldMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(hello.path("protocol").asInt()).isEqualTo(1);
            assertThat(coldMillis).as("cold start to first answer").isLessThan(4000);

            ObjectNode req = JSON.createObjectNode().put("file", script.toString());
            JsonNode review = call(out, in, 2, "loom/review", req).path("result");
            assertThat(review.path("result").path("version").asInt()).isEqualTo(1);
            JsonNode graph = call(out, in, 3, "loom/graph", req).path("result");
            assertThat(graph.path("result").path("workflows")).isNotEmpty();

            call(out, in, 4, "loom/shutdown", JSON.createObjectNode());
            assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).isZero();
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void withoutStdioItSaysSoAndExits() throws Exception {
        Process p =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                "io.github.llm4j.loom.cli.WeaveCLI",
                                "serve")
                        .redirectErrorStream(true)
                        .start();
        String all = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(2);
        assertThat(all).contains("--stdio");
    }
}
