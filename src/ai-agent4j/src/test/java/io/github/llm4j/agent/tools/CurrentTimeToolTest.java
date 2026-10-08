package io.github.llm4j.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class CurrentTimeToolTest {

    @Test
    void execute_shouldReturnFormattedTime() {
        CurrentTimeTool tool = new CurrentTimeTool(ZoneId.of("UTC"));
        String result = tool.execute(Collections.emptyMap());

        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC");
    }

    @Test
    void getName_shouldReturnCorrectName() {
        CurrentTimeTool tool = new CurrentTimeTool();
        assertThat(tool.getName()).isEqualTo("CurrentTime");
    }

    @Test
    void getDescription_shouldReturnDescription() {
        CurrentTimeTool tool = new CurrentTimeTool();
        assertThat(tool.getDescription()).isNotEmpty();
    }
}
