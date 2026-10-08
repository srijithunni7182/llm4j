package io.github.llm4j.tools;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The path part of an HTTP request an agent asks for. The agent may choose only a path below the tool's
 * base URL, so anything that could climb out, change the host, or smuggle in a query or fragment is refused.
 */
public final class RequestPath {

    private RequestPath() {}

    /** @return the path unchanged if it is safe, else throws */
    public static String validate(String path) {
        if (path == null || path.isEmpty()) throw new ToolRefusal("path is required");
        if (path.charAt(0) != '/') throw new ToolRefusal("path must start with / (it is relative to the tool's base URL)");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new ToolRefusal("path contains a control character");
            if (c == '\\' || c == '?' || c == '#' || c == '@' || c == ' ') {
                throw new ToolRefusal("path contains '" + c + "' (use query for query parameters; paths can't carry a host or fragment)");
            }
            if (c > 0x7e) throw new ToolRefusal("path must be plain ASCII (encode other characters as %XX)");
        }
        if (path.contains("//")) throw new ToolRefusal("path contains an empty segment (//)");
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
            throw new ToolRefusal("path contains an encoded dot, slash or NUL");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) throw new ToolRefusal("path contains a . or .. segment");
        }
        return path;
    }

    /** A set of path patterns: {@code *} matches within one segment, {@code **} across segments. */
    public static final class Patterns {

        private final List<Pattern> compiled;
        private final boolean any;

        public Patterns(List<String> patterns) {
            this.any = patterns.isEmpty();
            this.compiled = patterns.stream().map(Patterns::compile).toList();
        }

        public boolean matches(String path) {
            return any || compiled.stream().anyMatch(p -> p.matcher(path).matches());
        }

        private static Pattern compile(String glob) {
            if (!glob.startsWith("/")) throw new OptionException("allow_paths: " + glob + " must start with /");
            StringBuilder re = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else if (c == '*') {
                    re.append("[^/]*");
                } else {
                    re.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.compile(re.toString());
        }
    }
}
