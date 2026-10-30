package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 19 R18, context loss at a handoff: plant a fact before the work passes to another agent
 * and assert the receiving agent was told it. {@code expect.told} says what an agent was told over
 * the run, so a handoff that quietly drops the fact is a failed case, not a confident answer built
 * on a missing input.
 */
class HandoffContextProbeTest {

    @TempDir Path dir;

    private static final String KEEPS =
            """
            agent Triage { model: "m"  system: "Classify." }
            agent Billing { model: "m"  system: "Handle billing." }
            workflow Main(customer, issue) {
                delegate "Classify: {issue}" to Triage -> kind
                delegate "Handle {kind} for customer {customer}: {issue}" to Billing -> reply
                return reply
            }
            """;

    private static final String DROPS = KEEPS.replace(" for customer {customer}", "");

    private static String cases() {
        return "script: ../main.loom\nworkflow: Main\ncases:\n"
                + "  - name: the fact reaches the receiving agent\n"
                + "    input: { customer: 'customer 4417', issue: double charge }\n"
                + "    models: { Triage: billing, Billing: handled }\n"
                + "    expect:\n"
                + "      told: { Billing: [ 'customer 4417', 'double charge' ] }\n";
    }

    @Test
    void aHandoffThatKeepsTheFactPassesTheProbe() throws Exception {
        var results = Wtest.run(dir, KEEPS, cases());

        assertThat(results).allSatisfy(r -> assertThat(r.failures()).as(r.name()).isEmpty());
    }

    @Test
    void aHandoffThatDropsTheFactFailsTheProbeAndNamesWhatWasMissing() throws Exception {
        var results = Wtest.run(dir, DROPS, cases());

        assertThat(results.get(0).failures()).singleElement().asString().contains("told.Billing").contains("customer 4417");
    }

    @Test
    void anAgentTheScriptDoesNotHaveIsAProblemOfTheTestFile() throws Exception {
        var suite = Wtest.load(dir, KEEPS, cases().replace("Billing: [", "Nobody: ["));

        assertThat(suite.problems()).anyMatch(p -> p.contains("expect.told: 'Nobody' is not an agent"));
    }
}
