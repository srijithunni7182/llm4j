package io.github.llm4j.multiagent.model;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.rag.RAGAgent;
import io.github.llm4j.agent.knowledge.tools.GraphExtractionTool;
import io.github.llm4j.agent.knowledge.tools.GraphQueryTool;
import io.github.llm4j.multiagent.service.SharedKnowledgeService;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents an AI agent participant in the collaboration.
 */
@Data
public class AgentParticipant {

        private final String id;

        public String getId() {
                return id;
        }

        private final String name;

        public String getName() {
                return name;
        }

        private final AgentPersona persona;
        private final ReActAgent agent;
        private final String avatarUrl;
        private final SharedKnowledgeService sharedKnowledgeService;
        private final PromptRegistry promptRegistry;

        private final List<AgentThought> thoughts = new ArrayList<>();
        private String currentThought;
        private AgentOpinion opinion;

        public AgentParticipant(String id, String name, AgentPersona persona, ReActAgent agent, String avatarUrl,
                        SharedKnowledgeService sharedKnowledgeService, PromptRegistry promptRegistry) {
                this.id = id;
                this.name = name;
                this.persona = persona;
                this.agent = agent;
                this.avatarUrl = avatarUrl;
                this.sharedKnowledgeService = sharedKnowledgeService;
                this.promptRegistry = promptRegistry;
        }

        /**
         * Agent analyzes the problem and returns their initial thoughts.
         */
        public String analyze(String sessionId, String problem) {
                String prompt = promptRegistry.get("agent_analyze").orElseThrow()
                                .render(Map.of(
                                                "role", persona.getRole() != null ? persona.getRole() : "",
                                                "problem", problem != null ? problem : ""));

                AgentResult result = getSessionAgent(sessionId).run(prompt);
                this.currentThought = result.getFinalAnswer();
                return result.getFinalAnswer();
        }

        /**
         * Agent presents their argument based on the problem and context.
         */
        public String argue(String sessionId, String problem, String context) {
                String prompt = promptRegistry.get("agent_argue").orElseThrow()
                                .render(Map.of(
                                                "problem", problem != null ? problem : "",
                                                "context", context != null ? context : ""));

                AgentResult result = getSessionAgent(sessionId).run(prompt);
                this.currentThought = result.getFinalAnswer();
                return result.getFinalAnswer();
        }

        /**
         * Agent responds to other agents' arguments.
         */
        public String respond(String sessionId, String otherArguments) {
                String prompt = promptRegistry.get("agent_respond").orElseThrow()
                                .render(Map.of(
                                                "arguments", otherArguments != null ? otherArguments : ""));

                AgentResult result = getSessionAgent(sessionId).run(prompt);
                this.currentThought = result.getFinalAnswer();
                return result.getFinalAnswer();
        }

        /**
         * Agent provides final opinion for consensus building.
         */
        public AgentOpinion formOpinion(String sessionId, String problem, List<AgentThought> allThoughts) {
                StringBuilder context = new StringBuilder();
                for (AgentThought thought : allThoughts) {
                        context.append(String.format("%s: %s\n", thought.getAgentName(), thought.getContent()));
                }

                String prompt = promptRegistry.get("agent_form_opinion").orElseThrow()
                                .render(Map.of(
                                                "problem", problem != null ? problem : "",
                                                "discussion", context.toString()));

                AgentResult result = getSessionAgent(sessionId).run(prompt);

                this.opinion = new AgentOpinion(
                                id,
                                name,
                                result.getFinalAnswer(),
                                0.8,
                                new ArrayList<>(), // key points
                                new ArrayList<>() // concerns
                );

                return this.opinion;
        }

        private RAGAgent getSessionAgent(String sessionId) {
                // Return a RAGAgent wrapped around the base agent, with session-specific tools
                ReActAgent sessionAgent = agent.toBuilder()
                                .clearTools()
                                .addTools(agent.getTools()) // Keep existing tools (search, time)
                                .addTool(new GraphQueryTool(sharedKnowledgeService.getKnowledgeGraph(sessionId)))
                                .addTool(new GraphExtractionTool(sharedKnowledgeService.getKnowledgeGraph(sessionId)))
                                .build();

                return RAGAgent.builder()
                                .agent(sessionAgent)
                                .vectorStore(sharedKnowledgeService.getVectorStore(sessionId))
                                .embeddingProvider(sharedKnowledgeService.getEmbeddingProvider())
                                .topK(3)
                                .includeMetadata(true)
                                .build();
        }

        public void addThought(AgentThought thought) {
                this.thoughts.add(thought);
        }
}
