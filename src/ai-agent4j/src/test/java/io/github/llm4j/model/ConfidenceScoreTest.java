package io.github.llm4j.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConfidenceScoreTest {

    @Test
    void testBuilder_requiresScore() {
        ConfidenceScore score = ConfidenceScore.builder().build();
        assertEquals(0.5, score.getScore()); // Default
    }

    @Test
    void testBuilder_validateScoreRange() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.75).build();
        assertEquals(0.75, score.getScore());
    }

    @Test
    void testBuilder_throwsOnInvalidScore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfidenceScore.builder().score(-0.1).build());

        assertThrows(
                IllegalArgumentException.class, () -> ConfidenceScore.builder().score(1.1).build());
    }

    @Test
    void testLevelCalculation_high() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.9).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.HIGH, score.getLevel());
        assertTrue(score.isHigh());
    }

    @Test
    void testLevelCalculation_medium() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.6).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.MEDIUM, score.getLevel());
        assertFalse(score.isHigh());
        assertFalse(score.isLow());
    }

    @Test
    void testLevelCalculation_low() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.3).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.LOW, score.getLevel());
        assertTrue(score.isLow());
    }

    @Test
    void testLevelCalculation_unknown() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.1).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.UNKNOWN, score.getLevel());
        assertTrue(score.isLow());
    }

    @Test
    void testLevelBoundaries_0point8IsHigh() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.8).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.HIGH, score.getLevel());
    }

    @Test
    void testLevelBoundaries_0point5IsMedium() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.5).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.MEDIUM, score.getLevel());
    }

    @Test
    void testLevelBoundaries_0point2IsLow() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.2).build();
        assertEquals(ConfidenceScore.ConfidenceLevel.LOW, score.getLevel());
    }

    @Test
    void testReasoning_canBeNull() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.7).build();
        assertNull(score.getReasoning());
    }

    @Test
    void testReasoning_isPreserved() {
        ConfidenceScore score =
                ConfidenceScore.builder().score(0.7).reasoning("All tools succeeded").build();
        assertEquals("All tools succeeded", score.getReasoning());
    }

    @Test
    void testEquals_sameScore() {
        ConfidenceScore score1 = ConfidenceScore.builder().score(0.75).reasoning("test").build();
        ConfidenceScore score2 = ConfidenceScore.builder().score(0.75).reasoning("test").build();
        assertEquals(score1, score2);
    }

    @Test
    void testHashCode_consistent() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.75).build();
        int hash1 = score.hashCode();
        int hash2 = score.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    void testToString_readable() {
        ConfidenceScore score = ConfidenceScore.builder().score(0.75).reasoning("test").build();
        String str = score.toString();
        assertTrue(str.contains("0.75"));
        assertTrue(str.contains("MEDIUM"));
    }

    @Test
    void testConvenienceMethods_high() {
        ConfidenceScore score = ConfidenceScore.high("Clean execution");
        assertTrue(score.isHigh());
        assertEquals("Clean execution", score.getReasoning());
    }

    @Test
    void testConvenienceMethods_medium() {
        ConfidenceScore score = ConfidenceScore.medium("Partial success");
        assertEquals(ConfidenceScore.ConfidenceLevel.MEDIUM, score.getLevel());
    }

    @Test
    void testConvenienceMethods_low() {
        ConfidenceScore score = ConfidenceScore.low("Tool failures");
        assertTrue(score.isLow());
    }

    @Test
    void testConvenienceMethods_unknown() {
        ConfidenceScore score = ConfidenceScore.unknown("Agent said 'I don't know'");
        assertEquals(ConfidenceScore.ConfidenceLevel.UNKNOWN, score.getLevel());
    }
}
