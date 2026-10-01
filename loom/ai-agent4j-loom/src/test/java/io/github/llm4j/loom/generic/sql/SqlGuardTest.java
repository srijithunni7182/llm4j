package io.github.llm4j.loom.generic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.generic.support.Fuzz;
import io.github.llm4j.loom.tools.generic.SqlGuard;
import io.github.llm4j.loom.tools.generic.SqlGuard.Dialect;
import io.github.llm4j.loom.tools.generic.ToolRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select * from t where n = 'DELETE'",
            "SELECT * FROM t WHERE note = 'it''s; DROP TABLE x'",
            "WITH a AS (SELECT 1) SELECT * FROM a",
            "SELECT created_at, updated_by, insertion_point FROM t",
            "SELECT 1 -- DROP TABLE t\n",
            "SELECT /* INSERT INTO x */ 1",
            "SELECT /* nested /* DELETE */ still comment */ 1",
            "SELECT \"delete\" FROM \"drop\"",
            "SELECT `update` FROM t",
            "SELECT 1;",
            "  SELECT 1 ;  ",
            "VALUES (1), (2)",
            "SELECT replace(name, 'a', 'b') FROM t",
            "SELECT a FROM t WHERE b = ? AND c = ?",
            "SELECT $1, $2"})
    @Tag("V9.2")
    void harmlessStatementsPass(String sql) {
        assertThatCode(() -> SqlGuard.check(sql, Dialect.STANDARD)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT $$ INSERT INTO x $$",
            "SELECT $tag$ DELETE FROM x; $tag$ AS body",
            "SELECT 'a', $q$ it's DROP $q$"})
    @Tag("V9.2")
    void dollarQuotedTextIsTextWhereTheDatabaseSupportsIt(String sql) {
        assertThatCode(() -> SqlGuard.check(sql, Dialect.DOLLAR_QUOTES)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT $$ x; DROP TABLE t; $$", "SELECT $tag$ DELETE FROM t $tag$"})
    @Tag("V9.3")
    @Tag("H6")
    void withoutDollarQuotingTheTextIsReadAsCode(String sql) {
        // A database that doesn't know $$ would run what is inside, so the guard must see it.
        assertThatThrownBy(() -> SqlGuard.check(sql, Dialect.STANDARD)).isInstanceOf(ToolRefusal.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t VALUES (1)", "DELETE FROM t", "UPDATE t SET a = 1", "DROP TABLE t", "ALTER TABLE t ADD c INT",
            "CREATE TABLE x (a INT)", "TRUNCATE t", "GRANT ALL ON t TO u", "REVOKE ALL ON t FROM u", "CALL p()", "EXEC p", "COPY t TO '/tmp/x'",
            "MERGE INTO t USING s ON 1=1 WHEN MATCHED THEN DELETE",
            "SELECT 1; SELECT 2", "SELECT 1; DROP TABLE t", "SELECT 1;DROP TABLE t", "SELECT 1; -- c\n DELETE FROM t",
            "SELECT * INTO x FROM t", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x",
            "/* c */ UPDATE t SET a = 1", "-- c\nDELETE FROM t",
            "SELECT * FROM t FOR UPDATE",
            "", "   ", "-- only a comment", "/* only */", ";", ";;",
            "EXPLAIN SELECT 1", "SET x = 1", "BEGIN", "PRAGMA writable_schema = 1", "SHOW TABLES",
            "SELECT pg_read_file('/etc/passwd')", "SELECT lo_import('/etc/passwd')", "SELECT nextval('s')", "SELECT CSVREAD('x')",
            "SELECT 'unterminated", "SELECT /* unterminated", "SELECT \"unterminated",
            "SELECT 1 /*! ; DROP TABLE t */",
            "SELECT 1--x; DROP TABLE t",
            "SELECT 1 # c\n; DROP TABLE t", "1 SELECT 1", "123", "1; SELECT 1"})
    @Tag("V9.3")
    @Tag("H6")
    void statementsThatCouldChangeThingsOrAreNotOneSelectAreRefused(String sql) {
        assertThatThrownBy(() -> SqlGuard.check(sql, Dialect.STANDARD)).isInstanceOf(ToolRefusal.class);
        assertThatThrownBy(() -> SqlGuard.check(sql, Dialect.DOLLAR_QUOTES)).isInstanceOf(ToolRefusal.class);
    }

    @Test
    @Tag("V9.3")
    void aRefusalSaysWhichRule() {
        assertThatThrownBy(() -> SqlGuard.check("DELETE FROM t", Dialect.STANDARD)).hasMessageContaining("only SELECT, WITH or VALUES");
        assertThatThrownBy(() -> SqlGuard.check("SELECT 1; SELECT 2", Dialect.STANDARD)).hasMessageContaining("only one statement");
        assertThatThrownBy(() -> SqlGuard.check("SELECT * INTO x FROM t", Dialect.STANDARD)).hasMessageContaining("INTO");
        assertThatThrownBy(() -> SqlGuard.check("SELECT pg_read_file('x')", Dialect.STANDARD)).hasMessageContaining("PG_READ_FILE");
        assertThatThrownBy(() -> SqlGuard.check("", Dialect.STANDARD)).hasMessageContaining("empty");
        assertThatThrownBy(() -> SqlGuard.check("SELECT 'x", Dialect.STANDARD)).hasMessageContaining("unterminated quote");
    }

    @Test
    @Tag("V9.2")
    void theDialectFollowsTheJdbcUrl() {
        assertThat(SqlGuard.dialectFor("jdbc:postgresql://h/db")).isEqualTo(Dialect.DOLLAR_QUOTES);
        assertThat(SqlGuard.dialectFor("jdbc:h2:mem:x")).isEqualTo(Dialect.DOLLAR_QUOTES);
        assertThat(SqlGuard.dialectFor("jdbc:mysql://h/db")).isEqualTo(Dialect.STANDARD);
        assertThat(SqlGuard.dialectFor("jdbc:sqlserver://h")).isEqualTo(Dialect.STANDARD);
    }

    // ── F1: the guard agrees with an oracle built by construction ────────────────────────────

    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "INTO", "COPY", "LOCK", "ATTACH", "DETACH", "PRAGMA", "DO", "VACUUM",
            "REINDEX", "CLUSTER", "NOTIFY", "LISTEN", "UPSERT", "PG_READ_FILE", "NEXTVAL", "CSVREAD");
    private static final List<String> CODE_WORDS = List.of("SELECT", "FROM", "WHERE", "AND", "t", "a", "1", "AS", "WITH", "VALUES",
            "created_at", "updated_by", "insertion", "deleted", "dropdown", "INSERT", "DELETE", "DROP", "INTO", "CREATE", "UPDATE",
            "CALL", "COPY", "nextval", "pg_read_file", "x", "COUNT(*)", ",", "=", "(", ")");

    @Test
    @Tag("F1")
    void generatedStatementsAreClassifiedExactlyAsTheirConstructionSays() {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        for (Dialect dialect : Dialect.values()) {
            Fuzz.run("sql-guard-" + dialect, random -> {
                List<String> code = new ArrayList<>(); // what is really code, by construction
                StringBuilder sql = new StringBuilder();
                int parts = 1 + random.nextInt(9);
                for (int i = 0; i < parts; i++) {
                    switch (random.nextInt(7)) {
                        case 0, 1, 2 -> {
                            String w = CODE_WORDS.get(random.nextInt(CODE_WORDS.size()));
                            sql.append(w).append(' ');
                            addCodeTokens(code, w);
                        }
                        case 3 -> sql.append(quoted(random, '\'')).append(' ');
                        case 4 -> sql.append(quoted(random, '"')).append(' ');
                        case 5 -> sql.append(random.nextBoolean() ? "-- " + noise(random) + "\n" : "/* " + noise(random) + " */ ");
                        default -> {
                            if (dialect == Dialect.DOLLAR_QUOTES) {
                                sql.append("$$ ").append(noise(random)).append(" $$ ");
                            } else if (random.nextBoolean()) {
                                sql.append("; ");
                                code.add(";");
                            }
                        }
                    }
                }
                boolean expectAccept = expected(code);
                boolean accept;
                try {
                    SqlGuard.check(sql.toString(), dialect);
                    accept = true;
                } catch (ToolRefusal r) {
                    accept = false;
                }
                assertThat(accept).as("statement: " + sql + " | code tokens: " + code).isEqualTo(expectAccept);
                (accept ? accepted : refused).incrementAndGet();
            });
        }
        assertThat(accepted.get()).as("generated statements accepted").isGreaterThan(Fuzz.iterations() / 10);
        assertThat(refused.get()).as("generated statements refused").isGreaterThan(Fuzz.iterations() / 10);
    }

    private static void addCodeTokens(List<String> code, String word) {
        if (word.equals(",") || word.equals("=") || word.equals("(") || word.equals(")")) return;
        if (word.equals("1")) code.add("#"); // a number
        else code.add(word.equals("COUNT(*)") ? "COUNT" : word.toUpperCase());
    }

    /** The verdict the rules give for the real code tokens: one SELECT/WITH/VALUES statement, nothing forbidden. */
    private static boolean expected(List<String> code) {
        List<String> tokens = new ArrayList<>(code);
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals(";")) tokens.remove(tokens.size() - 1);
        if (tokens.isEmpty() || tokens.contains(";")) return false;
        if (!Set.of("SELECT", "WITH", "VALUES").contains(tokens.get(0))) return false;
        return tokens.stream().noneMatch(FORBIDDEN::contains);
    }

    /** Text inside quotes or comments that mentions every dangerous thing, with doubled quotes. */
    private static String noise(Random random) {
        String[] bits = {"DROP TABLE t;", "DELETE", "INSERT INTO", "; SELECT", "-- x", "created", "'' quote", "UPDATE"};
        StringBuilder sb = new StringBuilder();
        for (int i = random.nextInt(4); i >= 0; i--) sb.append(bits[random.nextInt(bits.length)]).append(' ');
        return sb.toString().replace("*/", "").replace("$$", "");
    }

    private static String quoted(Random random, char quote) {
        String body = noise(random).replace(String.valueOf(quote), "").replace("-- x", "--x");
        return quote + body + quote;
    }
}
