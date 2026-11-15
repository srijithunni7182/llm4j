package io.github.llm4j.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The author's command line and the typed parameters it is filled from (phase 36c, D9). The template
 * is split once, at load, into an argument list (double quotes group words); a placeholder {@code
 * {name}} is filled inside one argument, and a value is never split, re-parsed or interpreted, so a
 * value with spaces, {@code ;}, {@code --flag}, {@code $(…)} or a newline is still one argument. The
 * placeholders belong to this class: the template text is never passed through any other replacement.
 */
final class CommandTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** One parameter's type. */
    sealed interface Param permits PathIn, PathOut, IntRange, NumberRange, EnumOf, Text, Flag {
        /** The text the value becomes, or throws {@link ToolRefusal} naming the rule it broke. */
        String render(String name, Object value, Path workspace);

        /** Whether the parameter writes a file. */
        default boolean writes() {
            return false;
        }
    }

    record PathIn() implements Param {
        public String render(String name, Object value, Path workspace) {
            Path p = jail(name, value, workspace);
            if (!Files.exists(p)) throw new ToolRefusal(name + ": " + value + " does not exist");
            return notAnOption(workspace.toAbsolutePath().normalize().relativize(p).toString());
        }
    }

    record PathOut() implements Param {
        public String render(String name, Object value, Path workspace) {
            return notAnOption(workspace.toAbsolutePath().normalize().relativize(jail(name, value, workspace)).toString());
        }

        @Override
        public boolean writes() {
            return true;
        }
    }

    record IntRange(long min, long max) implements Param {
        public String render(String name, Object value, Path workspace) {
            String t = String.valueOf(value);
            if (!(value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) && !t.matches("-?\\d+"))
                throw new ToolRefusal(name + " must be a whole number");
            long v = value instanceof Number n ? n.longValue() : Long.parseLong(t);
            if (v < min || v > max) throw new ToolRefusal(name + " must be in " + min + ".." + max);
            return Long.toString(v);
        }
    }

    record NumberRange(double min, double max) implements Param {
        public String render(String name, Object value, Path workspace) {
            String t = String.valueOf(value);
            double v;
            try {
                v = value instanceof Number n ? n.doubleValue() : Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw new ToolRefusal(name + " must be a number");
            }
            if (Double.isNaN(v) || v < min || v > max) throw new ToolRefusal(name + " must be in " + trim(min) + ".." + trim(max));
            return trim(v);
        }
    }

    record EnumOf(List<String> allowed) implements Param {
        public String render(String name, Object value, Path workspace) {
            String t = String.valueOf(value);
            if (!allowed.contains(t)) throw new ToolRefusal(name + " must be one of " + String.join(", ", allowed));
            return t;
        }
    }

    record Text(Pattern pattern, int max, boolean allowDash) implements Param {
        public String render(String name, Object value, Path workspace) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) throw new ToolRefusal(name + " must be text");
            String t = String.valueOf(value);
            if (t.length() > max) throw new ToolRefusal(name + " must be at most " + max + " characters");
            if (!allowDash && t.startsWith("-")) throw new ToolRefusal(name + " must not start with - (the program would read it as an option); the author can allow it with allow_dash");
            if (pattern != null && !pattern.matcher(t).matches()) throw new ToolRefusal(name + " does not match the pattern " + pattern.pattern());
            return t;
        }
    }

    record Flag(String whenTrue) implements Param {
        public String render(String name, Object value, Path workspace) {
            boolean on = value instanceof Boolean b ? b : "true".equals(String.valueOf(value));
            if (!on && !"false".equals(String.valueOf(value)) && !(value instanceof Boolean))
                throw new ToolRefusal(name + " must be true or false");
            return on ? whenTrue : "";
        }
    }

    private static final int DEFAULT_TEXT_MAX = 1000;

    private final List<String> arguments;
    private final Map<String, Param> params;

    private CommandTemplate(List<String> arguments, Map<String, Param> params) {
        this.arguments = arguments;
        this.params = params;
    }

    /**
     * @param run the template
     * @param specs {@code param.<name>} option values by name
     * @throws OptionException for a template or type that cannot be used
     */
    static CommandTemplate parse(String run, Map<String, String> specs) {
        List<String> words = split(run);
        if (words.isEmpty()) throw new OptionException("run: is empty");
        if (PLACEHOLDER.matcher(words.get(0)).find())
            throw new OptionException("run: the program is the author's, so it cannot be a placeholder (" + words.get(0) + ")");
        Map<String, Param> params = new LinkedHashMap<>();
        specs.forEach((name, spec) -> params.put(name, param(name, spec)));
        for (String word : words) {
            Matcher m = PLACEHOLDER.matcher(word);
            while (m.find())
                if (!params.containsKey(m.group(1)))
                    throw new OptionException("run: {" + m.group(1) + "} has no param." + m.group(1) + " saying what it is");
        }
        return new CommandTemplate(words, params);
    }

    String program() {
        return arguments.get(0);
    }

    Map<String, Param> params() {
        return params;
    }

    boolean writes() {
        return params.values().stream().anyMatch(Param::writes);
    }

    /** The arguments after the program, filled from {@code given}, already checked for completeness. */
    List<String> fill(Map<String, Object> given, Path workspace) {
        Set<String> known = params.keySet();
        for (String name : given.keySet())
            if (!known.contains(name)) throw new ToolRefusal("unknown argument " + name + " (it takes " + String.join(", ", known) + ")");
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Param> e : params.entrySet()) {
            Object v = given.get(e.getKey());
            if (v == null) {
                if (e.getValue() instanceof Flag) {
                    values.put(e.getKey(), "");
                    continue;
                }
                throw new ToolRefusal(e.getKey() + " is required");
            }
            values.put(e.getKey(), e.getValue().render(e.getKey(), v, workspace));
        }
        List<String> out = new ArrayList<>();
        for (String word : arguments.subList(1, arguments.size())) {
            Matcher m = PLACEHOLDER.matcher(word);
            if (!m.find()) {
                out.add(word);
                continue;
            }
            boolean wholeFlag = word.matches("\\{[A-Za-z_][A-Za-z0-9_]*}") && params.get(word.substring(1, word.length() - 1)) instanceof Flag;
            StringBuilder filled = new StringBuilder();
            m.reset();
            int last = 0;
            while (m.find()) {
                filled.append(word, last, m.start()).append(values.get(m.group(1)));
                last = m.end();
            }
            filled.append(word.substring(last));
            if (wholeFlag && filled.length() == 0) continue; // a flag that is off adds nothing
            out.add(filled.toString());
        }
        return out;
    }

    // ── parsing ──────────────────────────────────────────────────────────────────────────────

    static List<String> split(String run) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : run.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any || cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
            }
        }
        if (quoted) throw new OptionException("run: has an opening quote with no closing one");
        if (any || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static Param param(String name, String spec) {
        String[] t = spec.strip().split("\\s+", 2);
        String rest = t.length > 1 ? t[1].strip() : "";
        try {
            switch (t[0]) {
                case "path" -> {
                    if (rest.equals("in")) return new PathIn();
                    if (rest.equals("out")) return new PathOut();
                }
                case "int" -> {
                    String[] r = range(rest);
                    return new IntRange(Long.parseLong(r[0]), Long.parseLong(r[1]));
                }
                case "number" -> {
                    String[] r = range(rest);
                    return new NumberRange(Double.parseDouble(r[0]), Double.parseDouble(r[1]));
                }
                case "enum" -> {
                    List<String> values = List.of(rest.split("\\|"));
                    if (rest.isBlank() || values.stream().anyMatch(String::isBlank)) break;
                    return new EnumOf(values);
                }
                case "string" -> {
                    Matcher p = Pattern.compile("pattern=(\\S+)").matcher(rest);
                    Matcher mx = Pattern.compile("max=(\\d+)").matcher(rest);
                    return new Text(p.find() ? Pattern.compile(p.group(1)) : null, mx.find() ? Integer.parseInt(mx.group(1)) : DEFAULT_TEXT_MAX, rest.contains("allow_dash"));
                }
                case "bool" -> {
                    List<String> text = split(rest);
                    if (text.size() == 1) return new Flag(text.get(0));
                }
                default -> {
                    // falls to the error below
                }
            }
        } catch (RuntimeException e) {
            // a number that is not one, a pattern that does not compile
        }
        throw new OptionException("param." + name + ": " + spec + " is not path in, path out, int a..b, number a..b, enum a|b|c, string [pattern=…] [max=N] or bool \"text\"");
    }

    private static String[] range(String text) {
        String[] r = text.split("\\.\\.", 2);
        if (r.length != 2) throw new IllegalArgumentException(text);
        return r;
    }

    /** A path that starts with a dash is written {@code ./-x}, so a program does not read it as an option. */
    private static String notAnOption(String path) {
        return path.startsWith("-") ? "./" + path : path;
    }

    private static String trim(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    private static Path jail(String name, Object value, Path workspace) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) throw new ToolRefusal(name + " must be a path");
        try {
            return SafePaths.inside(workspace, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new ToolRefusal(name + ": " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return String.join(" ", arguments).toLowerCase(Locale.ROOT);
    }
}
