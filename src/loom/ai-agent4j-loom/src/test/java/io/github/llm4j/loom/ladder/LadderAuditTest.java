package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.security.Finding;
import io.github.llm4j.loom.security.SecurityAudit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the checker warns about and what the audit reports for ladders that are legal but risky. */
@Tag("L9")
class LadderAuditTest {

    @TempDir
    Path dir;

    private List<Finding> audit(LadderHarness h) {
        return SecurityAudit.audit(h.script, "test.loom").findings().stream().filter(f -> f.rule().equals("LA16")).toList();
    }

    private static List<String> warnings(LadderHarness h) {
        return new ScriptValidator().validate(h.script, new ScriptValidator.Context().registeredTools(Set.of())).stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::toString).toList();
    }

    @Test
    void theAuditNamesEveryAutonomousRungThatCanBeReachedAndWhatIsMissingAroundIt() throws IOException {
        LadderHarness h = LadderHarness.fromResource("refund.loom", dir);
        assertThat(audit(h)).extracting(Finding::title).containsExactly("An autonomous rung can be reached");
        assertThat(audit(h).get(0).risk()).contains("rung act").contains("ceiling: act");

        LadderHarness bare = new LadderHarness(LadderChecksTest.AGENT + """
                ladder L {
                    rung a { mode: blind }
                    rung b { mode: autonomous climb when history.count >= 3 }
                    climbing is automatic
                }
                decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: b when the agent changes: keep the trust }
                workflow W() { decide D -> v note "{v}" }
                """);
        assertThat(audit(bare)).extracting(Finding::title).contains("An autonomous rung can be reached", "Climbing to act without a person", "Nothing takes the agent back down",
                "Acting with no ongoing check", "A changed agent keeps the old agent's trust");
    }

    @Test
    void aLadderThatCannotReachItsAutonomousRungIsNotFlaggedForIt() throws IOException {
        LadderHarness h = LadderHarness.fromResource("refund.loom", dir);
        LadderHarness capped = new LadderHarness(h.edited("ceiling:                act", "ceiling:                suggest"));
        assertThat(audit(capped)).isEmpty();
    }

    @Test
    void aClimbThatCountsEveryAnsweredCaseWarnsAboutAgreeableReviewers() {
        LadderHarness h = new LadderHarness(LadderChecksTest.AGENT + """
                measure agrees(c) = c.response.choice == c.proposal.choice
                ladder L {
                    rung review { mode: confirm }
                    rung auto { mode: autonomous climb when history.rate(agrees) >= 0.9 slip when history.rate(agrees) < 0.5 sample 5% blind }
                    climbing needs approval from boss
                }
                decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: auto when the agent changes: start over }
                workflow W() { decide D -> v note "{v}" }
                """);
        assertThat(warnings(h)).anyMatch(w -> w.contains("counts every case") && w.contains("history.where(careful)"));
        LadderHarness filtered = new LadderHarness(LadderChecksTest.AGENT + """
                measure agrees(c) = c.response.choice == c.proposal.choice
                ladder L {
                    rung review { mode: confirm }
                    rung auto { mode: autonomous climb when history.where(blind).rate(agrees) >= 0.9 slip when history.rate(agrees) < 0.5 sample 5% blind }
                    climbing needs approval from boss
                }
                decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: auto when the agent changes: start over }
                workflow W() { decide D -> v note "{v}" }
                """);
        assertThat(warnings(filtered)).noneMatch(w -> w.contains("counts every case"));
    }

    @Test
    void aLadderThatLooksFurtherBackThanTheRecordsAreKeptWarns() {
        LadderHarness h = new LadderHarness(LadderChecksTest.AGENT + """
                ladder L {
                    rung a { mode: blind }
                    rung b { mode: confirm climb when history.since(30d).count >= 5 }
                    climbing is automatic
                }
                decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: b keep records for: 14 days when the agent changes: start over }
                workflow W() { decide D -> v note "{v}" }
                """);
        assertThat(warnings(h)).anyMatch(w -> w.contains("keep records for 14 days") && w.contains("looks back 30d"));
    }

    @Test
    void testingOnPastCasesIsSaidNotToApplyYet() {
        LadderHarness h = new LadderHarness(LadderChecksTest.AGENT + """
                ladder L {
                    rung a { mode: blind }
                    rung b { mode: confirm climb when history.count >= 5 }
                    climbing is automatic
                }
                decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: b when the agent changes: test it on past cases }
                workflow W() { decide D -> v note "{v}" }
                """);
        assertThat(warnings(h)).anyMatch(w -> w.contains("not yet applied to a ladder") && w.contains("starts over"));
    }

    private static String retentionLadder(String climb) {
        return LadderChecksTest.AGENT + "measure ok(c) = c.response.kind == \"accept\"\nladder L {\n rung a { mode: blind }\n rung b { mode: confirm climb when " + climb + " }\n climbing is automatic\n}\n"
                + "decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: b keep records for: 14 days when the agent changes: start over }\n"
                + "workflow W() { decide D -> v note \"{v}\" }\n";
    }

    @Test
    void theLookBackIsFoundHoweverDeepInTheConditionItSits() {
        for (String climb : List.of("history.count >= 1 and not history.since(30d).any(ok)", "abs(history.since(30d).count) >= 1", "history.since(30d).span >= 2d",
                "history.count in [history.since(30d).count]", "-history.since(30d).count < 0", "history.where(ok).since(30d).count >= 1", "history.count(ok) >= history.since(30d).count")) {
            assertThat(warnings(new LadderHarness(retentionLadder(climb)))).as(climb).anyMatch(w -> w.contains("looks back 30d"));
        }
        assertThat(warnings(new LadderHarness(retentionLadder("history.since(7d).count >= 1")))).noneMatch(w -> w.contains("looks back"));
    }

    @Test
    void aFilterIsFoundHoweverDeepInTheConditionItSits() {
        String base = LadderChecksTest.AGENT + "measure agrees(c) = c.response.choice == c.proposal.choice\nladder L {\n rung a { mode: confirm }\n rung b { mode: autonomous climb when @C@ slip when history.rate(agrees) < 0.5 sample 5% blind }\n climbing needs approval from boss\n}\n"
                + "decision D { proposed by: Triager choices: x, y, escalate ask: p ladder: L ceiling: b when the agent changes: start over }\nworkflow W() { decide D -> v note \"{v}\" }\n";
        for (String climb : List.of("not history.where(blind).any(agrees)", "abs(history.where(blind).count) >= 1", "history.count >= 1 and history.where(blind).rate(agrees) >= 0.9",
                "history.count in [history.where(blind).count]", "-history.where(blind).count < 0", "history.rate(agrees) >= 0.9 and blind == blind")) {
            assertThat(warnings(new LadderHarness(base.replace("@C@", climb)))).as(climb).noneMatch(w -> w.contains("counts every case"));
        }
        assertThat(warnings(new LadderHarness(base.replace("@C@", "history.rate(agrees) >= 0.9")))).anyMatch(w -> w.contains("counts every case"));
    }
}
