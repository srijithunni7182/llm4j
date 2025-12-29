package io.github.llm4j.multiagent.model;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentParticipantTest {

    @Mock
    private ReActAgent mockAgent;

    @Mock
    private AgentPersona mockPersona;

    private AgentParticipant participant;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockPersona.getRole()).thenReturn("Test Role");

        participant = new AgentParticipant(
                "agent-1",
                "Test Agent",
                mockPersona,
                mockAgent,
                "/images/test.png");
    }

    @Test
    void testConstructorAndGetters() {
        assertThat(participant.getId()).isEqualTo("agent-1");
        assertThat(participant.getName()).isEqualTo("Test Agent");
        assertThat(participant.getPersona()).isEqualTo(mockPersona);
        assertThat(participant.getAgent()).isEqualTo(mockAgent);
        assertThat(participant.getAvatarUrl()).isEqualTo("/images/test.png");
        assertThat(participant.getThoughts()).isEmpty();
        assertThat(participant.getCurrentThought()).isNull();
        assertThat(participant.getOpinion()).isNull();
    }

    @Test
    void testAnalyze() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("Analysis result");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.analyze("Test problem");

        assertThat(result).isEqualTo("Analysis result");
        assertThat(participant.getCurrentThought()).isEqualTo("Analysis result");
        verify(mockAgent).run(contains("Test problem"));
        verify(mockAgent).run(contains("Test Role"));
    }

    @Test
    void testAnalyzeWithNullProblem() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("Null analysis");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.analyze(null);

        assertThat(result).isEqualTo("Null analysis");
        verify(mockAgent).run(anyString());
    }

    @Test
    void testAnalyzeWithEmptyProblem() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("Empty analysis");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.analyze("");

        assertThat(result).isEqualTo("Empty analysis");
        verify(mockAgent).run(anyString());
    }

    @Test
    void testArgue() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("My argument");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.argue("Problem", "Context from others");

        assertThat(result).isEqualTo("My argument");
        assertThat(participant.getCurrentThought()).isEqualTo("My argument");
        verify(mockAgent).run(contains("Problem"));
        verify(mockAgent).run(contains("Context from others"));
    }

    @Test
    void testArgueWithNullContext() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("Argument without context");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.argue("Problem", null);

        assertThat(result).isEqualTo("Argument without context");
        verify(mockAgent).run(anyString());
    }

    @Test
    void testRespond() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("My response");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.respond("Other arguments");

        assertThat(result).isEqualTo("My response");
        assertThat(participant.getCurrentThought()).isEqualTo("My response");
        verify(mockAgent).run(contains("Other arguments"));
    }

    @Test
    void testRespondWithNullArguments() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("Response to null");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        String result = participant.respond(null);

        assertThat(result).isEqualTo("Response to null");
        verify(mockAgent).run(anyString());
    }

    @Test
    void testFormOpinion() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("My final opinion");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        List<AgentThought> thoughts = new ArrayList<>();
        thoughts.add(AgentThought.builder()
                .agentName("Agent1")
                .content("Thought 1")
                .build());
        thoughts.add(AgentThought.builder()
                .agentName("Agent2")
                .content("Thought 2")
                .build());

        AgentOpinion opinion = participant.formOpinion("Problem", thoughts);

        assertThat(opinion).isNotNull();
        assertThat(opinion.getAgentId()).isEqualTo("agent-1");
        assertThat(opinion.getAgentName()).isEqualTo("Test Agent");
        assertThat(opinion.getRecommendation()).isEqualTo("My final opinion");
        assertThat(opinion.getConfidence()).isEqualTo(0.8);
        assertThat(participant.getOpinion()).isEqualTo(opinion);

        verify(mockAgent).run(contains("Problem"));
        verify(mockAgent).run(contains("Agent1: Thought 1"));
        verify(mockAgent).run(contains("Agent2: Thought 2"));
    }

    @Test
    void testFormOpinionWithEmptyThoughts() {
        AgentResult mockResult = mock(AgentResult.class);
        when(mockResult.getFinalAnswer()).thenReturn("Opinion without context");
        when(mockAgent.run(anyString())).thenReturn(mockResult);

        AgentOpinion opinion = participant.formOpinion("Problem", Collections.emptyList());

        assertThat(opinion).isNotNull();
        assertThat(opinion.getRecommendation()).isEqualTo("Opinion without context");
        verify(mockAgent).run(anyString());
    }

    @Test
    void testAddThought() {
        AgentThought thought1 = AgentThought.builder()
                .id("t1")
                .content("First thought")
                .build();
        AgentThought thought2 = AgentThought.builder()
                .id("t2")
                .content("Second thought")
                .build();

        participant.addThought(thought1);
        participant.addThought(thought2);

        assertThat(participant.getThoughts()).hasSize(2);
        assertThat(participant.getThoughts()).containsExactly(thought1, thought2);
    }

    @Test
    void testMultipleAnalysisCalls() {
        AgentResult mockResult1 = mock(AgentResult.class);
        when(mockResult1.getFinalAnswer()).thenReturn("First analysis");

        AgentResult mockResult2 = mock(AgentResult.class);
        when(mockResult2.getFinalAnswer()).thenReturn("Second analysis");

        when(mockAgent.run(anyString()))
                .thenReturn(mockResult1)
                .thenReturn(mockResult2);

        participant.analyze("Problem 1");
        assertThat(participant.getCurrentThought()).isEqualTo("First analysis");

        participant.analyze("Problem 2");
        assertThat(participant.getCurrentThought()).isEqualTo("Second analysis");

        verify(mockAgent, times(2)).run(anyString());
    }

    @Test
    void testAgentThrowsException() {
        when(mockAgent.run(anyString())).thenThrow(new RuntimeException("Agent error"));

        try {
            participant.analyze("Problem");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Agent error");
        }

        // Current thought should not be updated on error
        assertThat(participant.getCurrentThought()).isNull();
    }
}
