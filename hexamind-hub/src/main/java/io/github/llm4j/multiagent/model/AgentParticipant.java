package io.github.llm4j.multiagent.model;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.AgentPersona;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an AI agent participant in the collaboration.
 */
@Data
public class AgentParticipant {

        private final String id;
        private final String name;
        private final AgentPersona persona;
        private final ReActAgent agent;

        private final String avatarUrl;
        private final List<AgentThought> thoughts = new ArrayList<>();
        private String currentThought;
        private AgentOpinion opinion;

        public AgentParticipant(String id, String name, AgentPersona persona, ReActAgent agent, String avatarUrl) {
                this.id = id;
                this.name = name;
                this.persona = persona;
                this.agent = agent;
                this.avatarUrl = avatarUrl;
        }

        /**
         * Agent analyzes the problem and returns their initial thoughts.
         */
        public String analyze(String problem) {
                String prompt = String.format(
                                "Analyze this problem from your perspective as a %s: %s\n\n" +
                                                "MANDATORY: Before providing analysis, you MUST use web search to verify any unfamiliar terms or concepts. "
                                                +
                                                "If the query contains fabricated, fictional, or incorrect terms, you MUST explicitly call this out and challenge the premise. "
                                                +
                                                "Avoid corporate clichés like 'phased approach' and provide empirical, data-backed insights in 2-4 sentences.",
                                persona.getRole(), problem);

                AgentResult result = agent.run(prompt);
                this.currentThought = result.getFinalAnswer();
                return result.getFinalAnswer();
        }

        /**
         * Agent presents their argument based on the problem and context.
         */
        public String argue(String problem, String context) {
                String prompt = String.format(
                                "Problem: %s\n\n" +
                                                "Context from other agents:\n%s\n\n" +
                                                "Present your main argument or recommendation. Be specific, provide reasoning, and cite data where possible. "
                                                +
                                                "Avoid overly generic advice and stay true to your unique persona's creative or analytical bent.",
                                problem, context);

                AgentResult result = agent.run(prompt);
                this.currentThought = result.getFinalAnswer();
                return result.getFinalAnswer();
        }

        /**
         * Agent responds to other agents' arguments.
         */
        public String respond(String otherArguments) {
                String prompt = String.format(
                                "Other agents have presented these arguments:\n%s\n\n" +
                                                "Respond with your perspective. Do you agree, disagree, or have refinements? "
                                                +
                                                "Be constructive, specific, and challenge any collective hallucinations or unsupported assumptions you detect.",
                                otherArguments);

                AgentResult result = agent.run(prompt);
                this.currentThought = result.getFinalAnswer();
                return result.getFinalAnswer();
        }

        /**
         * Agent provides final opinion for consensus building.
         */
        public AgentOpinion formOpinion(String problem, List<AgentThought> allThoughts) {
                StringBuilder context = new StringBuilder();
                for (AgentThought thought : allThoughts) {
                        context.append(String.format("%s: %s\n", thought.getAgentName(), thought.getContent()));
                }

                String prompt = String.format(
                                "Problem: %s\n\n" +
                                                "Discussion so far:\n%s\n\n" +
                                                "Provide your final recommendation in 2-3 sentences. Rate your confidence (0-100).",
                                problem, context.toString());

                AgentResult result = agent.run(prompt);

                this.opinion = AgentOpinion.builder()
                                .agentId(id)
                                .agentName(name)
                                .recommendation(result.getFinalAnswer())
                                .confidence(0.8) // Could parse from response
                                .build();

                return this.opinion;
        }

        public void addThought(AgentThought thought) {
                this.thoughts.add(thought);
        }
}
