package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ladder.LCase;
import io.github.llm4j.loom.ladder.LedgerFold;
import io.github.llm4j.loom.ladder.LevelLog;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workflow that decides, run case after case against one ledger: the ladder as a person running it would see it. */
@Tag("L6")
class LadderRunTest {

    static final String REFUND = """
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 3 and history.rate(agrees) >= 0.8
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 6 and history.rate(agrees) >= 0.8
                    slip to suggest when history.last(3).count(dangerous) >= 2
                    escalate when case.amount > 200
                }
                climbing is automatic
            }
            decision Refund {
                proposed by: Triager
                choices: approve, reject, escalate
                group cases by: tier
                remember: amount, tier
                dangerous mistake: propose approve, person decides reject
                ask: support-lead
                ladder: L
                ceiling: act
                when the agent changes: start over
            }
            workflow Triage(ticket, tier, amount, reason, customer_since) {
                decide Refund -> verdict
                note "ticket {ticket}: {verdict} (proposed {verdict_proposal}, rung {verdict_level})"
            }
            """;

    static final String PUBLISH = """
            agent Writer {
                model: "m"
                system: "You are Writer."
                output_schema: { post: string, reasoning: string }
            }
            measure edit_size(c) = distance(c.proposal.value, c.response.final)
            measure untouched(c) = c.response.kind == "accept"
            ladder P {
                rung edit { mode: confirm }
                rung publish {
                    mode: autonomous
                    climb when history.count >= 3 and history.rate(untouched) >= 0.6
                }
                climbing is automatic
            }
            decision Publish {
                proposed by: Writer
                proposes: post
                remember: topic
                ask: editors
                ladder: P
                ceiling: publish
                when the agent changes: start over
            }
            workflow Triage(ticket, tier, amount, reason, customer_since, topic) {
                decide Publish -> post
                note "{post}"
            }
            """;

    @TempDir
    Path dir;

    private DecideHarness harness(String script) {
        return new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), script);
    }

    private static List<LCase> cases(DecideHarness h, String decision) {
        return LedgerFold.cases(h.ledger.records(decision));
    }

    @Test
    void aLadderWrittenOnTheAgentRunsTheDecisionExactlyAsIfTheDecisionHadBoundIt() {
        String onAgent = REFUND
                .replace("output_schema: { choice: string, reasoning: string, confidence: number }\n}",
                        "output_schema: { choice: string, reasoning: string, confidence: number }\n    ladder: L\n    ceiling: act\n}")
                .replace("                ladder: L\n                ceiling: act\n", "");
        assertThat(onAgent).doesNotContain("                ladder: L");
        DecideHarness h = harness(onAgent);
        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        h.person = f -> "approve";
        for (int i = 0; i < 3; i++) h.runCase("w" + i, h.inputs("gold", 50 + i));
        assertThat(h.asked).hasSize(3).noneMatch(q -> q.contains("The agent proposes"));
        assertThat(h.runCase("s0", h.inputs("gold", 60))).containsEntry("verdict_level", "suggest");
        for (int i = 0; i < 5; i++) h.runCase("s" + (i + 1), h.inputs("gold", 70 + i));
        int asked = h.asked.size();
        assertThat(h.runCase("a0", h.inputs("gold", 90))).containsEntry("verdict_level", "act");
        assertThat(h.asked).hasSize(asked);
    }

    @Test
    void aNewAgentIsWatchedTheQuestionHidesTheProposalAndEveryPartIsRecorded() {
        DecideHarness h = harness(REFUND);
        h.agent = f -> new String[] {"approve", "the order was damaged", "0.9"};
        h.person = f -> "approve";
        Map<String, Object> out = h.runCase("r1", h.inputs("gold", 80));

        assertThat(out).containsEntry("verdict", "approve").containsEntry("verdict_proposal", "approve").containsEntry("verdict_level", "watch");
        assertThat(h.asked).hasSize(1);
        assertThat(h.asked.get(0)).contains("support-lead").contains("amount = 80").doesNotContain("The agent proposes").doesNotContain("damaged");
        assertThat(h.trace).anyMatch(t -> t.contains("proposal recorded (hidden)"));
        assertThat(h.trace).noneMatch(t -> t.contains("proposes approve"));

        List<LCase> cases = cases(h, "Refund");
        assertThat(cases).hasSize(1);
        LCase c = cases.get(0);
        assertThat(c.id()).isEqualTo("r1/" + c.id().substring(3));
        assertThat(c.scope()).isEqualTo("gold");
        assertThat(c.level()).isEqualTo("watch");
        assertThat(c.fields()).containsEntry("amount", "80");
        assertThat(c.proposalChoice()).isEqualTo("approve");
        assertThat(c.reasoning()).isEqualTo("the order was damaged");
        assertThat(c.responseKind()).isEqualTo("accept");
        assertThat(c.responder()).isEqualTo("support-lead");
        assertThat(c.shown()).isFalse();
        assertThat(c.blind()).isTrue();
        assertThat(c.servedModel()).isEqualTo("m");
    }

    @Test
    void theAgentClimbsOnItsRecordAndTheLadderChangesWhatTheRunDoes() {
        DecideHarness h = harness(REFUND);
        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        h.person = f -> "approve";
        for (int i = 0; i < 3; i++) h.runCase("w" + i, h.inputs("gold", 50 + i));
        assertThat(h.asked).hasSize(3);
        assertThat(h.asked).noneMatch(q -> q.contains("The agent proposes"));

        // three agreeing cases: suggest. The person now sees the proposal.
        Map<String, Object> out = h.runCase("s0", h.inputs("gold", 60));
        assertThat(out).containsEntry("verdict_level", "suggest");
        assertThat(h.asked.get(3)).contains("The agent proposes: approve").contains("fine");
        assertThat(cases(h, "Refund").get(3).shown()).isTrue();

        // suggest cases are not blind evidence for the next step, but the ladder counts every answered case
        for (int i = 0; i < 5; i++) h.runCase("s" + (i + 1), h.inputs("gold", 70 + i));
        int asked = h.asked.size();
        Map<String, Object> acted = h.runCase("a0", h.inputs("gold", 90));
        assertThat(acted).containsEntry("verdict", "approve").containsEntry("verdict_level", "act");
        assertThat(h.asked).hasSize(asked);                                 // nobody was asked
        LCase last = cases(h, "Refund").get(cases(h, "Refund").size() - 1);
        assertThat(last.responder()).isEqualTo("agent");
        assertThat(last.blind()).isFalse();
        assertThat(last.level()).isEqualTo("act");
    }

    @Test
    void scopesEarnSeparatelyAndALimitSendsACaseToAPersonWhateverTheRung() {
        DecideHarness h = harness(REFUND);
        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        h.person = f -> "approve";
        for (int i = 0; i < 9; i++) h.runCase("g" + i, h.inputs("gold", 50));
        assertThat(h.runCase("g9", h.inputs("gold", 50))).containsEntry("verdict_level", "act");
        assertThat(h.runCase("b0", h.inputs("basic", 50))).containsEntry("verdict_level", "watch");

        int asked = h.asked.size();
        Map<String, Object> big = h.runCase("g10", h.inputs("gold", 500));
        assertThat(h.asked).hasSize(asked + 1);                             // amount over 200: a person is asked
        assertThat(big).containsEntry("verdict_level", "act");
        assertThat(cases(h, "Refund").get(cases(h, "Refund").size() - 1).how()).isEqualTo("escalate");
    }

    @Test
    void aDangerousMistakeSlipsTheAgentDownAndTheNextCaseIsAskedAgain() {
        DecideHarness h = harness(REFUND);
        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        h.person = f -> "approve";
        for (int i = 0; i < 10; i++) h.runCase("g" + i, h.inputs("gold", 50));
        assertThat(h.runCase("act0", h.inputs("gold", 50))).containsEntry("verdict_level", "act");
        h.person = f -> "reject";
        h.runCase("x1", h.inputs("gold", 500));                              // escalated: the person rejects what the agent approved
        h.runCase("x2", h.inputs("gold", 500));
        int asked = h.asked.size();
        Map<String, Object> after = h.runCase("after", h.inputs("gold", 50));
        assertThat(after).containsEntry("verdict_level", "suggest");
        assertThat(h.asked).hasSize(asked + 1);
        assertThat(h.audit).contains("level_changed");
    }

    @Test
    void theSameRunTwiceLeavesOneCaseAndAResumedRunRepeatsNothing() {
        DecideHarness h = harness(REFUND);
        h.person = f -> "approve";
        io.github.llm4j.loom.runtime.RunJournal journal = io.github.llm4j.loom.runtime.RunJournal.inMemory();
        for (int i = 0; i < 2; i++) {
            io.github.llm4j.loom.generic.support.ScriptedRun run = h.newRun(journal);
            io.github.llm4j.loom.execution.HarnessExecutor e = h.executor(run, "same");
            e.executeWorkflow("Triage", h.inputs("gold", 50));
        }
        assertThat(cases(h, "Refund")).hasSize(1);
        assertThat(h.asked).hasSize(1);
        assertThat(h.modelCalls).isEqualTo(1);
        assertThat(h.ledger.recordsOfKind("Refund", Rec.RESPONDED)).hasSize(1);
    }

    @Test
    void aLedgerThatCannotBeReadNeverMeansTheAgentActsAlone() {
        DecideHarness h = new DecideHarness(dir, new MemoryLedger() {
            @Override
            public synchronized List<Rec> recordsOfKind(String decision, String... kinds) {
                throw new IllegalStateException("disk gone");
            }

            @Override
            public synchronized List<Rec> records(String decision) {
                throw new IllegalStateException("disk gone");
            }
        }, new MemoryLevelStore(), REFUND);
        h.person = f -> "reject";
        Map<String, Object> out = h.runCase("r1", h.inputs("gold", 50));
        assertThat(out).containsEntry("verdict", "reject");
        assertThat(h.asked).hasSize(1);
        assertThat(h.asked.get(0)).doesNotContain("The agent proposes");
        assertThat(h.audit).contains("autonomy_degraded");
    }

    @Test
    void aChangedAgentStartsOverAndTheOldRecordStaysInTheLedger() {
        DecideHarness h = harness(REFUND);
        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        h.person = f -> "approve";
        for (int i = 0; i < 3; i++) h.runCase("w" + i, h.inputs("gold", 50));
        assertThat(h.runCase("s0", h.inputs("gold", 50))).containsEntry("verdict_level", "suggest");

        h.script = REFUND.replace("You are Triager.", "You are a stricter Triager.");
        Map<String, Object> out = h.runCase("n0", h.inputs("gold", 50));
        assertThat(out).containsEntry("verdict_level", "watch");
        assertThat(cases(h, "Refund")).hasSize(5);
        assertThat(cases(h, "Refund").get(4).epoch()).isEqualTo(2);
        assertThat(h.ledger.recordsOfKind("Refund", Rec.LEVEL).stream().anyMatch(r -> "epoch".equals(r.str("kind")))).isTrue();
    }

    @Test
    void aProviderThatSilentlyServesAnotherModelStartsANewEpoch() {
        DecideHarness h = harness(REFUND);
        h.agent = f -> new String[] {"approve", "fine", "0.9"};
        h.person = f -> "approve";
        for (int i = 0; i < 3; i++) h.runCase("w" + i, h.inputs("gold", 50));
        assertThat(h.runCase("s0", h.inputs("gold", 50))).containsEntry("verdict_level", "suggest");
        DecideHarness.servedModel = "m-v2";
        try {
            Map<String, Object> out = h.runCase("n0", h.inputs("gold", 50));
            assertThat(out).containsEntry("verdict_level", "watch");
            LevelLog.Standing s = new LevelLog("Refund", List.of("watch", "suggest", "act"), h.ledger).current("gold").orElseThrow();
            assertThat(s.epoch()).isEqualTo(2);
            assertThat(s.reason()).contains("served model changed from m to m-v2");
            assertThat(cases(h, "Refund").get(4).servedModel()).isEqualTo("m-v2");
        } finally {
            DecideHarness.servedModel = "m";
        }
    }

    // ---------- artifacts ----------

    @Test
    void aDecisionThatProposesAnArtifactRecordsAcceptedEditedAndRejected() {
        DecideHarness h = harness(PUBLISH);
        h.rawAgent = f -> "{\"post\": \"Hello wrld\", \"reasoning\": \"draft\"}";
        h.person = f -> "accept";
        Map<String, Object> first = h.runCase("p1", h.inputs("x", 1));
        assertThat(first).containsEntry("post", "Hello wrld").containsEntry("post_level", "edit");
        assertThat(h.asked.get(0)).contains("review the post").contains("Hello wrld").contains("The agent proposes");

        h.person = q -> "Hello world";
        Map<String, Object> second = h.runCase("p2", h.inputs("x", 1));
        assertThat(second).containsEntry("post", "Hello world").containsEntry("post_proposal", "Hello wrld");

        h.person = q -> "reject";
        Map<String, Object> third = h.runCase("p3", h.inputs("x", 1));
        assertThat(third).containsEntry("post", "");

        List<LCase> cases = cases(h, "Publish");
        assertThat(cases).extracting(LCase::responseKind).containsExactly("accept", "edit", "reject");
        assertThat(cases.get(1).proposalValue()).isEqualTo("Hello wrld");
        assertThat(cases.get(1).responseFinal()).isEqualTo("Hello world");
        assertThat(cases.get(0).shown()).isTrue();
    }

    @Test
    void anArtifactAgentThatKeepsBeingAcceptedGainsTheRightToPublishOnItsOwn() {
        DecideHarness h = harness(PUBLISH);
        h.rawAgent = f -> "{\"post\": \"A fine post\", \"reasoning\": \"draft\"}";
        h.person = f -> "accept";
        for (int i = 0; i < 3; i++) h.runCase("p" + i, h.inputs("x", 1));
        int asked = h.asked.size();
        Map<String, Object> out = h.runCase("auto", h.inputs("x", 1));
        assertThat(out).containsEntry("post", "A fine post").containsEntry("post_level", "publish");
        assertThat(h.asked).hasSize(asked);
        LCase last = cases(h, "Publish").get(3);
        assertThat(last.responder()).isEqualTo("agent");
        assertThat(last.responseKind()).isEqualTo("accept");
    }

    @Test
    void anArtifactAgentThatReturnsNothingUsableIsSentToAPersonEvenAtTheTopRung() {
        DecideHarness h = harness(PUBLISH);
        h.rawAgent = f -> "{\"post\": \"A fine post\", \"reasoning\": \"draft\"}";
        h.person = f -> "accept";
        for (int i = 0; i < 3; i++) h.runCase("p" + i, h.inputs("x", 1));
        h.rawAgent = f -> "{\"reasoning\": \"I could not\"}";
        int asked = h.asked.size();
        h.runCase("bad", h.inputs("x", 1));
        assertThat(h.asked).hasSize(asked + 1);
        LCase last = cases(h, "Publish").get(3);
        assertThat(last.how()).isEqualTo("unusable proposal");
        assertThat(last.level()).isEqualTo("publish");
        assertThat(last.malformed()).isTrue();
    }

    @Test
    void anArtifactIsReadFromTheFieldNamedForItTheOneContentFieldOrTheWholeObject() {
        DecideHarness h = harness(PUBLISH);
        h.person = f -> "accept";
        h.rawAgent = f -> "{\"post\": \"named\", \"reasoning\": \"r\", \"confidence\": 0.4}";
        h.runCase("n1", h.inputs("x", 1));
        h.rawAgent = f -> "{\"post\": \"out of range\", \"reasoning\": \"r\", \"confidence\": 7}";
        h.runCase("n4", h.inputs("x", 1));
        DecideHarness other = harness(PUBLISH.replace("output_schema: { post: string, reasoning: string }", "output_schema: { body: string, title: string, reasoning: string }"));
        other.person = h.person;
        other.rawAgent = f -> "{\"body\": \"only content\", \"reasoning\": \"r\"}";
        other.runCase("n2", other.inputs("x", 1));
        other.rawAgent = f -> "{\"title\": \"T\", \"body\": \"B\", \"reasoning\": \"r\"}";
        other.runCase("n3", other.inputs("x", 1));
        List<LCase> cases = cases(h, "Publish");
        assertThat(cases.get(0).proposalValue()).isEqualTo("named");
        assertThat(cases.get(0).confidence()).isEqualTo(0.4);
        assertThat(cases.get(1).confidence()).isNull();
        List<LCase> second = cases(other, "Publish");
        assertThat(second.get(0).proposalValue()).isEqualTo("only content");
        assertThat(second.get(1).proposalValue()).contains("\"title\":\"T\"").contains("\"body\":\"B\"");
    }

    @Test
    void aTaskTemplateFillsTheArtifactRequestFromTheCasesValues() {
        DecideHarness h = harness(PUBLISH.replace("remember: topic", "remember: topic").replace("ask: editors", "ask: editors\n task: \"Write about {reason} for {customer_since}\""));
        h.rawAgent = f -> "{\"post\": \"p\", \"reasoning\": \"r\"}";
        h.person = f -> "accept";
        h.runCase("t1", h.inputs("x", 1));
        assertThat(h.tasks.get(0)).contains("Write about damaged for 2020");
        DecideHarness plain = harness(PUBLISH);
        plain.rawAgent = h.rawAgent;
        plain.person = h.person;
        plain.runCase("t2", plain.inputs("x", 1));
        assertThat(plain.tasks.get(0)).contains("Produce the post for Publish.");
    }

    @Test
    void anAgentThatCannotProposeIsReportedAndAPersonStillDecides() {
        DecideHarness h = harness(PUBLISH);
        h.rawAgent = f -> "not json at all";
        h.person = f -> "I wrote this myself";
        Map<String, Object> out = h.runCase("bad1", h.inputs("x", 1));
        assertThat(out.get("post")).isEqualTo("I wrote this myself");
        LCase c = cases(h, "Publish").get(0);
        assertThat(c.malformed() || c.proposalValue() != null).isTrue();
        assertThat(c.responseKind()).isEqualTo("edit");
    }
}
