package io.github.llm4j.provider.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.Recency;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Gemini refuses a {@code timeRangeFilter} whose times carry sub-second digits (HTTP 400
 * "Granularity of nano is not supported"). A clock with microsecond or nanosecond precision made
 * every grounded request with {@code recency:} fail, so the times sent must be whole seconds.
 */
class GoogleRecencyWholeSecondsTest {
    private static final Instant NANOS = Instant.parse("2026-10-29T10:15:24.123456789Z");

    private static com.fasterxml.jackson.databind.JsonNode filterFor(Recency recency) {
        ArrayNode tools = new ObjectMapper().createArrayNode();
        GoogleGrounding.addTool(tools, Grounding.webSearch().recency(recency).build(), NANOS);
        return tools.get(0).path("googleSearch").path("timeRangeFilter");
    }

    @Test
    void bothTimesCarryWholeSecondsWhateverThePrecisionOfTheClock() {
        var filter = filterFor(Recency.WEEK);

        assertThat(filter.path("startTime").asText()).isEqualTo("2026-10-22T10:15:24Z");
        assertThat(filter.path("endTime").asText()).isEqualTo("2026-10-29T10:15:24Z");
    }

    @Test
    void everyRecencyGivesWholeSeconds() {
        for (Recency recency : Recency.values()) {
            var filter = filterFor(recency);
            assertThat(filter.path("startTime").asText()).matches("[^.]*Z");
            assertThat(filter.path("endTime").asText()).matches("[^.]*Z");
        }
    }
}
