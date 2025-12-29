package io.github.llm4j.examples;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;

/**
 * Example demonstrating agent personas.
 * Shows how different personas respond differently to the same question.
 */
public class PersonaAgentExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null) {
            System.err.println("Please set GOOGLE_API_KEY environment variable");
            System.exit(1);
        }

        // Create LLM client
        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gemini-1.5-flash")
                .build();

        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));

        String question = "What is 15 * 23 + 47?";

        System.out.println("=".repeat(80));
        System.out.println("PERSONA AGENT EXAMPLE");
        System.out.println("=".repeat(80));
        System.out.println("\nQuestion: " + question + "\n");

        // 1. Technical Analyst Persona
        System.out.println("\n" + "-".repeat(80));
        System.out.println("1. TECHNICAL ANALYST PERSONA");
        System.out.println("-".repeat(80));

        ReActAgent analystAgent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .persona(PersonaLibrary.technicalAnalyst())
                .maxIterations(5)
                .build();

        AgentResult analystResult = analystAgent.run(question);
        System.out.println("\nAnswer: " + analystResult.getFinalAnswer());
        System.out.println("Iterations: " + analystResult.getIterations());

        // 2. Software Developer Persona
        System.out.println("\n" + "-".repeat(80));
        System.out.println("2. SOFTWARE DEVELOPER PERSONA");
        System.out.println("-".repeat(80));

        ReActAgent developerAgent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .persona(PersonaLibrary.softwareDeveloper())
                .maxIterations(5)
                .build();

        AgentResult developerResult = developerAgent.run(question);
        System.out.println("\nAnswer: " + developerResult.getFinalAnswer());
        System.out.println("Iterations: " + developerResult.getIterations());

        // 3. Custom Persona
        System.out.println("\n" + "-".repeat(80));
        System.out.println("3. CUSTOM PERSONA (Friendly Math Tutor)");
        System.out.println("-".repeat(80));

        AgentPersona tutorPersona = AgentPersona.builder()
                .name("Math Tutor")
                .role("friendly mathematics teacher")
                .expertise("Mathematics education and step-by-step problem solving")
                .tone("Encouraging, patient, and educational. Always explain the steps.")
                .addConstraint("Break down problems into simple steps")
                .addConstraint("Explain the reasoning behind each step")
                .build();

        ReActAgent tutorAgent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new CalculatorTool())
                .persona(tutorPersona)
                .maxIterations(5)
                .build();

        AgentResult tutorResult = tutorAgent.run(question);
        System.out.println("\nAnswer: " + tutorResult.getFinalAnswer());
        System.out.println("Iterations: " + tutorResult.getIterations());

        System.out.println("\n" + "=".repeat(80));
        System.out.println("Notice how each persona approaches the same problem differently!");
        System.out.println("=".repeat(80));
    }
}
