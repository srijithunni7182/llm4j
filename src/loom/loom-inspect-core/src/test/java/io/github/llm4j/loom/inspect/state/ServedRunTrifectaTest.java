package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 32e, R37: a run that other agents started through an {@code expose} block has had its input
 * written by outsiders, so for the trifecta every agent in it has read untrusted content from the
 * start. Two legs of the usual three are then enough to be one tool from the third.
 */
class ServedRunTrifectaTest {

    private final SecurityFixtures f = new SecurityFixtures();

    private void runStart(boolean served) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_RUN_START);
        a.put(Semconv.LOOM_SEQ, 0L);
        a.put(Semconv.LOOM_RUN_ID, SecurityFixtures.RUN);
        a.put(Semconv.LOOM_WORKFLOW, "Total");
        if (served) a.put(Semconv.LOOM_SERVED, true);
        f.state.apply("i_0", new LogRec(SecurityFixtures.at(0), LogRec.INFO, "workflow Total", a, SecurityFixtures.TRACE, null));
    }

    @Test
    void inAServedRunPrivateDataAndASendAreTheWholeTrifecta() {
        runStart(true);
        f.tool(1, "Clerk", "CustomerDB", "private");
        f.tool(2, "Clerk", "SendEmail", "outward");

        assertThat(f.incidents("TRIFECTA")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("severity", "high").containsEntry("agent", "Clerk");
            assertThat(SecurityFixtures.detail(i)).contains("exposed workflow input");
        });
    }

    @Test
    void inARunNobodyOutsideStartedTheSameTwoLegsRaiseNothing() {
        runStart(false);
        f.tool(1, "Clerk", "CustomerDB", "private");
        f.tool(2, "Clerk", "SendEmail", "outward");

        assertThat(f.incidents("TRIFECTA")).isEmpty();
    }

    @Test
    void aServedRunThatTouchesNothingPrivateRaisesNothing() {
        runStart(true);
        f.tool(1, "Clerk", "Search", "untrusted");
        f.tool(2, "Clerk", "SendEmail", "outward");

        assertThat(f.incidents("TRIFECTA")).isEmpty();
    }
}
