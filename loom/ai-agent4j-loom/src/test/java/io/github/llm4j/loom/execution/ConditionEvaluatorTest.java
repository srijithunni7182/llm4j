package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConditionEvaluatorTest {

    // -----------------------------------------------------------------------
    // Equality / Inequality (string and numeric)
    // -----------------------------------------------------------------------

    @Test
    public void testEvaluateEquals_String() {
        VariableContext ctx = ctx("status", "SUCCESS");
        assertTrue(ConditionEvaluator.evaluate("status == \"SUCCESS\"", ctx));
        assertFalse(ConditionEvaluator.evaluate("status == \"FAIL\"", ctx));
    }

    @Test
    public void testEvaluateNotEquals_String() {
        VariableContext ctx = ctx("status", "SUCCESS");
        assertTrue(ConditionEvaluator.evaluate("status != \"FAIL\"", ctx));
        assertFalse(ConditionEvaluator.evaluate("status != \"SUCCESS\"", ctx));
    }

    @Test
    public void testEvaluateEquals_Numeric() {
        VariableContext ctx = ctx("score", "0.9");
        assertTrue(ConditionEvaluator.evaluate("score == 0.9", ctx));
        assertFalse(ConditionEvaluator.evaluate("score == 0.5", ctx));
    }

    // -----------------------------------------------------------------------
    // Numeric comparison operators (previously unsupported — critical fix)
    // -----------------------------------------------------------------------

    @Test
    public void testEvaluateGreaterThan() {
        VariableContext ctx = ctx("quality_score", "0.85");
        assertTrue(ConditionEvaluator.evaluate("quality_score > 0.8", ctx));
        assertFalse(ConditionEvaluator.evaluate("quality_score > 0.9", ctx));
        assertFalse(ConditionEvaluator.evaluate("quality_score > 0.85", ctx)); // strict
    }

    @Test
    public void testEvaluateLessThan() {
        VariableContext ctx = ctx("quality_score", "0.5");
        assertTrue(ConditionEvaluator.evaluate("quality_score < 0.8", ctx));
        assertFalse(ConditionEvaluator.evaluate("quality_score < 0.4", ctx));
        assertFalse(ConditionEvaluator.evaluate("quality_score < 0.5", ctx)); // strict
    }

    @Test
    public void testEvaluateGreaterThanOrEqual() {
        VariableContext ctx = ctx("iterations", "5");
        assertTrue(ConditionEvaluator.evaluate("iterations >= 5", ctx));
        assertTrue(ConditionEvaluator.evaluate("iterations >= 3", ctx));
        assertFalse(ConditionEvaluator.evaluate("iterations >= 6", ctx));
    }

    @Test
    public void testEvaluateLessThanOrEqual() {
        VariableContext ctx = ctx("iterations", "5");
        assertTrue(ConditionEvaluator.evaluate("iterations <= 5", ctx));
        assertTrue(ConditionEvaluator.evaluate("iterations <= 10", ctx));
        assertFalse(ConditionEvaluator.evaluate("iterations <= 4", ctx));
    }

    // -----------------------------------------------------------------------
    // Bare boolean flag (loop until idiom)
    // -----------------------------------------------------------------------

    @Test
    public void testEvaluateFlag_True() {
        VariableContext ctx = ctx("isDone", "true");
        assertTrue(ConditionEvaluator.evaluate("isDone", ctx));
    }

    @Test
    public void testEvaluateFlag_False() {
        VariableContext ctx = ctx("isReady", "false");
        assertFalse(ConditionEvaluator.evaluate("isReady", ctx));
    }

    @Test
    public void testEvaluateFlag_CaseInsensitive() {
        VariableContext ctx = ctx("flag", "TRUE");
        assertTrue(ConditionEvaluator.evaluate("flag", ctx));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    public void testNullConditionReturnsFalse() {
        assertFalse(ConditionEvaluator.evaluate(null, new DefaultVariableContext()));
    }

    @Test
    public void testEmptyConditionReturnsFalse() {
        assertFalse(ConditionEvaluator.evaluate("  ", new DefaultVariableContext()));
    }

    @Test
    public void testMissingVariableDefaultsToEmpty() {
        // Missing var → getVariable returns "" → not numeric → string compare
        VariableContext ctx = new DefaultVariableContext();
        assertFalse(ConditionEvaluator.evaluate("nonexistent == \"value\"", ctx));
    }

    @Test
    public void testOrderingOperatorsOnNonNumericReturnFalse() {
        // Strings passed to > should not throw; should return false cleanly.
        VariableContext ctx = ctx("ticket_type", "technical");
        assertFalse(ConditionEvaluator.evaluate("ticket_type > 5", ctx));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private VariableContext ctx(String key, String value) {
        VariableContext ctx = new DefaultVariableContext();
        ctx.setVariable(key, value);
        return ctx;
    }
}
