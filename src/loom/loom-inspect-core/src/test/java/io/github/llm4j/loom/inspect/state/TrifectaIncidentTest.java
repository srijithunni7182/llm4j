package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** R33, R42: one agent that has read untrusted content, reached private data and sent something out, in one run. */
class TrifectaIncidentTest {

    private final SecurityFixtures f = new SecurityFixtures();

    @Test
    void oneLegOrTwoLegsRaiseNothing() {
        f.tool(1, "Support", "Fetch", "untrusted");
        f.tool(2, "Support", "CustomerDB", "private");
        assertThat(f.incidents("TRIFECTA")).isEmpty();
        assertThat(f.agent("Support")).containsEntry("verdict", "two");
    }

    @Test
    void allThreeLegsInOneRunIsHighAndNamesTheToolOfEachLeg() {
        f.tool(1, "Support", "Fetch", "untrusted");
        f.tool(2, "Support", "CustomerDB", "private");
        f.tool(3, "Support", "SendEmail", "outward");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "high").containsEntry("agent", "Support").containsEntry("state", "open");
            assertThat(SecurityFixtures.detail(i)).contains("Fetch").contains("CustomerDB").contains("SendEmail");
        });
        assertThat(f.agent("Support")).containsEntry("verdict", "exercised");
    }

    @Test
    void theOrderOfTheLegsDoesNotMatter() {
        f.tool(1, "A", "Send", "outward");
        f.tool(2, "A", "Fetch", "untrusted");
        f.tool(3, "A", "DB", "private");
        assertThat(f.incidents("TRIFECTA")).hasSize(1);
    }

    @Test
    void aSingleToolThatReachesAllThreeIsEnough() {
        f.tool(1, "A", "Swiss", "outward,private,untrusted");
        assertThat(f.incidents("TRIFECTA")).hasSize(1);
    }

    @Test
    void whenAnyLegIsOnlyAnAssumptionItIsAWarningThatSaysSo() {
        f.tool(1, "A", "Mine", "outward,private,untrusted", false);
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "warn");
            assertThat(SecurityFixtures.detail(i)).contains("assumed");
        });
        assertThat(f.agent("A")).containsEntry("assumed", true).containsEntry("verdict", "exercised");
    }

    @Test
    void aKnownToolForEveryLegMakesItHighEvenIfAnAssumedToolWasAlsoUsed() {
        f.tool(1, "A", "Mine", "outward,private,untrusted", false);
        f.tool(2, "A", "Fetch", "untrusted");
        f.tool(3, "A", "DB", "private");
        f.tool(4, "A", "Send", "outward");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "high"));
    }

    @Test
    void eachAgentIsJudgedAlone() {
        f.tool(1, "A", "Fetch", "untrusted");
        f.tool(2, "B", "DB", "private");
        f.tool(3, "C", "Send", "outward");
        assertThat(f.incidents("TRIFECTA")).isEmpty();
        f.tool(4, "A", "DB", "private");
        f.tool(5, "A", "Send", "outward");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i).containsEntry("agent", "A"));
    }

    @Test
    void aCallTheRuntimeNeverRanCountsForNothing() {
        f.tool(1, "A", "Fetch", "untrusted");
        f.tool(2, "A", "DB", "private");
        f.failed(3, "A", "Send", "outward", "repeat_blocked", true);
        assertThat(f.incidents("TRIFECTA")).isEmpty();
    }

    @Test
    void aFailedCallThatRanStillReachedTheLeg() {
        f.tool(1, "A", "Fetch", "untrusted");
        f.tool(2, "A", "DB", "private");
        f.failed(3, "A", "Send", "outward", "server_error", false);
        assertThat(f.incidents("TRIFECTA")).hasSize(1);
    }

    @Test
    void itStaysOpenAfterTheRunEnds() {
        f.tool(1, "A", "Swiss", "outward,private,untrusted");
        f.runEnd(5, "completed");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i).containsEntry("state", "open"));
    }

    @Test
    void aTrifectaThatRestsOnAnAssumptionClosesWithTheRun() {
        f.tool(1, "A", "Mine", "outward,private,untrusted", false);
        f.runEnd(5, "completed");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i).containsEntry("state", "closed"));
    }

    @Test
    void aTrifectaThatLaterRestsOnKnownToolsIsHighAndStaysAfterTheRun() {
        f.tool(1, "A", "Mine", "outward,private,untrusted", false);
        f.tool(2, "A", "Fetch", "untrusted");
        f.tool(3, "A", "DB", "private");
        f.tool(4, "A", "Send", "outward");
        f.runEnd(5, "completed");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "high").containsEntry("state", "open"));
    }

    @Test
    void anAcceptedAgentRaisesInfoWithTheReasonAndIsStillListed() {
        f.accept("Support", "customer support: reads tickets, looks up the account, replies");
        f.tool(1, "Support", "Fetch", "untrusted");
        f.tool(2, "Support", "CustomerDB", "private");
        f.tool(3, "Support", "SendEmail", "outward");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "info");
            assertThat(SecurityFixtures.detail(i)).contains("customer support: reads tickets");
        });
        assertThat(f.agent("Support")).containsEntry("verdict", "accepted");
    }

    @Test
    void anotherAgentIsNotCoveredByTheAcceptance() {
        f.accept("Support", "reviewed");
        f.tool(1, "Billing", "Swiss", "outward,private,untrusted");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i.get("severity")).isNotEqualTo("info"));
    }

    @Test
    void theStarBlockAcceptsForEveryoneWhoSaysNothingElse() {
        f.state.setRules(java.util.Map.of("*", new InspectRules(null, null, null, null, null, null, null, null, null, new InspectRules.Security(null, null, null, "all reviewed"))));
        f.tool(1, "Anyone", "Swiss", "outward,private,untrusted");
        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "info"));
    }

    @Test
    void whatTheScriptCanDoShowsNextToWhatWasDone() {
        f.state.setSecurityProfile(() -> java.util.List.of(java.util.Map.of("agent", "Billing", "untrusted", java.util.List.of("Fetch"), "private", java.util.List.of("DB"), "outward", java.util.List.of("Refund"))));
        f.tool(1, "Billing", "Fetch", "untrusted");
        java.util.Map<String, Object> a = f.agent("Billing");
        assertThat(a).containsKey("can");
        assertThat(a).containsEntry("verdict", "armed");
        assertThat(f.incidents("TRIFECTA")).isEmpty();
    }

    @Test
    void anAgentThatNeverCalledAToolButCanDoAllThreeIsArmed() {
        f.state.setSecurityProfile(() -> java.util.List.of(java.util.Map.of("agent", "Billing", "untrusted", java.util.List.of("Fetch"), "private", java.util.List.of("DB"), "outward", java.util.List.of("Refund"))));
        assertThat(f.agent("Billing")).containsEntry("verdict", "armed");
    }

    @Test
    void withoutAProfileNothingIsGuessedAboutWhatAgentsCanDo() {
        f.tool(1, "A", "Fetch", "untrusted");
        assertThat(f.agent("A")).doesNotContainKey("can").containsEntry("verdict", "one");
    }
}
