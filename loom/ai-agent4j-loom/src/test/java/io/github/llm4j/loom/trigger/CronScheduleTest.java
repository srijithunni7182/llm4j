package io.github.llm4j.loom.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Verification plan V7.6–V7.7. */
class CronScheduleTest {

    @ParameterizedTest(name = "{0} after {1} in {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            // weekdays, working hours, every 15 minutes: Friday evening -> Monday morning
            "*/15 9-17 * * MON-FRI|2026-09-25T17:50:00Z|UTC|2026-09-28T09:00:00Z",
            "*/15 9-17 * * MON-FRI|2026-09-28T09:00:00Z|UTC|2026-09-28T09:15:00Z",
            "0 0 1 * *|2026-09-27T10:00:00Z|UTC|2026-10-01T00:00:00Z",
            "0 7 * * *|2026-09-27T10:00:00Z|Asia/Kolkata|2026-09-28T01:30:00Z",
            // US DST starts 2026-03-08 02:00 -> 03:00: 02:30 does not exist, fires when the clock jumps
            "30 2 * * *|2026-03-07T12:00:00Z|America/New_York|2026-03-08T07:30:00Z",
            "30 2 * * *|2026-03-08T07:30:00Z|America/New_York|2026-03-09T06:30:00Z",
            // US DST ends 2026-11-01 02:00 -> 01:00: 01:30 happens twice, fires once (the first)
            "30 1 * * *|2026-10-31T12:00:00Z|America/New_York|2026-11-01T05:30:00Z",
            "30 1 * * *|2026-11-01T05:30:00Z|America/New_York|2026-11-02T06:30:00Z",
            // both day fields restricted: either matches (13th of the month, or a Friday)
            "0 0 13 * FRI|2026-09-27T10:00:00Z|UTC|2026-10-02T00:00:00Z",
            "0 0 13 * FRI|2026-10-09T01:00:00Z|UTC|2026-10-13T00:00:00Z",
            "0 12 * JAN,jul *|2026-09-27T10:00:00Z|UTC|2027-01-01T12:00:00Z",
            "0 0 * * 7|2026-09-27T10:00:00Z|UTC|2026-10-04T00:00:00Z",
            "0 0 29 2 *|2026-09-27T10:00:00Z|UTC|2028-02-29T00:00:00Z",
            "5,10 */6 * * *|2026-09-27T10:00:00Z|UTC|2026-09-27T12:05:00Z",
            "0 8-18/5 * * *|2026-09-27T10:00:00Z|UTC|2026-09-27T13:00:00Z"})
    void v7_6_nextSlot(String cron, String after, String zone, String expected) {
        assertThat(CronSchedule.parse(cron).next(Instant.parse(after), ZoneId.of(zone))).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "61 * * * *|minute field: 61 is outside 0-59",
            "* 24 * * *|hour field: 24",
            "* * 0 * *|day-of-month field: 0",
            "* * * 13 *|month field: 13",
            "* * * * 8|day-of-week field: 8",
            "x * * * *|minute field: 'x' is not a number",
            "5-1 * * * *|runs backwards",
            "* * *|needs 5 fields",
            "*/0 * * * *|minute field: 0 is outside 1-59"})
    void v7_7_errorsNameTheField(String cron, String message) {
        assertThatThrownBy(() -> CronSchedule.parse(cron)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    @Test
    void impossibleDatesNeverMatch() {
        assertThatThrownBy(() -> CronSchedule.parse("0 0 31 2 *").next(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                .hasMessageContaining("never matches");
        assertThatThrownBy(() -> CronSchedule.parse(null)).hasMessageContaining("missing");
        assertThat(CronSchedule.parse(" 0 7 * * * ").expression()).isEqualTo("0 7 * * *");
        assertThat(CronSchedule.parse("? ? ? ? ?").toString()).isEqualTo("? ? ? ? ?");
    }
}
