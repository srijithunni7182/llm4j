package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.ladder.LevelLog;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verification F13 for a ladder: "when the agent changes: test it on past cases". The old agent's
 * past cases are replayed under the new one, the replay is judged by the ladder's own conditions,
 * and the new agent starts on the rung that supports, at most the rung the old agent had. The
 * replay writes nothing to the ledger and asks nobody.
 */
@Tag("L7")
class LadderEpochReplayTest {

    static final String SCRIPT =
            """
            agent Summarizer { model: "m" system: "You are Summarizer." }
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L {
                rung watch { mode: blind }
                rung suggest { mode: confirm  climb when history.count >= 3 and history.rate(agrees) >= 0.7 }
                rung act { mode: autonomous  climb when history.count >= 5 and history.rate(agrees) >= 0.9  slip when history.last(5).rate(agrees) < 0.5  sample 10% blind }
                climbing is automatic
            }
            decision Refund {
                proposed by: Triager
                choices: approve, reject, escalate
                group cases by: tier
                remember: amount, reason, customer_since
                ask: support-lead
                ladder: L
                when the agent changes: test it on past cases
            }
            workflow Triage(ticket, tier, amount, reason, customer_since) {
                delegate "Summarise {ticket}" to Summarizer -> summary
                decide Refund -> verdict
                note "{verdict}"
            }
            """;

    @TempDir Path dir;

    @Test
    void theNewAgentStartsOnTheRungItsReplayEarnsAtMostWhatTheOldOneHadAndTheReplayWritesNothing()
            throws Exception {
        for (String quality : new String[] {"same", "worse", "toofew"}) {
            Path work = Files.createDirectories(dir.resolve(quality));
            DecideHarness live =
                    new DecideHarness(work, new MemoryLedger(), new MemoryLevelStore(), SCRIPT);
            ReplayHarness h = new ReplayHarness(live, work);
            h.seedCases(quality.equals("toofew") ? 4 : 12);
            h.candidateAgent =
                    quality.equals("worse")
                            ? f -> new String[] {"escalate", "unsure", "0.1"}
                            : f ->
                                    new String[] {
                                        Integer.parseInt(f.get("amount")) < 100
                                                ? "approve"
                                                : "reject",
                                        "good",
                                        "0.9"
                                    };
            Path newScript =
                    h.script(
                            "new-agent.loom",
                            SCRIPT.replace("You are Triager.", "You are the new Triager."));
            live.seed("gold", Level.SUGGEST); // what the old agent had earned

            var def = live.parsed().getDecisions().get(0);
            LadderPolicy policy = LadderPolicy.of(live.parsed(), def);
            EpochReplay replay = new EpochReplay(h.engine, newScript);
            LadderEngine engine = new LadderEngine(policy, live.ledger, live.clock);
            engine.inheritWith(
                    (scope, old, identity) ->
                            policy.indexOf(
                                    replay.inheritedRung(
                                                    def,
                                                    scope,
                                                    old.rungName(),
                                                    old.epoch(),
                                                    identity)
                                            .rung()));
            int recordsBefore = live.ledger.records("Refund").size();
            int askedBefore = live.asked.size();

            LevelLog.Standing next = engine.standing("gold", "a-new-identity", null);

            String where = quality + ": " + next.reason();
            assertThat(next.epoch()).as(where).isEqualTo(2);
            switch (quality) {
                case "same" -> assertThat(next.rungName())
                        .as(where)
                        .isEqualTo(
                                "suggest"); // earned act on the replay; held to what the old agent
                    // had
                case "worse" -> assertThat(next.rungName()).as(where).isEqualTo("watch");
                default -> assertThat(next.rungName()).as(where).isEqualTo("watch");
            }
            assertThat(next.reason()).contains("test it on past cases");
            // the replay itself: no ledger record of its own beyond the epoch's, nobody asked, a
            // directory of its own
            assertThat(live.ledger.records("Refund").size() - recordsBefore)
                    .as("only the new epoch's level record")
                    .isEqualTo(1);
            assertThat(live.asked).hasSize(askedBefore);
            assertThat(Files.list(h.store.resolve("replays")).count()).isEqualTo(1);
        }
    }

    @Test
    void theReportOfAReplayOfALadderSaysWhichRungTheCandidateEarnsAndWhy() throws Exception {
        DecideHarness live =
                new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), SCRIPT);
        ReplayHarness h = new ReplayHarness(live, dir);
        h.seedCases(12);
        h.candidateAgent =
                f ->
                        new String[] {
                            Integer.parseInt(f.get("amount")) < 100 ? "approve" : "reject",
                            "good",
                            "0.9"
                        };
        Path newScript =
                h.script(
                        "new-agent.loom",
                        SCRIPT.replace("You are Triager.", "You are the new Triager."));
        ReplayReport report = h.engine.run("Refund", h.options(newScript), null);
        assertThat(report.levelEarned).isEqualTo("act");
        assertThat(report.levelEarnedWhy)
                .contains("every condition up to act is met by 12 replayed cases");
        assertThat(report.markdown())
                .contains("## The level the candidate would earn")
                .contains("act: every condition");
        assertThat(report.flips)
                .as("the candidate disagrees with the old agent on the cases between 100 and 299")
                .isNotEmpty();

        h.candidateAgent = f -> new String[] {"escalate", "unsure", "0.1"};
        ReplayReport worse = h.engine.run("Refund", h.options(newScript), null);
        assertThat(worse.levelEarned).isEqualTo("watch");
        assertThat(worse.levelEarnedWhy)
                .contains("not enough for suggest")
                .contains("history.rate");
    }
}
