package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.Fuzz;
import io.github.llm4j.tools.support.RecordingEffects;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What every tool does with whatever arguments a model sends: it answers with text and never throws (V1.6, V1.13, V3.6). */
class AnyArgumentsTest {

    @TempDir
    Path dir;

    // ── V1.6: whatever an agent sends, a generic tool answers with text ──────────────────────

    @Test
    @Tag("V1.6")
    @Tag("F2")
    @org.junit.jupiter.api.Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    void generatedArgumentsNeverMakeAToolThrow() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        Files.createDirectories(dir.resolve("notes"));
        Files.writeString(dir.resolve("notes/a.md"), "hello");
        Declared d = new Declared(Map.of("HOOK", "http://localhost:" + closedPort + "/hook", "DB", "jdbc:h2:mem:never" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"), dir);
        RecordingEffects ctx = new RecordingEffects();
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("webhook", d.create("tool T { use: webhook  url: env.HOOK  allow_http: true  retries: 0  timeout: 1s }", ctx));
        tools.put("http", d.create("tool T { use: http  base_url: \"http://localhost:" + closedPort + "\"  methods: \"GET, POST\"  retries: 0  timeout: 1s }", ctx));
        tools.put("file", d.create("tool T { use: file  root: \"notes\"  mode: readwrite  overwrite: true }", ctx));
        tools.put("shell", d.create("tool T { use: shell  allow: \"echo\"  timeout: 5s  unattended: true }", ctx));
        tools.put("sql", d.create("tool T { use: sql  url: env.DB }", ctx));
        tools.put("email", d.create("tool T { use: email  outbox: \"out\"  from: \"a@example.com\"  allow_to: \"*@example.com\"  attachments: true  max_per_run: 10000 }", ctx));
        List<String> keys = List.of("text", "title", "path", "method", "query", "body", "action", "content", "from_line", "lines", "pattern", "program",
                "args", "sql", "params", "table", "to", "subject", "html", "attach", "url", "host", "headers", "unknown");

        Fuzz.run("never-throws", random -> {
            for (Map.Entry<String, Tool> e : tools.entrySet()) {
                Map<String, Object> args = new HashMap<>();
                for (int i = random.nextInt(6); i >= 0; i--) args.put(keys.get(random.nextInt(keys.size())), value(random, 0));
                String result;
                try {
                    result = e.getValue().execute(random.nextInt(40) == 0 ? null : args);
                } catch (Throwable t) {
                    throw new AssertionError(e.getKey() + " threw " + t + " for " + args, t);
                }
                assertThat(result).as(e.getKey() + " " + args).isNotNull();
            }
        });
    }

    private static Object value(Random random, int depth) {
        return switch (random.nextInt(depth > 1 ? 7 : 9)) {
            case 0 -> null;
            case 1 -> random.nextInt(2000) - 1000;
            case 2 -> random.nextBoolean();
            case 3 -> "x".repeat(random.nextInt(3) * 3000);
            case 4 -> text(random);
            case 5 -> random.nextDouble();
            case 6 -> "";
            case 7 -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) list.add(value(random, depth + 1));
                yield list;
            }
            default -> {
                Map<String, Object> map = new HashMap<>();
                for (int i = random.nextInt(3); i > 0; i--) map.put(text(random), value(random, depth + 1));
                yield map;
            }
        };
    }

    private static String text(Random random) {
        String[] pieces = {"a", "..", "/", "\\", "\n", "\r\n", "\u0000", "é", "SELECT 1", "DROP TABLE t", "read", "write", "append", "list", "exists",
                "query", "schema", "GET", "POST", "echo", " ", "@example.com", "%2e", "?", "#", "x@example.com", "*", "$(id)"};
        StringBuilder sb = new StringBuilder();
        for (int i = random.nextInt(5); i >= 0; i--) sb.append(pieces[random.nextInt(pieces.length)]);
        return sb.toString();
    }

    @Test
    @Tag("H1")
    @Tag("V1.6")
    void aListOrObjectIsNotTextSoItIsRefusedRatherThanSentAsJunk() throws Exception {
        Files.createDirectories(dir.resolve("notes"));
        Declared d = new Declared(Map.of("HOOK", "http://localhost:9/hook"), dir);
        Tool file = d.create("tool T { use: file  root: \"notes\"  mode: readwrite }", new RecordingEffects());
        assertThat(file.execute(Map.of("action", "write", "path", "x.md", "content", List.of("a", "b")))).startsWith("Error:").contains("content must be text");
        assertThat(file.execute(Map.of("action", List.of("read"), "path", "x.md"))).startsWith("Error:");
        assertThat(file.execute(Map.of("action", "write", "path", Map.of("k", "v"), "content", "x"))).startsWith("Error:").contains("path must be text");
        assertThat(Files.list(dir.resolve("notes")).count()).isZero();
        // Numbers and booleans are read as text, which is what a model that writes {"content": 42} means.
        assertThat(file.execute(Map.of("action", "write", "path", "n.md", "content", 42))).startsWith("Wrote");
        assertThat(Files.readString(dir.resolve("notes/n.md"))).isEqualTo("42");
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
