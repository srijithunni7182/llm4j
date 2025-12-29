package io.github.llm4j.multiagent.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationSessionTest {

        @Test
        void testBuilderAndData() {
                Instant now = Instant.now();
                List<AgentThought> thoughts = new ArrayList<>();
                thoughts.add(AgentThought.builder().id("t1").build());

                Consensus consensus = Consensus.builder()
                                .recommendation("Test consensus")
                                .build();

                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("s1")
                                .problem("The problem")
                                .status(CollaborationSession.SessionStatus.CREATED)
                                .createdAt(now)
                                .currentRound(1)
                                .totalRounds(5)
                                .thoughts(thoughts)
                                .consensus(consensus)
                                .build();

                assertThat(session.getSessionId()).isEqualTo("s1");
                assertThat(session.getProblem()).isEqualTo("The problem");
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.CREATED);
                assertThat(session.getCreatedAt()).isEqualTo(now);
                assertThat(session.getCurrentRound()).isEqualTo(1);
                assertThat(session.getTotalRounds()).isEqualTo(5);
                assertThat(session.getThoughts()).hasSize(1);
                assertThat(session.getConsensus()).isEqualTo(consensus);
        }

        @Test
        void testDefaultValues() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("s2")
                                .build();

                assertThat(session.getSessionId()).isEqualTo("s2");
                assertThat(session.getThoughts()).isEmpty();
        }

        @Test
        void testAllSessionStatuses() {
                CollaborationSession.SessionStatus[] statuses = CollaborationSession.SessionStatus.values();

                assertThat(statuses).contains(
                                CollaborationSession.SessionStatus.CREATED,
                                CollaborationSession.SessionStatus.ANALYZING,
                                CollaborationSession.SessionStatus.DEBATING,
                                CollaborationSession.SessionStatus.BUILDING_CONSENSUS,
                                CollaborationSession.SessionStatus.REFINING,
                                CollaborationSession.SessionStatus.COMPLETED,
                                CollaborationSession.SessionStatus.FAILED);
        }

        @Test
        void testStatusCreated() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("created")
                                .status(CollaborationSession.SessionStatus.CREATED)
                                .build();

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.CREATED);
                assertThat(session.getStatus().name()).isEqualTo("CREATED");
        }

        @Test
        void testStatusAnalyzing() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("analyzing")
                                .status(CollaborationSession.SessionStatus.ANALYZING)
                                .build();

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.ANALYZING);
        }

        @Test
        void testStatusCompleted() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("completed")
                                .status(CollaborationSession.SessionStatus.COMPLETED)
                                .build();

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
        }

        @Test
        void testStatusFailed() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("failed")
                                .status(CollaborationSession.SessionStatus.FAILED)
                                .build();

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.FAILED);
        }

        @Test
        void testStatusTransition() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("transition")
                                .status(CollaborationSession.SessionStatus.CREATED)
                                .build();

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.CREATED);

                session.setStatus(CollaborationSession.SessionStatus.ANALYZING);
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.ANALYZING);

                session.setStatus(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
        }

        @Test
        void testEmptyThoughts() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("empty-thoughts")
                                .thoughts(Collections.emptyList())
                                .build();

                assertThat(session.getThoughts()).isEmpty();
        }

        @Test
        void testMultipleThoughts() {
                List<AgentThought> thoughts = Arrays.asList(
                                AgentThought.builder().id("t1").content("Thought 1").build(),
                                AgentThought.builder().id("t2").content("Thought 2").build(),
                                AgentThought.builder().id("t3").content("Thought 3").build());

                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("multi-thoughts")
                                .thoughts(thoughts)
                                .build();

                assertThat(session.getThoughts()).hasSize(3);
                assertThat(session.getThoughts().get(0).getId()).isEqualTo("t1");
                assertThat(session.getThoughts().get(1).getId()).isEqualTo("t2");
                assertThat(session.getThoughts().get(2).getId()).isEqualTo("t3");
        }

        @Test
        void testRoundProgression() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("rounds")
                                .currentRound(1)
                                .totalRounds(5)
                                .build();

                assertThat(session.getCurrentRound()).isEqualTo(1);
                assertThat(session.getTotalRounds()).isEqualTo(5);

                session.setCurrentRound(2);
                assertThat(session.getCurrentRound()).isEqualTo(2);

                session.setCurrentRound(5);
                assertThat(session.getCurrentRound()).isEqualTo(5);
        }

        @Test
        void testZeroRounds() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("zero")
                                .currentRound(0)
                                .totalRounds(0)
                                .build();

                assertThat(session.getCurrentRound()).isEqualTo(0);
                assertThat(session.getTotalRounds()).isEqualTo(0);
        }

        @Test
        void testNullProblem() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("null-problem")
                                .problem(null)
                                .build();

                assertThat(session.getProblem()).isNull();
        }

        @Test
        void testEmptyProblem() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("empty-problem")
                                .problem("")
                                .build();

                assertThat(session.getProblem()).isEmpty();
        }

        @Test
        void testLongProblem() {
                String longProblem = "A".repeat(10000);
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("long-problem")
                                .problem(longProblem)
                                .build();

                assertThat(session.getProblem()).hasSize(10000);
        }

        @Test
        void testNullConsensus() {
                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("no-consensus")
                                .consensus(null)
                                .build();

                assertThat(session.getConsensus()).isNull();
        }

        @Test
        void testWithConsensus() {
                Consensus consensus = Consensus.builder()
                                .recommendation("Final decision")
                                .agreementScore(0.9)
                                .build();

                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("with-consensus")
                                .consensus(consensus)
                                .build();

                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getRecommendation()).isEqualTo("Final decision");
                assertThat(session.getConsensus().getAgreementScore()).isEqualTo(0.9);
        }

        @Test
        void testTimestampHandling() {
                Instant past = Instant.parse("2024-01-01T00:00:00Z");
                Instant now = Instant.now();
                Instant future = Instant.parse("2025-12-31T23:59:59Z");

                CollaborationSession session1 = CollaborationSession.builder()
                                .sessionId("past")
                                .createdAt(past)
                                .build();
                assertThat(session1.getCreatedAt()).isEqualTo(past);

                CollaborationSession session2 = CollaborationSession.builder()
                                .sessionId("now")
                                .createdAt(now)
                                .build();
                assertThat(session2.getCreatedAt()).isEqualTo(now);

                CollaborationSession session3 = CollaborationSession.builder()
                                .sessionId("future")
                                .createdAt(future)
                                .build();
                assertThat(session3.getCreatedAt()).isEqualTo(future);
        }

        @Test
        void testCompleteSession() {
                Instant now = Instant.now();

                List<AgentThought> thoughts = Arrays.asList(
                                AgentThought.builder()
                                                .id("t1")
                                                .agentId("a1")
                                                .type(AgentThought.ThoughtType.ANALYSIS)
                                                .content("Analysis")
                                                .build(),
                                AgentThought.builder()
                                                .id("t2")
                                                .agentId("a2")
                                                .type(AgentThought.ThoughtType.ARGUMENT)
                                                .content("Argument")
                                                .build());

                Consensus consensus = Consensus.builder()
                                .recommendation("Proceed")
                                .agreementScore(0.85)
                                .build();

                CollaborationSession session = CollaborationSession.builder()
                                .sessionId("complete")
                                .problem("Complex problem")
                                .status(CollaborationSession.SessionStatus.COMPLETED)
                                .createdAt(now)
                                .currentRound(5)
                                .totalRounds(5)
                                .thoughts(thoughts)
                                .consensus(consensus)
                                .build();

                assertThat(session.getSessionId()).isEqualTo("complete");
                assertThat(session.getProblem()).isEqualTo("Complex problem");
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(session.getCreatedAt()).isEqualTo(now);
                assertThat(session.getCurrentRound()).isEqualTo(5);
                assertThat(session.getTotalRounds()).isEqualTo(5);
                assertThat(session.getThoughts()).hasSize(2);
                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getRecommendation()).isEqualTo("Proceed");
        }

        @Test
        void testSettersAndGetters() {
                CollaborationSession session = new CollaborationSession();
                Instant now = Instant.now();

                session.setSessionId("new-id");
                session.setProblem("New problem");
                session.setStatus(CollaborationSession.SessionStatus.ANALYZING);
                session.setCreatedAt(now);
                session.setCurrentRound(3);
                session.setTotalRounds(10);

                List<AgentThought> thoughts = Collections.singletonList(
                                AgentThought.builder().id("t").build());
                session.setThoughts(thoughts);

                Consensus consensus = Consensus.builder().recommendation("Test").build();
                session.setConsensus(consensus);

                assertThat(session.getSessionId()).isEqualTo("new-id");
                assertThat(session.getProblem()).isEqualTo("New problem");
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.ANALYZING);
                assertThat(session.getCreatedAt()).isEqualTo(now);
                assertThat(session.getCurrentRound()).isEqualTo(3);
                assertThat(session.getTotalRounds()).isEqualTo(10);
                assertThat(session.getThoughts()).hasSize(1);
                assertThat(session.getConsensus()).isEqualTo(consensus);
        }
}
