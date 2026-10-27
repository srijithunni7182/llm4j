package io.github.llm4j.loom.runtime;

/**
 * Evaluates Loom condition strings against a VariableContext.
 *
 * <p>Supported operators (in precedence-detection order):
 *
 * <ul>
 *   <li>{@code ==} – String/numeric equality
 *   <li>{@code !=} – String/numeric inequality
 *   <li>{@code >=} – Numeric greater-than-or-equal
 *   <li>{@code <=} – Numeric less-than-or-equal
 *   <li>{@code >} – Numeric greater-than
 *   <li>{@code <} – Numeric less-than
 *   <li>(bare) – Boolean flag: {@code "true"} / {@code "false"}
 * </ul>
 *
 * <p>When both sides of a comparison can be parsed as {@code double}, numeric comparison is used.
 * Otherwise, string comparison is used.
 */
public class ConditionEvaluator {

    /**
     * Evaluates a condition string against the given context.
     *
     * @param condition raw condition text from the .loom AST
     * @param context the current execution context
     * @return {@code true} if the condition is satisfied
     */
    public static boolean evaluate(String condition, VariableContext context) {
        if (condition == null || condition.trim().isEmpty()) return false;

        String cond = condition.trim();

        // `x is ok` / `x is not ok`: did a run step do what it was for? Fails closed: anything that
        // is not a run result, or never ran, is not ok.
        java.util.regex.Matcher isOk = IS_OK.matcher(cond);
        if (isOk.matches()) {
            boolean ok = ok(isOk.group(1), context);
            return isOk.group(2) == null ? ok : !ok;
        }

        // Multi-char operators must be checked before their single-char sub-strings.
        if (cond.contains("==")) {
            return evaluateBinary(cond, "==", context);
        } else if (cond.contains("!=")) {
            return evaluateBinary(cond, "!=", context);
        } else if (cond.contains(">=")) {
            return evaluateBinary(cond, ">=", context);
        } else if (cond.contains("<=")) {
            return evaluateBinary(cond, "<=", context);
        } else if (cond.contains(">")) {
            return evaluateBinary(cond, ">", context);
        } else if (cond.contains("<")) {
            return evaluateBinary(cond, "<", context);
        } else {
            // Bare boolean flag variable: loop until (isDone)
            Object val = context.getVariable(cond);
            return "true".equalsIgnoreCase(val != null ? val.toString() : "");
        }
    }

    /**
     * The variable paths a condition looks at, as written: the left side of a comparison (the right
     * side is always a text or a number, never a variable), the path of {@code x is ok}, or the
     * flag of a bare condition. Nothing is evaluated.
     */
    public static java.util.List<String> operands(String condition) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (condition == null || condition.isBlank()) return out;
        String cond = condition.trim();
        java.util.regex.Matcher isOk = IS_OK.matcher(cond);
        if (isOk.matches()) {
            out.add(isOk.group(1));
            return out;
        }
        for (String op : OPERATORS) {
            if (cond.contains(op)) {
                String[] parts = cond.split(java.util.regex.Pattern.quote(op), 2);
                if (parts.length == 2) out.add(parts[0].trim());
                return out;
            }
        }
        out.add(cond);
        return out;
    }

    private static final String[] OPERATORS = {"==", "!=", ">=", "<=", ">", "<"};

    /**
     * The condition as a person wrote it: spaces around the operator and a text in quotes ({@code
     * intent.urgent == "yes"}). The parser keeps the condition without its quotes; this puts them
     * back for display. It changes nothing about how the condition is evaluated.
     */
    public static String display(String condition) {
        if (condition == null) return "";
        String cond = condition.trim();
        if (IS_OK.matcher(cond).matches()) return cond;
        for (String op : OPERATORS) {
            if (cond.contains(op)) {
                String[] parts = cond.split(java.util.regex.Pattern.quote(op), 2);
                if (parts.length != 2) return cond;
                String right = parts[1].trim().replace("\"", "");
                return parts[0].trim()
                        + " "
                        + op
                        + " "
                        + (isNumeric(right) ? right : "\"" + right + "\"");
            }
        }
        return cond;
    }

    private static final java.util.regex.Pattern IS_OK =
            java.util.regex.Pattern.compile("(\\S+) is (not )?ok");

    /**
     * True when {@code path} names the result of a {@code run} step whose task called its outcome
     * ok, or that was only described in a simulated run.
     */
    static boolean ok(String path, VariableContext context) {
        int dot = path.indexOf('.');
        return Boolean.TRUE.equals(
                context.getVariable("_ok:" + (dot < 0 ? path : path.substring(0, dot))));
    }

    private static boolean evaluateBinary(String cond, String operator, VariableContext context) {
        String[] parts = cond.split(java.util.regex.Pattern.quote(operator), 2);
        if (parts.length != 2) return false;

        String path = parts[0].trim();
        String expectedRaw = parts[1].trim().replace("\"", ""); // strip surrounding quotes

        Object actualValueObj = resolvePath(path, context);
        String actualValue = actualValueObj != null ? actualValueObj.toString() : "";

        // Attempt numeric comparison when both sides look like numbers.
        if (isNumeric(actualValue) && isNumeric(expectedRaw)) {
            double actual = Double.parseDouble(actualValue);
            double expected = Double.parseDouble(expectedRaw);
            return compareNumeric(actual, expected, operator);
        }

        // Fall back to string comparison for == and !=.
        return compareString(actualValue, expectedRaw, operator);
    }

    /**
     * Resolves {@code name} or a dotted path like {@code report.verdict} or {@code plan.hooks.0}
     * against the context: map keys and list indexes. Returns {@code null} if any step is missing.
     * Shared by conditions and by delegate payload substitution ({@code {report.verdict}}).
     */
    public static Object resolvePath(String path, VariableContext context) {
        if (!path.contains(".")) {
            return context.getVariable(path);
        }

        String[] parts = path.split("\\.");
        Object current = context.getVariable(parts[0]);

        for (int i = 1; i < parts.length; i++) {
            if (current == null) return null;
            current = getProperty(current, parts[i]);
        }
        return current;
    }

    private static Object getProperty(Object obj, String field) {
        if (obj instanceof java.util.Map<?, ?> map) {
            return map.get(field);
        }
        if (obj instanceof java.util.List<?> list && field.matches("\\d+")) {
            int index = Integer.parseInt(field);
            return index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    private static boolean compareNumeric(double actual, double expected, String operator) {
        return switch (operator) {
            case "==" -> actual == expected;
            case "!=" -> actual != expected;
            case ">" -> actual > expected;
            case "<" -> actual < expected;
            case ">=" -> actual >= expected;
            case "<=" -> actual <= expected;
            default -> false;
        };
    }

    private static boolean compareString(String actual, String expected, String operator) {
        return switch (operator) {
            case "==" -> expected.equals(actual);
            case "!=" -> !expected.equals(actual);
            default -> false;
        };
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) return false;
        // a number starts with one of these: asking the parser about every word costs an exception
        // each time
        char first = value.stripLeading().charAt(0);
        if (!(Character.isDigit(first)
                || first == '-'
                || first == '+'
                || first == '.'
                || first == 'N'
                || first == 'I')) return false;
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
