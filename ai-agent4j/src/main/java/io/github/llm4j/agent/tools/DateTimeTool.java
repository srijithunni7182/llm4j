package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * A tool that returns the current date and time.
 * This is useful for agents that need to be aware of the current time
 * to avoid making factually incorrect statements about temporal events.
 */
public class DateTimeTool implements Tool {

    @Override
    public String getName() {
        return "CurrentDateTime";
    }

    @Override
    public String getDescription() {
        return "Returns the current date and time. Use this when you need to know the present date or time to contextualize information.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        return ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }
}
