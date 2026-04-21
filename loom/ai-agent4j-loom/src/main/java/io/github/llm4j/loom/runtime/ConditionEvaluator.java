package io.github.llm4j.loom.runtime;

/**
 * Evaluates Loom condition strings against a VariableContext.
 *
 * <p>Supported operators (in precedence-detection order):
 * <ul>
 *   <li>{@code ==}  – String/numeric equality</li>
 *   <li>{@code !=}  – String/numeric inequality</li>
 *   <li>{@code >=}  – Numeric greater-than-or-equal</li>
 *   <li>{@code <=}  – Numeric less-than-or-equal</li>
 *   <li>{@code >}   – Numeric greater-than</li>
 *   <li>{@code <}   – Numeric less-than</li>
 *   <li>(bare)      – Boolean flag: {@code "true"} / {@code "false"}</li>
 * </ul>
 *
 * <p>When both sides of a comparison can be parsed as {@code double}, numeric
 * comparison is used. Otherwise, string comparison is used.
 */
public class ConditionEvaluator {

    /**
     * Evaluates a condition string against the given context.
     *
     * @param condition raw condition text from the .loom AST
     * @param context   the current execution context
     * @return {@code true} if the condition is satisfied
     */
    public static boolean evaluate(String condition, VariableContext context) {
        if (condition == null || condition.trim().isEmpty()) return false;

        String cond = condition.trim();

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

    private static boolean evaluateBinary(String cond, String operator, VariableContext context) {
        String[] parts = cond.split(java.util.regex.Pattern.quote(operator), 2);
        if (parts.length != 2) return false;

        String path          = parts[0].trim();
        String expectedRaw   = parts[1].trim().replace("\"", ""); // strip surrounding quotes

        Object actualValueObj = resolvePath(path, context);
        String actualValue = actualValueObj != null ? actualValueObj.toString() : "";

        // Attempt numeric comparison when both sides look like numbers.
        if (isNumeric(actualValue) && isNumeric(expectedRaw)) {
            double actual   = Double.parseDouble(actualValue);
            double expected = Double.parseDouble(expectedRaw);
            return compareNumeric(actual, expected, operator);
        }

        // Fall back to string comparison for == and !=.
        return compareString(actualValue, expectedRaw, operator);
    }

    private static Object resolvePath(String path, VariableContext context) {
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
        return null;
    }

    private static boolean compareNumeric(double actual, double expected, String operator) {
        return switch (operator) {
            case "==" -> actual == expected;
            case "!=" -> actual != expected;
            case ">"  -> actual >  expected;
            case "<"  -> actual <  expected;
            case ">=" -> actual >= expected;
            case "<=" -> actual <= expected;
            default   -> false;
        };
    }

    private static boolean compareString(String actual, String expected, String operator) {
        return switch (operator) {
            case "==" -> expected.equals(actual);
            case "!=" -> !expected.equals(actual);
            default   -> false;
        };
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
