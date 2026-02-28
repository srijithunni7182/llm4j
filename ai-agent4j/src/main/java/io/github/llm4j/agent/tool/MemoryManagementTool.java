package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.memory.SemanticMemoryService;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A critical tool for autonomous agents that allows them to explicitly write factual observations
 * about the user into long-term persistent storage (Semantic Memory).
 */
public class MemoryManagementTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManagementTool.class);

    private final SemanticMemoryService memoryService;

    public MemoryManagementTool(SemanticMemoryService memoryService) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService cannot be null");
    }

    @Override
    public String getName() {
        return "save_memory_fact";
    }

    @Override
    public String getDescription() {
        return "Use this tool to save important, persistent facts about the user into your long-term memory. " +
               "If the user tells you a preference, constraint, personal detail, or an important piece of context " +
               "that you should remember for future conversations, use this tool to save it. " +
               "Arguments: \n" +
               "- fact (string): A clear, standalone, declarative sentence documenting the fact. (e.g. 'The user is allergic to peanuts' or 'The user prefers Next.js over React').";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String fact = (String) args.get("fact");

        if (fact == null || fact.trim().isEmpty()) {
            return "Error: 'fact' argument is missing or empty.";
        }

        try {
            memoryService.saveFact(fact);
            return "Successfully saved fact into long-term memory: " + fact;
        } catch (Exception e) {
            logger.error("Error saving memory fact via tool: {}", e.getMessage());
            return "Warning: Failed to save fact due to system error: " + e.getMessage();
        }
    }
}
