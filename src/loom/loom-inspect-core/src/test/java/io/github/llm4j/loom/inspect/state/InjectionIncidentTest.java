package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** R34, R43: a result that read like instructions, and what the agent did next. */
class InjectionIncidentTest {

    private final SecurityFixtures f = new SecurityFixtures();

    @Test
    void aCleanResultRaisesNothing() {
        f.tool(1, "A", "Fetch", "untrusted");
        assertThat(f.incidents("INJECTION")).isEmpty();
    }

    @Test
    void aSuspectResultIsAWarningThatNamesTheGroupsAndTheSourceNeverTheText() {
        f.suspect(1, "A", "Fetch", "untrusted", "exfil,override", 3);
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "warn").containsEntry("agent", "A");
            String detail = SecurityFixtures.detail(i);
            assertThat(detail).contains("exfil").contains("override").contains("Fetch");
        });
    }

    @Test
    void aHarmlessKnownCallAfterwardsLeavesItAWarning() {
        f.tool(1, "A", "Notes", "");
        f.suspect(2, "A", "Fetch", "untrusted", "override", 1);
        f.tool(3, "A", "Notes", "");
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "warn"));
    }

    @Test
    void anOutwardCallRightAfterwardsMakesItHigh() {
        f.suspect(1, "A", "Fetch", "untrusted", "exfil", 1);
        f.tool(2, "A", "SendEmail", "outward");
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "high");
            assertThat(SecurityFixtures.detail(i)).contains("SendEmail");
        });
    }

    @Test
    void aToolTheAgentHadNotCalledBeforeMakesItHigh() {
        f.tool(1, "A", "Notes", "");
        f.suspect(2, "A", "Fetch", "untrusted", "override", 1);
        f.tool(3, "A", "DeleteRecord", "private");
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "high"));
    }

    @Test
    void anotherAgentsNextCallDoesNotEscalateIt() {
        f.suspect(1, "A", "Fetch", "untrusted", "override", 1);
        f.tool(2, "B", "SendEmail", "outward");
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "warn"));
    }

    @Test
    void onlyTheNextCallIsJudged() {
        f.tool(1, "A", "Notes", "");
        f.suspect(2, "A", "Fetch", "untrusted", "override", 1);
        f.tool(3, "A", "Notes", "");
        f.tool(4, "A", "SendEmail", "outward"); // a later call: not the one that followed the suspect result
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "warn"));
    }

    @Test
    void forAnAgentWithAnAcceptedTrifectaAnyFindingIsHigh() {
        f.accept("Support", "reviewed");
        f.suspect(1, "Support", "Fetch", "untrusted", "override", 1);
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "high");
            assertThat(SecurityFixtures.detail(i)).contains("accepted");
        });
    }

    @Test
    void itStaysOpenAfterTheRunEnds() {
        f.suspect(1, "A", "Fetch", "untrusted", "override", 1);
        f.runEnd(4, "completed");
        assertThat(f.incidents("INJECTION")).singleElement().satisfies(i -> assertThat(i).containsEntry("state", "open"));
    }

    @Test
    void theViewCountsFindingsByGroupAndSource() {
        f.suspect(1, "A", "Fetch", "untrusted", "exfil,override", 2);
        f.suspect(2, "A", "Fetch", "untrusted", "override", 1);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> inj = (java.util.Map<String, Object>) f.security().get("injection");
        assertThat(inj).containsEntry("suspect", 2L);
        assertThat((java.util.Map<String, Object>) inj.get("byGroup")).containsEntry("override", 2L).containsEntry("exfil", 1L);
        assertThat((java.util.Map<String, Object>) inj.get("bySource")).containsEntry("Fetch", 2L);
    }
}
