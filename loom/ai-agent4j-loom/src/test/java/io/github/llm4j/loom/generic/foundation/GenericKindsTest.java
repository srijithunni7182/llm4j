package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.loom.generic.support.Fuzz;
import io.github.llm4j.loom.generic.support.RecordingEffects;
import io.github.llm4j.loom.tools.ToolFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Things every generic kind shares: registration, option syntax, descriptions, and never throwing at the agent. */
class GenericKindsTest {

    @TempDir
    Path dir;

    @Test
    @Tag("V1.1")
    void allSixKindsAreRegisteredAndNoneIsABareBuiltInName() {
        assertThat(new ToolFactory().kinds()).contains("webhook", "email", "http", "file", "shell", "sql");
        assertThat(ToolFactory.BUILT_INS.keySet()).doesNotContain("webhook", "email", "http", "file", "shell", "sql");
    }

    @Test
    @Tag("V1.2")
    @Tag("V1.4")
    void optionValuesCanCarryUnitsAndKeysCanBeQuoted() {
        ToolDef def = Declared.parse("""
                tool Api { use: http  base_url: "https://api.example.com"  timeout: 20s  max_bytes: 64k  retries: 3
                           "header.X-Trace-Id": "abc"  header.Accept: "application/json"  ms: "kept as a key" }
                """);
        Map<String, String> o = new LinkedHashMap<>();
        def.getOptions().forEach((k, v) -> o.put(k, v.value()));
        assertThat(o).containsEntry("timeout", "20s").containsEntry("max_bytes", "64k").containsEntry("retries", "3")
                .containsEntry("header.X-Trace-Id", "abc").containsEntry("header.Accept", "application/json").containsEntry("ms", "kept as a key");
    }

    @Test
    @Tag("V1.2")
    void aNumberFollowedByAKeyIsNotMistakenForAUnit() {
        ToolDef def = Declared.parse("tool Api { use: http  base_url: \"https://a.example.com\"  retries: 2 s: x }");
        assertThat(def.getOptions().get("retries").value()).isEqualTo("2");
        assertThat(def.getOptions()).containsKey("s");
    }

    @Test
    @Tag("V1.4")
    void headerOptionsAreOnlyAcceptedWhereTheyMakeSense() {
        Declared d = new Declared(Map.of("T", "token-value-1234"), dir);
        assertThat(d.problems("tool N { use: file  \"header.X-A\": \"b\" }")).anyMatch(p -> p.contains("unknown option header.X-A"));
        assertThat(d.problems("tool N { use: http  base_url: \"https://a.example.com\"  \"header.X-A\": \"b\" }")).isEmpty();
        assertThat(d.problems("tool N { use: http  base_url: \"https://a.example.com\"  \"header.\": \"b\" }")).anyMatch(p -> p.contains("unknown option"));
        assertThat(d.problems("tool N { use: http  base_url: \"https://a.example.com\"  \"header.X-Token\": env.MISSING }")).anyMatch(p -> p.contains("MISSING"));
    }

    @Test
    @Tag("V1.2")
    void unknownOptionsListWhatTheKindTakes() {
        Declared d = new Declared(Map.of(), dir);
        assertThat(d.problems("tool N { use: file  colour: red }")).anyMatch(p -> p.contains("unknown option colour") && p.contains("mode") && p.contains("root"));
    }

    @Test
    @Tag("V1.9")
    @Tag("V1.8")
    void descriptionIsAcceptedByExistingKindsToo() throws Exception {
        Tool t = new Declared(Map.of(), dir).create("tool Calc { use: calculator  description: \"Use for money only.\" }", new RecordingEffects());
        assertThat(t.getName()).isEqualTo("Calc");
        assertThat(t.getDescription()).endsWith("Use for money only.");
        assertThat(t.execute(Map.of("expression", "2+2"))).contains("4");
    }

    // ── V1.6: whatever an agent sends, a generic tool answers with text ──────────────────────

    @Test
    @Tag("V1.6")
    @Tag("F2")
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

    @SuppressWarnings("unused")
    private static Set<String> unused() throws IOException {
        return Set.of();
    }
}
