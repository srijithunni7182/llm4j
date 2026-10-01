package io.github.llm4j.tools.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.RecordingDriver;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlToolTest {

    @TempDir
    Path dir;
    RecordingEffects ctx;
    Declared declared;
    Map<String, String> env;
    String url;
    Connection keepAlive; // keeps the in-memory database alive between calls

    @BeforeEach
    void setUp() throws SQLException {
        String name = "t" + System.nanoTime();
        url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        env = new HashMap<>(Map.of("DB_URL", url, "DB_USER", "sa", "DB_PASSWORD", ""));
        keepAlive = DriverManager.getConnection(url, "sa", "");
        exec("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100), bio CLOB, note VARCHAR(10000))");
        exec("INSERT INTO people VALUES (1, 'Asha', NULL, 'first'), (2, 'Ben', NULL, 'second'), (3, 'Chloe', NULL, 'third')");
        exec("CREATE VIEW names AS SELECT name FROM people");
        ctx = new RecordingEffects();
        declared = new Declared(env, dir);
    }

    void exec(String sql) throws SQLException {
        try (Statement s = keepAlive.createStatement()) {
            s.execute(sql);
        }
    }

    long count(String table) throws SQLException {
        try (Statement s = keepAlive.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    Tool tool(String options) throws Exception {
        return declared.create("tool Db { use: sql  url: env.DB_URL  user: env.DB_USER  " + options + " }", ctx);
    }

    static String run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            throw new AssertionError("a generic tool threw instead of returning an Error: " + e, e);
        }
    }

    static Map<String, Object> query(String sql, Object... params) {
        return Map.of("sql", sql, "params", List.of(params));
    }

    // ── V9.1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V9.1")
    void aSelectWithBoundParametersReturnsATable() throws Exception {
        String result = run(tool(""), query("SELECT id, name FROM people WHERE id > ? ORDER BY id", 1));
        assertThat(result).isEqualTo("ID | NAME\n---------\n2 | Ben\n3 | Chloe");
    }

    @Test
    @Tag("V9.1")
    void jsonAndCsvFormats() throws Exception {
        assertThat(run(tool("format: json"), query("SELECT id, name FROM people WHERE id = ?", 1))).isEqualTo("[{\"ID\":1,\"NAME\":\"Asha\"}]");
        exec("INSERT INTO people VALUES (4, 'Dee, \"D\"', NULL, NULL)");
        assertThat(run(tool("format: csv"), query("SELECT id, name, note FROM people WHERE id = ?", 4))).isEqualTo("ID,NAME,NOTE\n4,\"Dee, \"\"D\"\"\",");
        assertThat(run(tool(""), query("SELECT id, note FROM people WHERE id = ?", 4))).contains("NULL");
    }

    @Test
    @Tag("V9.1")
    @Tag("H6")
    void valuesReachTheDatabaseOnlyAsBoundParameters() throws Exception {
        String evil = "x'; DROP TABLE people; --";
        String result = run(tool(""), query("SELECT ? AS v", evil));

        assertThat(result).contains(evil);
        assertThat(count("people")).isEqualTo(3);
        assertThat(run(tool(""), query("SELECT name FROM people WHERE name = ?", "Asha' OR '1'='1"))).contains("(no rows)");
    }

    @Test
    @Tag("V9.1")
    void badQueriesAndParamsAreErrorsWithoutTheUrl() throws Exception {
        Tool t = tool("");
        assertThat(run(t, query("SELECT * FROM missing_table"))).startsWith("Error: the database refused the query").doesNotContain(url);
        assertThat(run(t, query("SELECT ? + ?", 1))).startsWith("Error:");
        assertThat(run(t, Map.of("sql", "SELECT 1", "params", "not a list"))).contains("params must be a list");
        assertThat(run(t, Map.of("action", "drop"))).contains("action must be");
        assertThat(run(t, Map.of())).contains("sql is required");
    }

    // ── V9.3 / H6 refusals reach the tool ────────────────────────────────────────────────────

    @Test
    @Tag("V9.3")
    @Tag("H6")
    void writesAndMultipleStatementsAreRefusedAndTheDataIsUntouched() throws Exception {
        Tool t = tool("");
        List<String> attacks = List.of("INSERT INTO people VALUES (9, 'Eve', NULL, NULL)", "DELETE FROM people", "DROP TABLE people",
                "UPDATE people SET name = 'x'", "SELECT 1; DELETE FROM people", "SELECT * INTO copy FROM people",
                "WITH d AS (DELETE FROM people RETURNING *) SELECT * FROM d", "TRUNCATE TABLE people", "CALL 1", "SELECT CSVREAD('/etc/passwd')",
                "SELECT FILE_READ('/etc/passwd')", "SELECT NEXTVAL('s')", "SELECT 1 /*! ; DROP TABLE people */", "ALTER TABLE people DROP COLUMN name",
                "SELECT DELETE FROM people", "\u0000DROP TABLE people");
        for (String attack : attacks) assertThat(run(t, query(attack))).as(attack).startsWith("Error:");

        assertThat(count("people")).isEqualTo(3);
        assertThat(run(t, query("SELECT name FROM people WHERE id = 1"))).contains("Asha");
    }

    // ── V9.4 read-only connection, V9.8 one connection per call ──────────────────────────────

    @Test
    @Tag("V9.4")
    void everyCallOpensAReadOnlyConnectionAndClosesIt() throws Exception {
        RecordingDriver.register();
        RecordingDriver.events.clear();
        env.put("DB_URL", RecordingDriver.PREFIX + "mem:" + url.substring("jdbc:h2:mem:".length()));
        Tool t = tool("");

        run(t, query("SELECT 1"));
        run(t, Map.of("action", "schema"));

        assertThat(RecordingDriver.events).containsExactly("open", "readOnly=true", "autoCommit=true", "close",
                "open", "readOnly=true", "autoCommit=true", "close");
    }

    // ── V9.5 limits ──────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V9.5")
    void maxRowsCutsTheResultAndSaysSo() throws Exception {
        String two = run(tool("max_rows: 2"), query("SELECT id FROM people ORDER BY id"));
        assertThat(two).isEqualTo("ID\n---\n1\n2\n(only the first 2 rows are shown; there are more)");
        assertThat(run(tool("max_rows: 3"), query("SELECT id FROM people"))).doesNotContain("more");
        assertThat(declared.problems("tool Db { use: sql  url: env.DB_URL  max_rows: 1001 }")).anyMatch(p -> p.contains("max_rows"));
        assertThat(declared.problems("tool Db { use: sql  url: env.DB_URL  max_rows: 0 }")).anyMatch(p -> p.contains("max_rows"));
    }

    @Test
    @Tag("V9.5")
    void longCellsAreCutAndTheWholeResultIsCappedInBytes() throws Exception {
        exec("INSERT INTO people VALUES (5, 'Long', NULL, '" + "x".repeat(5000) + "')");
        String result = run(tool(""), query("SELECT note FROM people WHERE id = 5"));
        assertThat(result).contains("x".repeat(1000) + "…").doesNotContain("x".repeat(1001));
        assertThat(run(tool("max_bytes: 100"), query("SELECT name, note FROM people"))).contains("[cut:");
    }

    // ── V9.6 timeout ─────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V9.6")
    void aQueryThatRunsTooLongIsCancelled() throws Exception {
        long t0 = System.nanoTime();
        String result = run(tool("timeout: 1s"), query("SELECT COUNT(*) FROM SYSTEM_RANGE(1, 200000000) a, SYSTEM_RANGE(1, 200000000) b"));
        assertThat(result).startsWith("Error: the database refused the query");
        assertThat((System.nanoTime() - t0) / 1_000_000_000L).isLessThan(20);
    }

    // ── V9.7 schema ──────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V9.7")
    void schemaListsTablesAndColumnsOrOneTable() throws Exception {
        Tool t = tool("");
        String all = run(t, Map.of("action", "schema"));
        assertThat(all).contains("PUBLIC.PEOPLE(ID INTEGER, NAME CHARACTER VARYING").contains("PUBLIC.NAMES(NAME CHARACTER VARYING");
        assertThat(all).doesNotContain("INFORMATION_SCHEMA");
        assertThat(run(t, Map.of("action", "schema", "table", "PEOPLE"))).startsWith("PUBLIC.PEOPLE(").doesNotContain("NAMES");
        assertThat(run(t, Map.of("action", "schema", "table", "NOPE"))).isEqualTo("no table named NOPE");
    }

    // ── V9.8 drivers and secrets ─────────────────────────────────────────────────────────────

    @Test
    @Tag("V9.8")
    void aUrlWithoutADriverIsALoadErrorNamingOnlyTheScheme() {
        env.put("ORACLE", "jdbc:oracle:thin:@//secret-host:1521/service");
        List<String> problems = declared.problems("tool Db { use: sql  url: env.ORACLE }");
        assertThat(problems).anyMatch(p -> p.contains("no JDBC driver is installed for jdbc:oracle"));
        assertThat(String.join(" ", problems)).doesNotContain("secret-host");
        env.put("NOTJDBC", "postgres://host/db");
        assertThat(declared.problems("tool Db { use: sql  url: env.NOTJDBC }")).anyMatch(p -> p.contains("must be a JDBC URL"));
        assertThat(declared.problems("tool Db { use: sql  url: \"jdbc:h2:mem:x\" }")).anyMatch(p -> p.contains("url must come from the environment"));
        assertThat(declared.problems("tool Db { use: sql  url: env.DB_URL  password: \"literal-password\" }")).anyMatch(p -> p.contains("password must come from the environment"));
    }

    @Test
    @Tag("V9.8")
    @Tag("V1.5")
    void aFailedLoginNeverShowsTheUrlOrPassword() throws Exception {
        env.put("DB_PASSWORD", "wrong-PASSWORD-4321");
        env.put("DB_URL", url.replace("DB_CLOSE_DELAY=-1", "DB_CLOSE_DELAY=-1;PASSWORD=" + "hidden-in-url-9999"));
        Tool t = declared.create("tool Db { use: sql  url: env.DB_URL  user: env.DB_USER  password: env.DB_PASSWORD }", ctx);

        String result = run(t, query("SELECT 1"));

        assertThat(result).startsWith("Error:").doesNotContain("PASSWORD-4321").doesNotContain("hidden-in-url-9999").doesNotContain(env.get("DB_URL"));
        assertThat(ctx.everything()).doesNotContain("PASSWORD-4321").doesNotContain("hidden-in-url-9999");
    }

    // ── V9.9 not a side effect ───────────────────────────────────────────────────────────────

    @Test
    @Tag("V9.9")
    void queriesLeaveNoEffectRecords() throws Exception {
        Tool t = tool("");
        run(t, query("SELECT 1"));
        run(t, Map.of("action", "schema"));
        assertThat(ctx.journal().all()).isEmpty();
        assertThat(ctx.audited("tool_call")).hasSize(2);
        assertThat(ctx.audited("tool_effect")).isEmpty();
        assertThat(ctx.audited("tool_call").get(0).data()).containsEntry("target", "query").doesNotContainKey("sql");
        assertThat(ctx.everything()).doesNotContain("SELECT 1");
    }
}
