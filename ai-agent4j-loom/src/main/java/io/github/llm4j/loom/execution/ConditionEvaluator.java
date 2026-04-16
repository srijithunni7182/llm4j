package io.github.llm4j.loom.execution;

public class ConditionEvaluator {
    // Very simple condition evaluator for Phase 1. 
    // Format: "variableName == value" or "variableName != value"
    
    public static boolean evaluate(String condition, HarnessContext context) {
        if (condition == null || condition.trim().isEmpty()) return false;
        
        String[] parts;
        boolean equalCheck = true;
        
        if (condition.contains("==")) {
            parts = condition.split("==");
        } else if (condition.contains("!=")) {
            parts = condition.split("!=");
            equalCheck = false;
        } else {
            // Assume boolean flag variable
            return "true".equalsIgnoreCase(context.getVariable(condition.trim()));
        }

        if (parts.length != 2) return false;

        String varName = parts[0].trim();
        String expectedValue = parts[1].trim().replace("\"", ""); // strip quotes
        
        String actualValue = context.getVariable(varName);

        if (equalCheck) {
            return expectedValue.equals(actualValue);
        } else {
            return !expectedValue.equals(actualValue);
        }
    }
}
