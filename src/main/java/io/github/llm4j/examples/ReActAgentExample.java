package io.github.llm4j.examples;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.openai.OpenAIProvider;

/**
 * Example demonstrating the ReAct agent with multiple tools.
 */
public class ReActAgentExample {

    public static void main(String[] args) {
        // Check for API key
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }

        // Create LLM client
        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gpt-3.5-turbo")
                .build();

        LLMClient llmClient = new DefaultLLMClient(new OpenAIProvider(config));

        // Create ReAct agent with tools
        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmClient)
                .addTool(new CalculatorTool())
                .addTool(new CurrentTimeTool())
                .maxIterations(10)
                .temperature(0.7)
                .build();

        // Example 1: Math calculation
        System.out.println("=== Example 1: Math Calculation ===");
        AgentResult result1 = agent.run("What is 15 * 23 + 47?");
        printResult(result1);

        // Example 2: Multi-step calculation
        System.out.println("\n=== Example 2: Multi-Step Calculation ===");
        AgentResult result2 = agent.run("Calculate (100 - 25) * 2 and then add 50 to it");
        printResult(result2);

        // Example 3: Using current time
        System.out.println("\n=== Example 3: Using Current Time ===");
        AgentResult result3 = agent.run("What is the current date and time?");
        printResult(result3);
    }

    private static void printResult(AgentResult result) {
        System.out.println("Completed: " + result.isCompleted());
        System.out.println("Iterations: " + result.getIterations());
        System.out.println("Steps taken: " + result.getSteps().size());
        System.out.println("\nThought process:");

        for (int i = 0; i < result.getSteps().size(); i++) {
            AgentResult.AgentStep step = result.getSteps().get(i);
            System.out.println("  Step " + (i + 1) + ":");
            System.out.println("    Thought: " + step.getThought());
            System.out.println("    Action: " + step.getAction());
            System.out.println("    Action Input: " + step.getActionInput());
            System.out.println("    Observation: " + step.getObservation());
        }

        System.out.println("\nFinal Answer: " + result.getFinalAnswer());
    }
}
