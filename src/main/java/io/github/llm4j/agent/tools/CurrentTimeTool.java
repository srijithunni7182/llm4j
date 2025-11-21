package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A tool that returns the current date and time.
 */
public class CurrentTimeTool implements Tool {

    private final DateTimeFormatter formatter;
    private final ZoneId zoneId;

    public CurrentTimeTool() {
        this(ZoneId.systemDefault());
    }

    public CurrentTimeTool(ZoneId zoneId) {
        this.zoneId = zoneId;
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    }

    @Override
    public String getName() {
        return "CurrentTime";
    }

    @Override
    public String getDescription() {
        return "Returns the current date and time. No input required.";
    }

    @Override
    public String execute(String input) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        return now.atZone(zoneId).format(formatter);
    }
}
