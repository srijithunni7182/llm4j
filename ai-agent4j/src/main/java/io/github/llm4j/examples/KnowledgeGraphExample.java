package io.github.llm4j.examples;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import io.github.llm4j.agent.knowledge.store.InMemoryGraphStore;
import io.github.llm4j.agent.knowledge.tools.GraphQueryTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;

/**
 * Example demonstrating knowledge graph integration with agents.
 * Creates a simple organizational knowledge graph and queries it.
 */
public class KnowledgeGraphExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null) {
            System.err.println("Please set GOOGLE_API_KEY environment variable");
            System.exit(1);
        }

        System.out.println("=".repeat(80));
        System.out.println("KNOWLEDGE GRAPH EXAMPLE");
        System.out.println("=".repeat(80));

        // 1. Build a knowledge graph (company org chart)
        System.out.println("\n1. Building Knowledge Graph (Company Org Chart)...\n");

        KnowledgeGraph graph = new InMemoryGraphStore();

        // Add entities (employees)
        Entity alice = Entity.builder()
                .id("alice")
                .type("Person")
                .addProperty("name", "Alice Johnson")
                .addProperty("title", "CEO")
                .addProperty("department", "Executive")
                .build();

        Entity bob = Entity.builder()
                .id("bob")
                .type("Person")
                .addProperty("name", "Bob Smith")
                .addProperty("title", "CTO")
                .addProperty("department", "Engineering")
                .build();

        Entity charlie = Entity.builder()
                .id("charlie")
                .type("Person")
                .addProperty("name", "Charlie Davis")
                .addProperty("title", "Senior Engineer")
                .addProperty("department", "Engineering")
                .build();

        Entity diana = Entity.builder()
                .id("diana")
                .type("Person")
                .addProperty("name", "Diana Martinez")
                .addProperty("title", "CFO")
                .addProperty("department", "Finance")
                .build();

        // Add relationships
        graph.addTriple(new Triple(bob, Relation.builder().type("REPORTS_TO").build(), alice));
        graph.addTriple(new Triple(diana, Relation.builder().type("REPORTS_TO").build(), alice));
        graph.addTriple(new Triple(charlie, Relation.builder().type("REPORTS_TO").build(), bob));
        graph.addTriple(new Triple(bob, Relation.builder().type("WORKS_WITH").build(), diana));

        System.out.println("Knowledge Graph Statistics:");
        System.out.println("  - Entities: " + graph.getEntityCount());
        System.out.println("  - Relationships: " + graph.getTripleCount());

        // 2. Create agent with knowledge graph tool
        System.out.println("\n2. Creating Agent with Knowledge Graph Access...\n");

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gemini-1.5-flash")
                .build();

        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));

        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .addTool(new GraphQueryTool(graph))
                .maxIterations(5)
                .build();

        // 3. Query the knowledge graph through the agent
        System.out.println("3. Querying Knowledge Graph through Agent...\n");

        String[] questions = {
                "Who does Charlie report to?",
                "Who are all the people in the Engineering department?",
                "What is Bob's title and who does he report to?"
        };

        for (int i = 0; i < questions.length; i++) {
            System.out.println("-".repeat(80));
            System.out.println("Question " + (i + 1) + ": " + questions[i]);
            System.out.println("-".repeat(80));

            AgentResult result = agent.run(questions[i]);

            System.out.println("\nAnswer: " + result.getFinalAnswer());
            System.out.println("Iterations: " + result.getIterations());
            System.out.println();
        }

        System.out.println("=".repeat(80));
        System.out.println("Knowledge Graph allows agents to reason over structured data!");
        System.out.println("=".repeat(80));
    }
}
