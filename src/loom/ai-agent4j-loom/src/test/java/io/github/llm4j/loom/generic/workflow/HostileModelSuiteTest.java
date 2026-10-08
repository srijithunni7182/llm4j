package io.github.llm4j.loom.generic.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model that has been turned against its own tools. Every attack must come back as an {@code Error:}, change
 * nothing outside, and leave every secret out of what the model sees and what the run reports.
 */
class HostileModelSuiteTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "SECRETSECRET";

    @TempDir
    Path dir;
    MockWebServer server;
    Connection keepAlive;
    String dbUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        for (int i = 0; i < 50; i++) server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("fine"));
        server.start();
        Files.createDirectories(dir.resolve("notes/.hidden"));
        Files.writeString(dir.resolve("outside.md"), "OUTSIDE");
        Files.writeString(dir.resolve("notes/.env"), "HIDDEN");
        Files.writeString(dir.resolve("notes/.hidden/x.md"), "HIDDEN");
        Files.writeString(dir.resolve("notes/a.md"), "alpha");
        Files.writeString(dir.resolve("notes/data.bin"), "bin");
        Files.writeString(dir.resolve("canary.txt"), "alive");
        dbUrl = "jdbc:h2:mem:hostile" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(dbUrl, "sa", SECRET + "db");
        try (Statement s = keepAlive.createStatement()) {
            s.execute("CREATE TABLE people (id INT, name VARCHAR(50))");
            s.execute("INSERT INTO people VALUES (1, 'Asha'), (2, 'Ben')");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        keepAlive.close();
    }

    static final String SCRIPT = """
            tool Hook  { use: webhook  url: env.HOOK  format: json }
            tool Api   { use: http  base_url: env.API  auth_header: "Authorization"  auth_value: env.API_TOKEN  allow_private: true }
            tool Mail  { use: email  outbox: "outbox"  from: "digest@example.com"  allow_to: "*@example.com" }
            tool Files { use: file  root: "notes"  mode: readwrite  overwrite: true }
            tool Ops   { use: shell  allow: "echo"  unattended: true }
            tool Db    { use: sql  url: env.DB  user: env.DB_USER  password: env.DB_PASSWORD }
            agent Hooker { model: "m" tools: [Hook]  max_iterations: 60 }
            agent Caller { model: "m" tools: [Api]   max_iterations: 60 }
            agent Mailer { model: "m" tools: [Mail]  max_iterations: 60 }
            agent Clerk  { model: "m" tools: [Files] max_iterations: 60 }
            agent Runner { model: "m" tools: [Ops]   max_iterations: 60 }
            agent Analyst { model: "m" tools: [Db]   max_iterations: 60 }
            workflow Hooker()  { delegate "go" to Hooker -> r }
            workflow Caller()  { delegate "go" to Caller -> r }
            workflow Mailer()  { delegate "go" to Mailer -> r }
            workflow Clerk()   { delegate "go" to Clerk -> r }
            workflow Runner()  { delegate "go" to Runner -> r }
            workflow Analyst() { delegate "go" to Analyst -> r }
            """;

    /** The world a hostile tool call could change. */
    record World(Map<String, String> files, int requests, long rows) { }

    World world() throws IOException, SQLException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) all.filter(Files::isRegularFile)::iterator) files.put(dir.relativize(p).toString(), Files.readString(p));
        }
        long rows;
        try (Statement s = keepAlive.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM people")) {
            rs.next();
            rows = rs.getLong(1);
        }
        return new World(files, server.getRequestCount(), rows);
    }

    static String act(String tool, Map<String, Object> args) {
        try {
            return ScriptedRun.call(tool, JSON.writeValueAsString(args));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs the workflow named after the agent with the model making exactly these calls, then checks everything. */
    void attack(String workflow, String tool, List<Map<String, Object>> attacks) throws Exception {
        List<String> replies = new ArrayList<>();
        for (Map<String, Object> a : attacks) replies.add(act(tool, a));
        replies.add(ScriptedRun.done("finished"));
        ScriptedRun run = new ScriptedRun(dir).replies(replies.toArray(String[]::new));
        run.journal = RunJournal.inMemory();
        run.env.put("HOOK", server.url("/services/T0000/B0000/" + SECRET + "hook").toString().replace("http://", "http://"));
        run.env.put("API", server.url("/").toString());
        run.env.put("API_TOKEN", "tok-" + SECRET + "api");
        run.env.put("DB", dbUrl);
        run.env.put("DB_USER", "sa");
        run.env.put("DB_PASSWORD", SECRET + "db");
        World before = world();

        HarnessExecutor executor = run.executor(SCRIPT.replace("format: json }", "format: json  allow_http: true }"));
        executor.initialize();
        executor.executeWorkflow(workflow, Map.of());

        assertThat(world()).as("the world after " + attacks.size() + " hostile calls to " + tool).isEqualTo(before);
        List<io.github.llm4j.model.Message> last = run.requests.get(run.requests.size() - 1).getMessages();
        String scratchpad = last.get(last.size() - 1).getContent();
        assertThat(count(scratchpad, "Observation: Error:")).as("every attack was answered with an Error").isEqualTo(attacks.size());
        assertThat(count(scratchpad, "Observation:")).as("and nothing else was observed").isEqualTo(attacks.size());
        assertThat(run.seen()).as("what the model saw").doesNotContain(SECRET);
        assertThat(run.everything()).as("what the run reported").doesNotContain(SECRET);
    }

    static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    @Tag("H1")
    void webhookAttacks() throws Exception {
        attack("Hooker", "Hook", List.of(
                Map.of("text", "hi", "title", "x\r\nHost: evil.example"),
                Map.of("text", "hi", "title", "x\nBcc: evil"),
                Map.of("text", "x".repeat(25_000)),
                Map.of("text", ""),
                Map.of(),
                Map.of("text", List.of("a", "b"), "title", Map.of("k", "v"))));
    }

    @Test
    @Tag("H2")
    void httpAttacks() throws Exception {
        attack("Caller", "Api", List.of(
                Map.of("path", "https://evil.example/"), Map.of("path", "//evil.example/x"), Map.of("path", "/a/../../etc/passwd"),
                Map.of("path", "/x@evil.example"), Map.of("path", "/a%2e%2e/b"), Map.of("path", "/a?b=1#c"),
                Map.of("path", "/x", "method", "DELETE"), Map.of("path", "/x", "method", "TRACE"), Map.of("path", "/x", "method", "POST", "body", "x"),
                Map.of("path", "/x", "body", "a body on a GET"), Map.of("path", "/café"), Map.of("method", "GET")));
    }

    @Test
    @Tag("H3")
    void emailAttacks() throws Exception {
        List<Map<String, Object>> attacks = new ArrayList<>(List.of(
                Map.of("to", "a@evil.com", "subject", "s", "body", "b"),
                Map.of("to", "a@example.com\r\nBcc: evil@evil.com", "subject", "s", "body", "b"),
                Map.of("to", "a@example.com", "subject", "s\r\nBcc: evil@evil.com", "body", "b"),
                Map.of("to", "a@example.com", "subject", "s", "body", "b", "attach", ".env"),
                Map.of("to", "a@example.com", "subject", "s", "body", "b", "attach", "../../.env"),
                Map.of("subject", "s", "body", "b")));
        attacks.add(Map.of("to", java.util.stream.IntStream.range(0, 500).mapToObj(i -> "u" + i + "@example.com").toList(), "subject", "s", "body", "b"));
        attack("Mailer", "Mail", attacks);
        assertThat(dir.resolve("outbox")).satisfiesAnyOf(p -> assertThat(p).doesNotExist(), p -> {
            try (Stream<Path> files = Files.list(p)) {
                assertThat(files.toList()).isEmpty();
            }
        });
    }

    @Test
    @Tag("H4")
    void fileAttacks() throws Exception {
        attack("Clerk", "Files", List.of(
                Map.of("action", "read", "path", "../outside.md"), Map.of("action", "read", "path", "/etc/passwd"),
                Map.of("action", "read", "path", ".env"), Map.of("action", "read", "path", ".hidden/x.md"),
                Map.of("action", "write", "path", "../outside.md", "content", "PWNED"), Map.of("action", "write", "path", ".env", "content", "PWNED"),
                Map.of("action", "append", "path", ".hidden/x.md", "content", "PWNED"), Map.of("action", "read", "path", "data.bin"),
                Map.of("action", "write", "path", "run.sh", "content", "echo pwned"), Map.of("action", "delete", "path", "a.md"),
                Map.of("action", "write", "path", "x.md", "content", "a".repeat(2 * 1024 * 1024)), Map.of("action", "read")));
    }

    @Test
    @Tag("H5")
    void shellAttacks() throws Exception {
        attack("Runner", "Ops", List.of(
                Map.of("program", "/bin/sh", "args", List.of("-c", "rm canary.txt")), Map.of("program", "sh", "args", List.of("-c", "rm canary.txt")),
                Map.of("program", "../x"), Map.of("program", "echo; rm canary.txt"), Map.of("program", "echo", "args", List.of("a\nrm canary.txt")),
                Map.of("program", "echo", "args", "a; rm canary.txt"), Map.of("program", List.of("echo")), Map.of("cmd", "rm canary.txt"),
                Map.of("program", "rm", "args", List.of("canary.txt")), Map.of("program", "echo", "args", java.util.Collections.nCopies(65, "x"))));
    }

    @Test
    @Tag("H6")
    void sqlAttacks() throws Exception {
        attack("Analyst", "Db", List.of(
                Map.of("sql", "INSERT INTO people VALUES (9, 'Eve')"), Map.of("sql", "DELETE FROM people"), Map.of("sql", "DROP TABLE people"),
                Map.of("sql", "SELECT 1; DELETE FROM people"), Map.of("sql", "SELECT * INTO copy FROM people"),
                Map.of("sql", "WITH d AS (DELETE FROM people RETURNING *) SELECT * FROM d"), Map.of("sql", "UPDATE people SET name = 'x'"),
                Map.of("sql", "SELECT CSVREAD('/etc/passwd')"), Map.of("sql", "SELECT 1 /*! ; DROP TABLE people */"),
                Map.of("sql", "SELECT \\u0044ELETE FROM people"), Map.of("sql", "TRUNCATE TABLE people"), Map.of("sql", "CALL 1"),
                Map.of("sql", ""), Map.of("action", "drop")));
    }
}
