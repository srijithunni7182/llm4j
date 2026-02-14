package io.github.llm4j.fairness;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BiasEventTest {

    @Test
    void testBuilderAndGetters() {
        Instant now = Instant.now();
        BiasEvent event = BiasEvent.builder()
                .type(BiasType.GENDER)
                .severity(BiasSeverity.HIGH)
                .text("biased text")
                .explanation("contains gender steering")
                .confidence(0.95)
                .timestamp(now)
                .addMetadata("engine", "test-v1")
                .build();

        assertEquals(BiasType.GENDER, event.getType());
        assertEquals(BiasSeverity.HIGH, event.getSeverity());
        assertEquals("biased text", event.getText());
        assertEquals("contains gender steering", event.getExplanation());
        assertEquals(0.95, event.getConfidence());
        assertEquals(now, event.getTimestamp());
        assertEquals("test-v1", event.getMetadata().get("engine"));
    }

    @Test
    void testDefaultTimestampAndConfidence() {
        BiasEvent event = BiasEvent.builder()
                .type(BiasType.RACIAL)
                .severity(BiasSeverity.LOW)
                .text("some text")
                .build();

        assertNotNull(event.getTimestamp());
        assertEquals(0.5, event.getConfidence());
    }

    @Test
    void testToString() {
        BiasEvent event = BiasEvent.builder()
                .type(BiasType.RELIGIOUS)
                .severity(BiasSeverity.MEDIUM)
                .text("text")
                .explanation("expl")
                .confidence(0.8)
                .build();

        String str = event.toString();
        assertTrue(str.contains("RELIGIOUS"));
        assertTrue(str.contains("MEDIUM"));
        assertTrue(str.contains("0.8"));
        assertTrue(str.contains("expl"));
    }

    @Test
    void testMetadataImmutability() {
        BiasEvent.Builder builder = BiasEvent.builder()
                .type(BiasType.AGE)
                .severity(BiasSeverity.LOW)
                .text("text");

        BiasEvent event = builder.build();
        Map<String, Object> metadata = event.getMetadata();

        assertThrows(UnsupportedOperationException.class, () -> metadata.put("new", "val"));
    }
}
