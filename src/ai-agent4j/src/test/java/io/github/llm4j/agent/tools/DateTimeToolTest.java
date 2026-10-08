package io.github.llm4j.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DateTimeToolTest {

    @Test
    void testExecute() {
        DateTimeTool tool = new DateTimeTool();
        String result = tool.execute(Map.of());

        assertThat(result).isNotNull();
        // Verify it's a valid RFC 1123 date time
        ZonedDateTime parsed = ZonedDateTime.parse(result, DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(parsed).isNotNull();

        // Should be close to now
        assertThat(parsed.getYear()).isEqualTo(ZonedDateTime.now().getYear());
    }

    @Test
    void testNameAndDescription() {
        DateTimeTool tool = new DateTimeTool();
        assertThat(tool.getName()).isEqualTo("CurrentDateTime");
        assertThat(tool.getDescription()).contains("current date and time");
    }
}
