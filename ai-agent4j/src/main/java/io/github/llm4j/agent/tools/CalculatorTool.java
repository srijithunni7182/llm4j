package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;

/**
 * A calculator tool that can evaluate basic mathematical expressions.
 */
public class CalculatorTool implements Tool {

    @Override
    public String getName() {
        return "Calculator";
    }

    @Override
    public String getDescription() {
        return "Useful for performing mathematical calculations. " +
                "Input should be a JSON object with an 'expression' field, e.g., {\"expression\": \"2 + 2\"}. " +
                "Supports +, -, *, / operators.";
    }

    @Override
    public String execute(java.util.Map<String, Object> args) {
        String input = (String) args.get("expression");
        if (input == null || input.trim().isEmpty()) {
            // Fallback to "input" key if "expression" is missing
            input = (String) args.get("input");
        }

        if (input == null || input.trim().isEmpty()) {
            return "Error: No 'expression' provided in arguments";
        }

        try {
            double result = evaluate(input.trim());
            // Return integer if no decimal part
            if (result == (long) result) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "Error evaluating expression: " + e.getMessage();
        }
    }

    /**
     * Simple expression evaluator that handles basic arithmetic.
     * Supports +, -, *, / and parentheses with proper precedence.
     */
    private double evaluate(String expression) {
        return new ExpressionParser(expression).parse();
    }

    private static class ExpressionParser {
        private final String expression;
        private int pos = -1;
        private int ch;

        ExpressionParser(String expression) {
            this.expression = expression.replaceAll("\\s+", "");
        }

        void nextChar() {
            ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
        }

        boolean eat(int charToEat) {
            while (ch == ' ')
                nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < expression.length()) {
                throw new RuntimeException("Unexpected: " + (char) ch);
            }
            return x;
        }

        double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if (eat('+'))
                    x += parseTerm();
                else if (eat('-'))
                    x -= parseTerm();
                else
                    return x;
            }
        }

        double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if (eat('*'))
                    x *= parseFactor();
                else if (eat('/'))
                    x /= parseFactor();
                else
                    return x;
            }
        }

        double parseFactor() {
            if (eat('+'))
                return parseFactor();
            if (eat('-'))
                return -parseFactor();

            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                eat(')');
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.')
                    nextChar();
                x = Double.parseDouble(expression.substring(startPos, this.pos));
            } else {
                throw new RuntimeException("Unexpected: " + (char) ch);
            }

            return x;
        }
    }
}
