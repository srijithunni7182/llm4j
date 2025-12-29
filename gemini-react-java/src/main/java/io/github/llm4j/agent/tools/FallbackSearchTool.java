package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool that tries multiple search implementations in order.
 * If one fails, it tries the next one.
 */
public class FallbackSearchTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(FallbackSearchTool.class);
    private final List<Tool> tools;
    private final String name;

    public FallbackSearchTool(String name, List<Tool> tools) {
        this.name = name;
        this.tools = new ArrayList<>(tools);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return tools.get(0).getDescription();
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        StringBuilder errors = new StringBuilder();
        for (Tool tool : tools) {
            String result = tool.execute(args);
            if (result != null && !result.startsWith("Error") && !result.toLowerCase().contains("api error")) {
                return result;
            }
            errors.append(tool.getClass().getSimpleName()).append(": ").append(result).append("\n");
            logger.warn("Search tool {} failed, trying fallback. Error: {}", tool.getClass().getSimpleName(), result);
        }
        return "Error: All search tools failed.\n" + errors.toString();
    }
}
