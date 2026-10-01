package io.github.llm4j.loom.tools.generic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a SQL statement may be run by a read-only tool: exactly one statement, starting with
 * SELECT, WITH or VALUES, and none of the keywords or functions that change things. It reads the statement as
 * tokens, skipping quoted text and comments, so {@code SELECT 'DELETE'} passes and {@code SELECT 1; DROP TABLE t}
 * doesn't.
 *
 * <p>Wherever databases disagree about what is quoted or commented, this guard reads the text as code, which
 * can only refuse more. It is a second layer: the connection is also read-only, and the database user should be.
 */
public final class SqlGuard {

    /** Databases read some quoting differently; dollar-quoted strings exist only in some. */
    public enum Dialect { STANDARD, DOLLAR_QUOTES }

    private static final Set<String> FIRST = Set.of("SELECT", "WITH", "VALUES");
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "INTO", "COPY", "LOCK", "ATTACH", "DETACH", "PRAGMA", "DO", "VACUUM",
            "REINDEX", "CLUSTER", "NOTIFY", "LISTEN", "UPSERT");
    /** Functions that read files, run commands, or change state from inside a SELECT. */
    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of("PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "LO_IMPORT",
            "LO_EXPORT", "DBLINK", "DBLINK_EXEC", "LOAD_FILE", "XP_CMDSHELL", "SET_CONFIG", "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND",
            "NEXTVAL", "SETVAL", "FILE_READ", "FILE_WRITE", "CSVREAD", "CSVWRITE", "LINK_SCHEMA");

    private SqlGuard() {}

    /** The dialect to use for a JDBC URL. */
    public static Dialect dialectFor(String jdbcUrl) {
        String u = jdbcUrl.toLowerCase(Locale.ROOT);
        return u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:cockroachdb:") || u.startsWith("jdbc:h2:")
                ? Dialect.DOLLAR_QUOTES : Dialect.STANDARD;
    }

    /** @throws ToolRefusal naming the rule the statement breaks */
    public static void check(String sql, Dialect dialect) {
        List<String> tokens = tokens(sql, dialect);
        if (tokens.isEmpty()) throw new ToolRefusal("the statement is empty");
        int end = tokens.size();
        if (tokens.get(end - 1).equals(";")) end--;
        for (int i = 0; i < end; i++) {
            if (tokens.get(i).equals(";")) throw new ToolRefusal("only one statement is allowed");
        }
        if (end == 0) throw new ToolRefusal("the statement is empty");
        if (!FIRST.contains(tokens.get(0))) throw new ToolRefusal("only SELECT, WITH or VALUES statements are allowed");
        for (int i = 0; i < end; i++) {
            String t = tokens.get(i);
            if (FORBIDDEN.contains(t)) throw new ToolRefusal("the statement uses " + t + ", which this tool doesn't allow (it only reads)");
            if (DANGEROUS_FUNCTIONS.contains(t)) throw new ToolRefusal("the statement calls " + t + ", which this tool doesn't allow");
        }
    }

    /** The words, numbers (as {@code #}) and semicolons outside quotes and comments; words upper-cased. */
    static List<String> tokens(String sql, Dialect dialect) {
        List<String> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                // A line comment. (Without the space some databases read it as arithmetic, so it stays visible.)
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*' && !(i + 2 < n && sql.charAt(i + 2) == '!')) {
                i = skipBlockComment(sql, i);
            } else if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '$' && dialect == Dialect.DOLLAR_QUOTES && dollarTag(sql, i) != null) {
                i = skipDollarQuoted(sql, i, dollarTag(sql, i));
            } else if (Character.isDigit(c)) {
                // A number is a token too, so a statement can't start with one and still count as starting with SELECT.
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.' || sql.charAt(i) == '_')) i++;
                out.add("#");
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                out.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                if (c == ';') out.add(";");
                i++;
            }
        }
        return out;
    }

    private static int skipBlockComment(String s, int from) {
        int depth = 0;
        int i = from;
        while (i < s.length()) {
            if (s.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (s.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) return i;
            } else {
                i++;
            }
        }
        throw new ToolRefusal("the statement has an unterminated comment");
    }

    /** Skips a quoted string or identifier; a doubled quote inside is an escaped quote. Backslashes are not escapes. */
    private static int skipQuoted(String s, int from, char quote) {
        int i = from + 1;
        while (i < s.length()) {
            if (s.charAt(i) == quote) {
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new ToolRefusal("the statement has an unterminated quote");
    }

    /** {@code $$} or {@code $tag$} at position i, or null (a parameter like {@code $1} is not a tag). */
    private static String dollarTag(String s, int i) {
        int j = i + 1;
        if (j < s.length() && s.charAt(j) == '$') return "$$";
        if (j >= s.length() || !(Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) return null;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
        return j < s.length() && s.charAt(j) == '$' ? s.substring(i, j + 1) : null;
    }

    private static int skipDollarQuoted(String s, int from, String tag) {
        int close = s.indexOf(tag, from + tag.length());
        if (close < 0) throw new ToolRefusal("the statement has an unterminated $-quoted string");
        return close + tag.length();
    }
}
