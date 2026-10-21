package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * R35: a tool that keeps refusing access, a call to a tool the agent does not have, and approvals
 * that were refused.
 */
class AccessIncidentTest {

    private final SecurityFixtures f = new SecurityFixtures();

    @Test
    void twoRefusalsInASpanAreNotEnough() {
        f.failed(1, "R", "CustomerDB", "private", "permission_denied", false);
        f.failed(20, "R", "CustomerDB", "private", "permission_denied", false);
        assertThat(f.incidents("ACCESS")).isEmpty();
    }

    @Test
    void threeInSixtySecondsIsAWarning() {
        f.failed(1, "R", "CustomerDB", "private", "permission_denied", false);
        f.failed(20, "R", "CustomerDB", "private", "authentication", false);
        f.failed(40, "R", "CustomerDB", "private", "permission_denied", false);
        assertThat(f.incidents("ACCESS"))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i)
                                    .containsEntry("severity", "warn")
                                    .containsEntry("state", "open");
                            assertThat(i.get("title").toString()).contains("CustomerDB");
                            assertThat(SecurityFixtures.detail(i)).contains("3");
                        });
    }

    @Test
    void threeRefusalsSpreadOverMoreThanTheSpanAreNot() {
        f.failed(1, "R", "CustomerDB", "private", "permission_denied", false);
        f.failed(50, "R", "CustomerDB", "private", "permission_denied", false);
        f.failed(110, "R", "CustomerDB", "private", "permission_denied", false);
        assertThat(f.incidents("ACCESS")).isEmpty();
    }

    @Test
    void otherFailuresAreNotRefusals() {
        for (int i = 0; i < 5; i++) f.failed(i + 1, "R", "CustomerDB", "private", "timeout", false);
        assertThat(f.incidents("ACCESS")).isEmpty();
    }

    @Test
    void theToolAnsweringAgainClosesIt() {
        for (int i = 0; i < 3; i++)
            f.failed(i + 1, "R", "CustomerDB", "private", "permission_denied", false);
        assertThat(f.incidents("ACCESS")).hasSize(1);
        f.tool(10, "R", "CustomerDB", "private");
        assertThat(f.incidents("ACCESS").get(0)).containsEntry("state", "closed");
    }

    @Test
    void theThresholdIsARule() {
        f.rules("R", new InspectRules.Security(2, 30L, null, null));
        f.failed(1, "R", "CustomerDB", "private", "permission_denied", false);
        f.failed(10, "R", "CustomerDB", "private", "permission_denied", false);
        assertThat(f.incidents("ACCESS")).hasSize(1);
    }

    @Test
    void aCallToAToolTheAgentDoesNotHaveIsInfoAndStaysUntilTheRunEnds() {
        f.failed(1, "R", "DeleteRecord", null, "unknown_tool", true);
        assertThat(f.incidents("ACCESS"))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i)
                                    .containsEntry("severity", "info")
                                    .containsEntry("agent", "R")
                                    .containsEntry("state", "open");
                            assertThat(i.get("title").toString()).contains("DeleteRecord");
                        });
        f.runEnd(5, "completed");
        assertThat(f.incidents("ACCESS").get(0)).containsEntry("state", "closed");
    }

    @Test
    void aRefusedApprovalIsCountedAndRaisesNothing() {
        f.failed(1, "R", "SendEmail", "outward", "rejected_by_human", true);
        f.failed(2, "R", "SendEmail", "outward", "rejected_by_human", true);
        f.failed(3, "R", "SendEmail", "outward", "approval_unavailable", true);
        assertThat(f.incidents("ACCESS")).isEmpty();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> access =
                (java.util.Map<String, Object>) f.security().get("access");
        assertThat(access).containsEntry("approvalsRefused", 3L);
    }

    @Test
    void theViewCountsRefusalsByCategory() {
        f.failed(1, "R", "A", "private", "permission_denied", false);
        f.failed(2, "R", "A", "private", "permission_denied", false);
        f.failed(3, "R", "B", "private", "authentication", false);
        f.failed(4, "R", "Nope", null, "unknown_tool", true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> access =
                (java.util.Map<String, Object>) f.security().get("access");
        assertThat(access).containsEntry("refused", 3L).containsEntry("unknownTools", 1L);
        assertThat((java.util.Map<String, Object>) access.get("byCategory"))
                .containsEntry("permission_denied", 2L)
                .containsEntry("authentication", 1L);
    }
}
