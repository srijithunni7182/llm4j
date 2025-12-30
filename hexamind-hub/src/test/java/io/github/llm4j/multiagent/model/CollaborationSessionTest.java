package io.github.llm4j.multiagent.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationSessionTest {

        private CollaborationSession createSession(String id, String problem,
                        CollaborationSession.SessionStatus status) {
                CollaborationSession session = new CollaborationSession();
                session.setContents(id, problem);
                session.setStatus(status);
                return session;
        }

        private AgentThought createThought(String id, String content, AgentThought.ThoughtType type) {
                return new AgentThought(id, "agent1", "Agent 1", content, type, Instant.now(), new ArrayList<>(), 0.5);
        }

        private Consensus createConsensus(String rec, double score) {
                return new Consensus(rec, score, new HashMap<>(), new ArrayList<>(), new ArrayList<>(), "Reasoning");
        }

        @Test
        void testConstructorAndData() {
                Instant now = Instant.now();
                List<AgentThought> thoughts = new ArrayList<>();
                thoughts.add(createThought("t1", "content", AgentThought.ThoughtType.ANALYSIS));

                Consensus consensus = createConsensus("Test consensus", 0.9);

                CollaborationSession session = new CollaborationSession(
                                "s1",
                                "The problem",
                                CollaborationSession.SessionStatus.CREATED,
                                now,
                                null,
                                thoughts,
                                consensus,
                                1,
                                5,
                                new ConcurrentHashMap<>());

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
                CollaborationSession session = new CollaborationSession();
                session.setContents("s2", null);

                assertThat(session.getSessionId()).isEqualTo("s2");
                assertThat(session.getThoughts()).isEmpty(); // Default initialized in field
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
                CollaborationSession session = createSession("created", null,
                                CollaborationSession.SessionStatus.CREATED);

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.CREATED);
                assertThat(session.getStatus().name()).isEqualTo("CREATED");
        }

        @Test
        void testStatusAnalyzing() {
                CollaborationSession session = createSession("analyzing", null,
                                CollaborationSession.SessionStatus.ANALYZING);

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.ANALYZING);
        }

        @Test
        void testStatusCompleted() {
                CollaborationSession session = createSession("completed", null,
                                CollaborationSession.SessionStatus.COMPLETED);

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
        }

        @Test
        void testStatusFailed() {
                CollaborationSession session = createSession("failed", null, CollaborationSession.SessionStatus.FAILED);

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.FAILED);
        }

        @Test
        void testStatusTransition() {
                CollaborationSession session = createSession("transition", null,
                                CollaborationSession.SessionStatus.CREATED);

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.CREATED);

                session.setStatus(CollaborationSession.SessionStatus.ANALYZING);
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.ANALYZING);

                session.setStatus(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
        }

        @Test
        void testEmptyThoughts() {
                CollaborationSession session = createSession("empty-thoughts", null, null);
                session.setThoughts(Collections.emptyList());

                assertThat(session.getThoughts()).isEmpty();
        }

        @Test
        void testMultipleThoughts() {
                List<AgentThought> thoughts = Arrays.asList(
                                createThought("t1", "Thought 1", AgentThought.ThoughtType.ANALYSIS),
                                createThought("t2", "Thought 2", AgentThought.ThoughtType.ARGUMENT),
                                createThought("t3", "Thought 3", AgentThought.ThoughtType.CRITIQUE));

                CollaborationSession session = createSession("multi-thoughts", null, null);
                session.setThoughts(thoughts);

                assertThat(session.getThoughts()).hasSize(3);
                assertThat(session.getThoughts().get(0).getId()).isEqualTo("t1");
                assertThat(session.getThoughts().get(1).getId()).isEqualTo("t2");
                assertThat(session.getThoughts().get(2).getId()).isEqualTo("t3");
        }

        @Test
        void testRoundProgression() {
                CollaborationSession session = createSession("rounds", null, null);
                session.setCurrentRound(1);
                session.setTotalRounds(5);

                assertThat(session.getCurrentRound()).isEqualTo(1);
                assertThat(session.getTotalRounds()).isEqualTo(5);

                session.setCurrentRound(2);
                assertThat(session.getCurrentRound()).isEqualTo(2);

                session.setCurrentRound(5);
                assertThat(session.getCurrentRound()).isEqualTo(5);
        }

        @Test
        void testZeroRounds() {
                CollaborationSession session = createSession("zero", null, null);
                session.setCurrentRound(0);
                session.setTotalRounds(0);

                assertThat(session.getCurrentRound()).isEqualTo(0);
                assertThat(session.getTotalRounds()).isEqualTo(0);
        }

        @Test
        void testNullProblem() {
                CollaborationSession session = createSession("null-problem", null, null);

                assertThat(session.getProblem()).isNull();
        }

        @Test
        void testEmptyProblem() {
                CollaborationSession session = createSession("empty-problem", "", null);

                assertThat(session.getProblem()).isEmpty();
        }

        @Test
        void testLongProblem() {
                String longProblem = "A".repeat(10000);
                CollaborationSession session = createSession("long-problem", longProblem, null);

                assertThat(session.getProblem()).hasSize(10000);
        }

        @Test
        void testNullConsensus() {
                CollaborationSession session = createSession("no-consensus", null, null);
                session.setConsensus(null);

                assertThat(session.getConsensus()).isNull();
        }

        @Test
        void testWithConsensus() {
                Consensus consensus = createConsensus("Final decision", 0.9);

                CollaborationSession session = createSession("with-consensus", null, null);
                session.setConsensus(consensus);

                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getRecommendation()).isEqualTo("Final decision");
                assertThat(session.getConsensus().getAgreementScore()).isEqualTo(0.9);
        }

        @Test
        void testTimestampHandling() {
                Instant past = Instant.parse("2024-01-01T00:00:00Z");
                Instant now = Instant.now();
                Instant future = Instant.parse("2025-12-31T23:59:59Z");

                CollaborationSession session1 = createSession("past", null, null);
                session1.setCreatedAt(past);
                assertThat(session1.getCreatedAt()).isEqualTo(past);

                CollaborationSession session2 = createSession("now", null, null);
                session2.setCreatedAt(now);
                assertThat(session2.getCreatedAt()).isEqualTo(now);

                CollaborationSession session3 = createSession("future", null, null);
                session3.setCreatedAt(future);
                assertThat(session3.getCreatedAt()).isEqualTo(future);
        }

        @Test
        void testCompleteSession() {
                Instant now = Instant.now();

                List<AgentThought> thoughts = Arrays.asList(
                                createThought("t1", "Analysis", AgentThought.ThoughtType.ANALYSIS),
                                createThought("t2", "Argument", AgentThought.ThoughtType.ARGUMENT));

                Consensus consensus = createConsensus("Proceed", 0.85);

                CollaborationSession session = new CollaborationSession(
                                "complete",
                                "Complex problem",
                                CollaborationSession.SessionStatus.COMPLETED,
                                now,
                                null,
                                thoughts,
                                consensus,
                                5,
                                5,
                                new ConcurrentHashMap<>());

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

                session.setContents("new-id", "New problem");
                session.setStatus(CollaborationSession.SessionStatus.ANALYZING);
                session.setCreatedAt(now);
                session.setCurrentRound(3);
                session.setTotalRounds(10);

                List<AgentThought> thoughts = Collections.singletonList(
                                createThought("t", "content", AgentThought.ThoughtType.ANALYSIS));
                session.setThoughts(thoughts);

                Consensus consensus = createConsensus("Test", 0.8);
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
