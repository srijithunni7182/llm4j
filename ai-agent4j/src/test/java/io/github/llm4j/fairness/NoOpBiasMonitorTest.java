package io.github.llm4j.fairness;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NoOpBiasMonitorTest {

    @Test
    void testNoOpBehavior() {
        NoOpBiasMonitor monitor = new NoOpBiasMonitor();
        List<BiasEvent> events = monitor.detectBias("Any text", BiasContext.empty());

        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testShouldIntervene() {
        NoOpBiasMonitor monitor = new NoOpBiasMonitor();
        assertFalse(monitor.shouldIntervene(List.of()));

        BiasEvent lowEvent = BiasEvent.builder()
                .type(BiasType.OTHER)
                .severity(BiasSeverity.LOW)
                .text("text")
                .build();
        assertFalse(monitor.shouldIntervene(List.of(lowEvent)));

        BiasEvent highEvent = BiasEvent.builder()
                .type(BiasType.OTHER)
                .severity(BiasSeverity.HIGH)
                .text("text")
                .build();
        assertTrue(monitor.shouldIntervene(List.of(highEvent)));
    }
}
