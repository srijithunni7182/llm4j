package io.github.llm4j.fairness;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

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

        BiasEvent lowEvent =
                BiasEvent.builder()
                        .type(BiasType.OTHER)
                        .severity(BiasSeverity.LOW)
                        .text("text")
                        .build();
        assertFalse(monitor.shouldIntervene(List.of(lowEvent)));

        BiasEvent highEvent =
                BiasEvent.builder()
                        .type(BiasType.OTHER)
                        .severity(BiasSeverity.HIGH)
                        .text("text")
                        .build();
        assertTrue(monitor.shouldIntervene(List.of(highEvent)));
    }
}
