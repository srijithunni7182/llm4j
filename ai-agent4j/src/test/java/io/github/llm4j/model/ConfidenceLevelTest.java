package io.github.llm4j.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConfidenceLevelTest {

    @Test
    void testFromScore_0point0_isUnknown() {
        ConfidenceScore.ConfidenceLevel level = ConfidenceScore.ConfidenceLevel.fromScore(0.0);
        assertEquals(ConfidenceScore.ConfidenceLevel.UNKNOWN, level);
    }

    @Test
    void testFromScore_0point5_isMedium() {
        ConfidenceScore.ConfidenceLevel level = ConfidenceScore.ConfidenceLevel.fromScore(0.5);
        assertEquals(ConfidenceScore.ConfidenceLevel.MEDIUM, level);
    }

    @Test
    void testFromScore_0point8_isHigh() {
        ConfidenceScore.ConfidenceLevel level = ConfidenceScore.ConfidenceLevel.fromScore(0.8);
        assertEquals(ConfidenceScore.ConfidenceLevel.HIGH, level);
    }

    @Test
    void testFromScore_1point0_isHigh() {
        ConfidenceScore.ConfidenceLevel level = ConfidenceScore.ConfidenceLevel.fromScore(1.0);
        assertEquals(ConfidenceScore.ConfidenceLevel.HIGH, level);
    }

    @Test
    void testFromScore_negative_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfidenceScore.ConfidenceLevel.fromScore(-0.1));
    }

    @Test
    void testFromScore_greaterThan1_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfidenceScore.ConfidenceLevel.fromScore(1.1));
    }

    @Test
    void testContains_boundaryConditions() {
        assertTrue(ConfidenceScore.ConfidenceLevel.HIGH.contains(0.8));
        assertTrue(ConfidenceScore.ConfidenceLevel.HIGH.contains(0.9));
        assertFalse(ConfidenceScore.ConfidenceLevel.HIGH.contains(0.79));

        assertTrue(ConfidenceScore.ConfidenceLevel.MEDIUM.contains(0.5));
        assertTrue(ConfidenceScore.ConfidenceLevel.MEDIUM.contains(0.7));
        assertFalse(ConfidenceScore.ConfidenceLevel.MEDIUM.contains(0.8));

        assertTrue(ConfidenceScore.ConfidenceLevel.LOW.contains(0.2));
        assertTrue(ConfidenceScore.ConfidenceLevel.LOW.contains(0.4));
        assertFalse(ConfidenceScore.ConfidenceLevel.LOW.contains(0.5));

        assertTrue(ConfidenceScore.ConfidenceLevel.UNKNOWN.contains(0.0));
        assertTrue(ConfidenceScore.ConfidenceLevel.UNKNOWN.contains(0.1));
        assertFalse(ConfidenceScore.ConfidenceLevel.UNKNOWN.contains(0.2));
    }

    @Test
    void testGetters() {
        assertEquals(0.8, ConfidenceScore.ConfidenceLevel.HIGH.getMinScore());
        assertEquals(1.0, ConfidenceScore.ConfidenceLevel.HIGH.getMaxScore());

        assertEquals(0.5, ConfidenceScore.ConfidenceLevel.MEDIUM.getMinScore());
        assertEquals(0.8, ConfidenceScore.ConfidenceLevel.MEDIUM.getMaxScore());
    }
}
