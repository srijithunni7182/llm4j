package io.github.llm4j.loom.trigger.system;

import java.util.List;
import java.util.regex.Pattern;

/** Quoting for the command lines the backends write. */
final class Quote {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");

    private Quote() { }

    /** POSIX shell: as is when safe, else single-quoted. */
    static String shell(String arg) {
        if (!arg.isEmpty() && SAFE.matcher(arg).matches()) return arg;
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    static String shell(List<String> argv) {
        return String.join(" ", argv.stream().map(Quote::shell).toList());
    }

    /** systemd ExecStart: double-quoted when needed. */
    static String systemd(String arg) {
        if (!arg.isEmpty() && SAFE.matcher(arg).matches()) return arg;
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Windows command line: double-quoted when needed. */
    static String windows(String arg) {
        if (!arg.isEmpty() && SAFE.matcher(arg).matches()) return arg;
        return "\"" + arg.replace("\"", "\\\"") + "\"";
    }

    static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
