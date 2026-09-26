package io.github.llm4j.engram.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.engram.core.models.ScoredMemory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-similarity recall and novelty checks: "have I already said something that means this?" */
class EngramNoveltyTest {

    @Test
    void nearestRanksByMeaningOnlyWithinATopic() {
        EngramEngine engine = new EngramEngine();
        engine.remember("A food video about the thermodynamics of searing steak", MemoryTier.EPISODIC, 0.1, "casting:direction:1");
        engine.remember("A travel vlog about night markets in Taipei", MemoryTier.EPISODIC, 0.1, "casting:direction:2");
        // High importance and fresh, but under another topic: must not appear.
        engine.remember("The creator loves the thermodynamics of cooking", MemoryTier.SEMANTIC, 1.0, "feedback:reel");

        List<ScoredMemory> near = engine.nearest("the thermodynamics of cooking eggs", "casting:direction:", 5);
        assertEquals(2, near.size());
        assertTrue(near.get(0).memory().getContent().contains("thermodynamics"));
        assertTrue(near.get(0).score() > near.get(1).score());
        assertTrue(near.get(0).score() <= 1.0 + 1e-9, "scores are cosine similarities, not blended ranks");
    }

    @Test
    void noveltyFlagsARewordedRepeatButNotANewIdea() {
        EngramEngine engine = new EngramEngine();
        engine.remember("Explain the science of why pasta water must be salty", MemoryTier.EPISODIC, 0.1, "casting:direction:1");

        EngramEngine.Novelty repeat = engine.novelty("The science behind salting your pasta water", "casting:direction:", 0.75);
        assertFalse(repeat.novel(), "a reworded version of the same idea is not novel: " + repeat.similarity());
        assertNotNull(repeat.closest());

        EngramEngine.Novelty fresh = engine.novelty("A street vendor in Naples judges three tourists' pizza", "casting:direction:", 0.75);
        assertTrue(fresh.novel(), "a different idea is novel: " + fresh.similarity());

        EngramEngine.Novelty empty = engine.novelty("anything", "nothing-here:", 0.75);
        assertTrue(empty.novel());
        assertNull(empty.closest());
    }
}
