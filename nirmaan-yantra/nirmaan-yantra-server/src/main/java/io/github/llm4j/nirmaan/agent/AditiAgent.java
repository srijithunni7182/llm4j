package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;

@Component
public class AditiAgent extends BaseNirmaanAgent {

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

        String prompt = String.format(
                """
                        You are Aditi, an expert Product Manager at a top-tier tech company.
                        Your goal is to transform the following user idea into a comprehensive Product Requirements Document (PRD).

                        User Idea: "%s"

                        Output the PRD in Markdown format with the following sections:
                        1. **Goal Description**: Clear summary of the product.
                        2. **User Stories**: Bullet points of key user flows.
                        3. **Acceptance Criteria**: What must be true for the product to be shipped.
                        4. **Feature List**: Defined MVP features.

                        Do not include conversational filler. Output ONLY the Markdown PRD.
                        """,
                context.getUserIdea());

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
