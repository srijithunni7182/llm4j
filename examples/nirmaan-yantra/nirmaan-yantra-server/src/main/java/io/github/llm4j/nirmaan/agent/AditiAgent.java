package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AditiAgent extends BaseNirmaanAgent {

    private final PromptRegistry promptRegistry;

    public AditiAgent(PromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String getName() {
        return "Aditi";
    }

    @Override
    public String getRole() {
        return "Product Manager";
    }

    @Override
    public void execute(ProjectContext context) {
        context.setStatus(ProjectStatus.PLANNING);
        logThought(context,
                "I need to analyze the user's idea and break it down into actionable user stories and acceptance criteria.");
        context.log(getName(), "Analyzing user idea: " + context.getUserIdea());

        String prompt = promptRegistry.get("aditi.prd_gen")
                .orElseThrow(() -> new RuntimeException("Prompt 'aditi.prd_gen' not found"))
                .render(Map.of("user_idea", context.getUserIdea()));

        try {
            LLMRequest request = LLMRequest.builder()
                    .addUserMessage(prompt)
                    .temperature(0.7)
                    .build();

            LLMResponse response = llmClient.chat(request);
            String prdContent = response.getContent();

            context.addArtifact("PRD.md", prdContent);
            context.log(getName(), "PRD generated successfully.");

        } catch (Exception e) {
            context.log(getName(), "Error generating PRD: " + e.getMessage());
            context.setStatus(ProjectStatus.FAILED);
            e.printStackTrace();
        }
    }
}
