package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R32, R36: the security section of a run's view. */
class SecurityViewTest {

    private final SecurityFixtures f = new SecurityFixtures();

    @Test
    @SuppressWarnings("unchecked")
    void theViewAlwaysHasASecuritySectionEvenForAQuietRun() {
        Map<String, Object> s = f.security();
        assertThat(s).containsKeys("agents", "injection", "access", "guards");
        assertThat((List<?>) s.get("agents")).isEmpty();
        assertThat((Map<String, Object>) s.get("guards"))
                .containsEntry("pii_masked", 0L)
                .containsEntry("bias", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachAgentShowsWhichToolsServedWhichLeg() {
        f.tool(1, "Support", "Fetch", "untrusted");
        f.tool(2, "Support", "Fetch", "untrusted");
        f.tool(3, "Support", "DB", "private");
        Map<String, Object> used = (Map<String, Object>) f.agent("Support").get("used");
        assertThat((List<String>) used.get("untrusted")).containsExactly("Fetch");
        assertThat((List<String>) used.get("private")).containsExactly("DB");
        assertThat((List<String>) used.get("outward")).isEmpty();
        assertThat(f.agent("Support"))
                .containsEntry("assumed", false)
                .containsEntry("verdict", "two");
    }

    @Test
    void aToolWithNoReachAtAllCountsForNoLeg() {
        f.tool(1, "A", "Calc", "");
        assertThat(f.agent("A")).containsEntry("verdict", "none");
    }

    @Test
    void aTraceWithoutReachSaysNothingAboutLegs() {
        f.tool(1, "A", "Old", null);
        assertThat(f.agent("A")).containsEntry("verdict", "none");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGuardCountsComeFromTheGuardEvents() {
        f.guard(1, "A", "pii_masked");
        f.guard(2, "A", "pii_masked");
        f.guard(3, "A", "pii_blocked");
        f.guard(4, "A", "pii_detected");
        f.guard(5, "A", "bias_detected");
        f.guard(6, "A", "bias_blocked");
        Map<String, Object> g = (Map<String, Object>) f.security().get("guards");
        assertThat(g)
                .containsEntry("pii_masked", 2L)
                .containsEntry("pii_blocked", 1L)
                .containsEntry("pii_detected", 1L)
                .containsEntry("bias", 2L);
    }

    @Test
    void aGuardEventWithoutANameIsIgnored() {
        f.guard(1, "A", null);
        assertThat((Map<String, Object>) f.security().get("guards"))
                .containsEntry("pii_masked", 0L);
    }

    @Test
    void theOldViewFieldsAreStillThere() {
        f.tool(1, "A", "Fetch", "untrusted");
        assertThat(f.state.view()).containsKeys("tools", "tasks", "incidents", "models");
    }
}
