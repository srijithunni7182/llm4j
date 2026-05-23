package io.github.llm4j.tantrik;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSqueezerTest {

    @Test
    void doesNotSqueezeWithinBudget() {
        ContextSqueezer squeezer = new ContextSqueezer(500);
        ContextSqueezer.SqueezeResult result = squeezer.squeeze("short context");
        assertFalse(result.squeezed());
    }

    @Test
    void squeezesWhenOverBudget() {
        ContextSqueezer squeezer = new ContextSqueezer(20);
        String payload = "A".repeat(400);
        ContextSqueezer.SqueezeResult result = squeezer.squeeze(payload);
        assertTrue(result.squeezed());
        assertTrue(result.compressionRatio() < 1.0d);
    }
}
