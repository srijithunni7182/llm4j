package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Compares what a test file expects with what the run produced. An expected value is a literal
 * (equal), a map (a subset of the fields of the actual map, each field matched in turn), a list
 * (equal, element by element), or a matcher: {@code any}, {@code absent}, or a one-key map such as
 * {@code contains: "x"}, {@code matches: "regex"}, {@code one_of: [..]}, {@code gt}, {@code gte},
 * {@code lt}, {@code lte}, {@code between: [a, b]}, {@code type: number}, {@code not: matcher}.
 * Regular expressions run under a step limit so a pathological pattern cannot hang a suite.
 */
public final class Matchers {

    private static final long REGEX_STEPS = 2_000_000;

    private Matchers() {}

    /** The matcher keys a one-key map may use. */
    public static final List<String> KEYS =
            List.of(
                    "contains",
                    "matches",
                    "one_of",
                    "gt",
                    "gte",
                    "lt",
                    "lte",
                    "between",
                    "type",
                    "not",
                    "absent");

    /** Null when {@code actual} satisfies {@code expected}, else why it does not. */
    public static String mismatch(JsonNode expected, Object actual) {
        return mismatch(expected, actual, "");
    }

    private static String mismatch(JsonNode expected, Object actual, String path) {
        String at = path.isEmpty() ? "" : path + ": ";
        if (expected == null || expected.isNull())
            return actual == null ? null : at + "expected nothing, got " + show(actual);
        if (expected.isTextual() && expected.asText().equals("any"))
            return actual == null ? at + "expected a value, got nothing" : null;
        if (expected.isTextual() && expected.asText().equals("absent"))
            return actual == null ? null : at + "expected nothing, got " + show(actual);
        if (isMatcher(expected)) return matcher(expected, actual, at);
        if (expected.isObject()) {
            Object value = actual;
            if (value instanceof String s)
                value = Values.parseObject(s).map(m -> (Object) m).orElse(value);
            if (!(value instanceof Map<?, ?> m))
                return at + "expected an object with " + fields(expected) + ", got " + show(actual);
            List<String> problems = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = expected.fields();
            while (it.hasNext()) {
                var e = it.next();
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (!m.containsKey(e.getKey())) {
                    if (!(e.getValue().isTextual() && e.getValue().asText().equals("absent")))
                        problems.add(child + ": missing (the result has " + m.keySet() + ")");
                    continue;
                }
                String p = mismatch(e.getValue(), m.get(e.getKey()), child);
                if (p != null) problems.add(p);
            }
            return problems.isEmpty() ? null : String.join("; ", problems);
        }
        if (expected.isArray()) {
            Object value = actual;
            if (value instanceof String s)
                value = Values.parseList(s).map(l -> (Object) l).orElse(value);
            if (!(value instanceof List<?> l)) return at + "expected a list, got " + show(actual);
            if (l.size() != expected.size())
                return at
                        + "expected "
                        + expected.size()
                        + " items, got "
                        + l.size()
                        + ": "
                        + show(actual);
            for (int i = 0; i < l.size(); i++) {
                String p = mismatch(expected.get(i), l.get(i), path + "[" + i + "]");
                if (p != null) return p;
            }
            return null;
        }
        return literal(expected, actual)
                ? null
                : at + "expected " + expected.asText() + ", got " + show(actual);
    }

    private static boolean literal(JsonNode expected, Object actual) {
        if (actual == null) return false;
        if (expected.isNumber()) {
            BigDecimal a = number(actual);
            return a != null && a.compareTo(expected.decimalValue()) == 0;
        }
        if (expected.isBoolean()) return String.valueOf(actual).equalsIgnoreCase(expected.asText());
        return String.valueOf(actual).strip().equals(expected.asText().strip());
    }

    // ---- matcher forms

    private static boolean isMatcher(JsonNode n) {
        return n.isObject() && n.size() == 1 && KEYS.contains(n.fieldNames().next());
    }

    private static String matcher(JsonNode n, Object actual, String at) {
        String key = n.fieldNames().next();
        JsonNode arg = n.get(key);
        String text = actual == null ? null : String.valueOf(actual);
        switch (key) {
            case "absent":
                return actual == null ? null : at + "expected nothing, got " + show(actual);
            case "not":
                return mismatch(arg, actual, "") == null
                        ? at + "expected anything but " + describe(arg) + ", got " + show(actual)
                        : null;
            case "contains":
                if (text == null)
                    return at + "expected text containing " + quote(arg.asText()) + ", got nothing";
                return text.contains(arg.asText())
                        ? null
                        : at
                                + "expected text containing "
                                + quote(arg.asText())
                                + ", got "
                                + show(actual);
            case "matches":
                if (text == null)
                    return at + "expected text matching " + quote(arg.asText()) + ", got nothing";
                try {
                    return Pattern.compile(arg.asText()).matcher(new Limited(text)).find()
                            ? null
                            : at
                                    + "expected text matching "
                                    + quote(arg.asText())
                                    + ", got "
                                    + show(actual);
                } catch (RuntimeException e) {
                    return at
                            + "the pattern "
                            + quote(arg.asText())
                            + " could not be used: "
                            + e.getMessage();
                }
            case "one_of":
                for (JsonNode o : arg) if (literal(o, actual)) return null;
                return at + "expected one of " + describe(arg) + ", got " + show(actual);
            case "gt", "gte", "lt", "lte", "between":
                return compare(key, arg, actual, at);
            case "type":
                return typeOf(actual).equals(arg.asText())
                        ? null
                        : at
                                + "expected a "
                                + arg.asText()
                                + ", got "
                                + typeOf(actual)
                                + " "
                                + show(actual);
            default:
                return at + "unknown matcher " + key;
        }
    }

    private static String compare(String key, JsonNode arg, Object actual, String at) {
        BigDecimal a = number(actual);
        if (a == null)
            return at + "expected a number " + key + " " + describe(arg) + ", got " + show(actual);
        boolean ok;
        switch (key) {
            case "gt" -> ok = a.compareTo(arg.decimalValue()) > 0;
            case "gte" -> ok = a.compareTo(arg.decimalValue()) >= 0;
            case "lt" -> ok = a.compareTo(arg.decimalValue()) < 0;
            case "lte" -> ok = a.compareTo(arg.decimalValue()) <= 0;
            default -> ok =
                    arg.isArray()
                            && arg.size() == 2
                            && a.compareTo(arg.get(0).decimalValue()) >= 0
                            && a.compareTo(arg.get(1).decimalValue()) <= 0;
        }
        return ok ? null : at + "expected " + key + " " + describe(arg) + ", got " + show(actual);
    }

    private static BigDecimal number(Object o) {
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o == null) return null;
        try {
            return new BigDecimal(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String typeOf(Object o) {
        if (o == null) return "nothing";
        if (o instanceof Number) return "number";
        if (o instanceof Boolean) return "boolean";
        if (o instanceof Map) return "object";
        if (o instanceof List) return "list";
        String s = String.valueOf(o).strip();
        if (number(s) != null) return "number";
        return "text";
    }

    // ---- words

    /** An actual value as a short phrase: quoted text (cut), numbers, maps and lists. */
    static String show(Object o) {
        if (o == null) return "nothing";
        String s = String.valueOf(o);
        if (o instanceof CharSequence)
            return s.length() <= 200
                    ? quote(s)
                    : quote(s.substring(0, 200))
                            + "… ("
                            + s.length()
                            + " characters, sha256 "
                            + Values.hash(s)
                            + ")";
        return Values.cut(s, 200, false);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String describe(JsonNode n) {
        return n.isTextual() ? quote(n.asText()) : n.toString();
    }

    private static String fields(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    /** Text that gives up after too many character reads, so a bad pattern cannot hang a suite. */
    private static final class Limited implements CharSequence {
        private final String text;
        private long steps;

        Limited(String text) {
            this.text = text;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps > REGEX_STEPS)
                throw new IllegalStateException("the pattern took too many steps on this text");
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Limited(text.substring(start, end));
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Whether {@code n} is a known matcher form or a plain value, for the file checker. */
    public static Optional<String> badMatcher(JsonNode n) {
        if (n == null || !n.isObject()) return Optional.empty();
        if (n.size() == 1) {
            String k = n.fieldNames().next();
            if (KEYS.contains(k)
                    && k.equals("between")
                    && !(n.get(k).isArray() && n.get(k).size() == 2))
                return Optional.of("between takes a list of two numbers");
            if (k.equals("matches"))
                try {
                    Pattern.compile(n.get(k).asText());
                } catch (RuntimeException e) {
                    return Optional.of("matches: the pattern does not compile: " + e.getMessage());
                }
        }
        return Optional.empty();
    }
}
