package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChangesMeansChangesEverywhereTest {

    static final String SCRIPT =
            "tool Orders { use: http base_url: \"https://a.example\" methods: \"GET,POST\" effect: reads }\n"
                    + "tool Pay { use: http base_url: \"https://p.example\" methods: \"GET\" effect: changes }\n"
                    + "agent A { model: \"m\" system: \"s\" tools: [Orders, Pay] approve: [Pay] }\n"
                    + "workflow Main(q) { delegate \"{q}\" to A -> r }\n";

    @Test
    void theAuditListsOnlyTheToolsThatChangeSomethingAsNeedingApproval() {
        String unapproved = SCRIPT.replace(" approve: [Pay]", "");
        var report =
                io.github.llm4j.loom.security.SecurityAudit.audit(
                        EffectsFixture.parse(unapproved), "t.loom");
        assertThat(report.agents())
                .singleElement()
                .satisfies(a -> assertThat(a.unapprovedEffects()).containsExactly("Pay"));
    }

    @Test
    void approvingTheChangingToolLeavesNothingUnapproved() {
        var report =
                io.github.llm4j.loom.security.SecurityAudit.audit(
                        EffectsFixture.parse(SCRIPT), "t.loom");
        assertThat(report.agents().get(0).unapprovedEffects()).isEmpty();
    }
}
