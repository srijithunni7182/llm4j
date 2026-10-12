package io.github.llm4j.loom.inspect.otel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void idsAreLowercaseHexOfTheRightLength() {
        assertThat(Ids.traceId("r-7f3a")).matches("[0-9a-f]{32}");
        assertThat(Ids.spanId("r-7f3a", "Main/s1")).matches("[0-9a-f]{16}");
    }

    @Test
    void theSameInputGivesTheSameIdSoARunKeepsItsTraceAcrossRestarts() {
        assertThat(Ids.traceId("r-7f3a")).isEqualTo(Ids.traceId("r-7f3a"));
        assertThat(Ids.spanId("r-7f3a", "Main/s1")).isEqualTo(Ids.spanId("r-7f3a", "Main/s1"));
    }

    @Test
    void differentRunsAndStepsGetDifferentIds() {
        assertThat(Ids.traceId("r-1")).isNotEqualTo(Ids.traceId("r-2"));
        assertThat(Ids.spanId("r-1", "Main/s1")).isNotEqualTo(Ids.spanId("r-1", "Main/s2"));
        assertThat(Ids.spanId("r-1", "Main/s1")).isNotEqualTo(Ids.spanId("r-2", "Main/s1"));
        assertThat(Ids.spanId("r-1", "Main/s1~1")).isNotEqualTo(Ids.spanId("r-1", "Main/s1~2"));
    }
}
