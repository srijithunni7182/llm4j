package io.github.llm4j.multiagent.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentThoughtTest {

    @Test
    void testBuilderAndData() {
        Instant now = Instant.now();
        List<String> references = Arrays.asList("ref1", "ref2");

        AgentThought thought = AgentThought.builder()
                .id("thought-1")
                .agentId("agent-1")
                .agentName("Test Agent")
                .content("This is my thought")
                .type(AgentThought.ThoughtType.ANALYSIS)
                .timestamp(now)
                .referencesTo(references)
                .confidence(0.85)
                .build();

        assertThat(thought.getId()).isEqualTo("thought-1");
        assertThat(thought.getAgentId()).isEqualTo("agent-1");
        assertThat(thought.getAgentName()).isEqualTo("Test Agent");
        assertThat(thought.getContent()).isEqualTo("This is my thought");
        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.ANALYSIS);
        assertThat(thought.getTimestamp()).isEqualTo(now);
        assertThat(thought.getReferencesTo()).containsExactly("ref1", "ref2");
        assertThat(thought.getConfidence()).isEqualTo(0.85);
    }

    @Test
    void testDefaultValues() {
        AgentThought thought = AgentThought.builder()
                .id("thought-2")
                .build();

        assertThat(thought.getId()).isEqualTo("thought-2");
        assertThat(thought.getReferencesTo()).isNotNull();
        assertThat(thought.getReferencesTo()).isEmpty();
    }

    @Test
    void testNoArgsConstructor() {
        AgentThought thought = new AgentThought();

        assertThat(thought.getId()).isNull();
        assertThat(thought.getAgentId()).isNull();
        assertThat(thought.getAgentName()).isNull();
        assertThat(thought.getContent()).isNull();
        assertThat(thought.getType()).isNull();
        assertThat(thought.getTimestamp()).isNull();
        assertThat(thought.getReferencesTo()).isEmpty();
        assertThat(thought.getConfidence()).isEqualTo(0.0);
    }

    @Test
    void testAllArgsConstructor() {
        Instant now = Instant.now();
        List<String> refs = Collections.singletonList("ref");

        AgentThought thought = new AgentThought(
                "id", "agentId", "agentName", "content",
                AgentThought.ThoughtType.ARGUMENT, now, refs, 0.9);

        assertThat(thought.getId()).isEqualTo("id");
        assertThat(thought.getAgentId()).isEqualTo("agentId");
        assertThat(thought.getAgentName()).isEqualTo("agentName");
        assertThat(thought.getContent()).isEqualTo("content");
        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.ARGUMENT);
        assertThat(thought.getTimestamp()).isEqualTo(now);
        assertThat(thought.getReferencesTo()).isEqualTo(refs);
        assertThat(thought.getConfidence()).isEqualTo(0.9);
    }

    @Test
    void testAllThoughtTypes() {
        AgentThought.ThoughtType[] types = AgentThought.ThoughtType.values();

        assertThat(types).contains(
                AgentThought.ThoughtType.ANALYSIS,
                AgentThought.ThoughtType.ARGUMENT,
                AgentThought.ThoughtType.COUNTER_ARGUMENT,
                AgentThought.ThoughtType.CRITIQUE,
                AgentThought.ThoughtType.REBUTTAL,
                AgentThought.ThoughtType.AGREEMENT,
                AgentThought.ThoughtType.REFINEMENT,
                AgentThought.ThoughtType.CONCLUSION);
    }

    @Test
    void testThoughtTypeAnalysis() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.ANALYSIS)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.ANALYSIS);
        assertThat(thought.getType().name()).isEqualTo("ANALYSIS");
    }

    @Test
    void testThoughtTypeArgument() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.ARGUMENT)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.ARGUMENT);
    }

    @Test
    void testThoughtTypeCounterArgument() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.COUNTER_ARGUMENT)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.COUNTER_ARGUMENT);
    }

    @Test
    void testThoughtTypeCritique() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.CRITIQUE)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.CRITIQUE);
    }

    @Test
    void testThoughtTypeRebuttal() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.REBUTTAL)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.REBUTTAL);
    }

    @Test
    void testThoughtTypeAgreement() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.AGREEMENT)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.AGREEMENT);
    }

    @Test
    void testThoughtTypeRefinement() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.REFINEMENT)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.REFINEMENT);
    }

    @Test
    void testThoughtTypeConclusion() {
        AgentThought thought = AgentThought.builder()
                .type(AgentThought.ThoughtType.CONCLUSION)
                .build();

        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.CONCLUSION);
    }

    @Test
    void testEmptyReferences() {
        AgentThought thought = AgentThought.builder()
                .referencesTo(Collections.emptyList())
                .build();

        assertThat(thought.getReferencesTo()).isEmpty();
    }

    @Test
    void testMultipleReferences() {
        List<String> refs = Arrays.asList("ref1", "ref2", "ref3", "ref4");
        AgentThought thought = AgentThought.builder()
                .referencesTo(refs)
                .build();

        assertThat(thought.getReferencesTo()).hasSize(4);
        assertThat(thought.getReferencesTo()).containsExactly("ref1", "ref2", "ref3", "ref4");
    }

    @Test
    void testConfidenceBounds() {
        AgentThought lowConfidence = AgentThought.builder()
                .confidence(0.0)
                .build();
        assertThat(lowConfidence.getConfidence()).isEqualTo(0.0);

        AgentThought highConfidence = AgentThought.builder()
                .confidence(1.0)
                .build();
        assertThat(highConfidence.getConfidence()).isEqualTo(1.0);

        AgentThought midConfidence = AgentThought.builder()
                .confidence(0.5)
                .build();
        assertThat(midConfidence.getConfidence()).isEqualTo(0.5);
    }

    @Test
    void testNullContent() {
        AgentThought thought = AgentThought.builder()
                .content(null)
                .build();

        assertThat(thought.getContent()).isNull();
    }

    @Test
    void testEmptyContent() {
        AgentThought thought = AgentThought.builder()
                .content("")
                .build();

        assertThat(thought.getContent()).isEmpty();
    }

    @Test
    void testLongContent() {
        String longContent = "A".repeat(10000);
        AgentThought thought = AgentThought.builder()
                .content(longContent)
                .build();

        assertThat(thought.getContent()).hasSize(10000);
    }

    @Test
    void testSettersAndGetters() {
        AgentThought thought = new AgentThought();
        Instant now = Instant.now();

        thought.setId("new-id");
        thought.setAgentId("new-agent");
        thought.setAgentName("New Name");
        thought.setContent("New content");
        thought.setType(AgentThought.ThoughtType.CRITIQUE);
        thought.setTimestamp(now);
        thought.setReferencesTo(Arrays.asList("r1", "r2"));
        thought.setConfidence(0.75);

        assertThat(thought.getId()).isEqualTo("new-id");
        assertThat(thought.getAgentId()).isEqualTo("new-agent");
        assertThat(thought.getAgentName()).isEqualTo("New Name");
        assertThat(thought.getContent()).isEqualTo("New content");
        assertThat(thought.getType()).isEqualTo(AgentThought.ThoughtType.CRITIQUE);
        assertThat(thought.getTimestamp()).isEqualTo(now);
        assertThat(thought.getReferencesTo()).containsExactly("r1", "r2");
        assertThat(thought.getConfidence()).isEqualTo(0.75);
    }
}
