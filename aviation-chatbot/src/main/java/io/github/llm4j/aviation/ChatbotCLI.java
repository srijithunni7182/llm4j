package io.github.llm4j.aviation;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;

import java.util.Scanner;

public class ChatbotCLI {
    public static void main(String[] args) {
        String googleApiKey = System.getenv("GOOGLE_API_KEY");
        String aviationStackApiKey = System.getenv("AVIATION_STACK_API_KEY");

        if (googleApiKey == null || aviationStackApiKey == null) {
            System.err.println("Please set GOOGLE_API_KEY and AVIATION_STACK_API_KEY");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(googleApiKey)
                .defaultModel("gemini-2.0-flash")
                .build();

        LLMClient llmClient = new DefaultLLMClient(new GoogleProvider(config));

        io.github.llm4j.agent.tools.openapi.OpenAPITool aviationTool = io.github.llm4j.agent.tools.openapi.OpenAPITool
                .builder()
                .name("AviationStack")
                .specLocation("aviation-chatbot/src/main/resources/aviationstack-openapi.json")
                .apiKeyAuth("access_key", aviationStackApiKey)
                .build();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmClient)
                .addTool(aviationTool)
                .addTool(new CurrentTimeTool())
                .maxIterations(10)
                .temperature(0.7)
                .build();

        Scanner scanner = new Scanner(System.in);
        System.out.println("Aviation Chatbot CLI");
        System.out.println("====================");
        System.out.println("Type 'exit' to quit\n");

        while (true) {
            System.out.print("You: ");
            String query = scanner.nextLine().trim();

            if (query.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye!");
                break;
            }

            if (query.isEmpty()) {
                continue;
            }

            System.out.println("\n" + "=".repeat(80));
            AgentResult result = agent.run(query);
            System.out.println("=".repeat(80));

            System.out.println("\nBot: " + result.getFinalAnswer());
            System.out.println("\nCompleted: " + result.isCompleted());
            System.out.println("Iterations: " + result.getIterations());
            System.out.println();
        }

        scanner.close();
    }
}
