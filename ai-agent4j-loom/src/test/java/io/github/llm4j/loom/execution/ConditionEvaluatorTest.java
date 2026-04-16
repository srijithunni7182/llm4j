package io.github.llm4j.loom.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConditionEvaluatorTest {
    
    @Test
    public void testEvaluateEquals() {
        HarnessContext context = new HarnessContext();
        context.setVariable("status", "SUCCESS");
        assertTrue(ConditionEvaluator.evaluate("status == \"SUCCESS\"", context));
        assertFalse(ConditionEvaluator.evaluate("status == \"FAIL\"", context));
    }

    @Test
    public void testEvaluateNotEquals() {
        HarnessContext context = new HarnessContext();
        context.setVariable("status", "SUCCESS");
        assertTrue(ConditionEvaluator.evaluate("status != \"FAIL\"", context));
        assertFalse(ConditionEvaluator.evaluate("status != \"SUCCESS\"", context));
    }

    @Test
    public void testEvaluateFlag() {
        HarnessContext context = new HarnessContext();
        context.setVariable("isDone", "true");
        assertTrue(ConditionEvaluator.evaluate("isDone", context));
        
        context.setVariable("isReady", "false");
        assertFalse(ConditionEvaluator.evaluate("isReady", context));
    }
}
