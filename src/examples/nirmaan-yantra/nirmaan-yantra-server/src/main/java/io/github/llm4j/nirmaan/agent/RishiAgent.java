package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;

@Component
public class RishiAgent extends BaseNirmaanAgent {

    private final io.github.llm4j.agent.prompt.PromptRegistry promptRegistry;

    public RishiAgent(io.github.llm4j.agent.prompt.PromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String getName() {
        return "Rishi";
    }

    @Override
    public String getRole() {
        return "Solutions Architect";
    }

    @Override
    public void execute(ProjectContext context) {
        context.setStatus(ProjectStatus.ARCHITECTING);
        logThought(context,
                "I will review Aditi's PRD and select the most appropriate tech stack (Java/Spring Boot or Python/Flask) based on the requirements.");
        context.log(getName(), "Reviewing PRD and selecting best Tech Stack...");

        String prdContent = context.getArtifacts().get("PRD.md");
        if (prdContent == null) {
            context.log(getName(), "Error: PRD.md not found!");
            context.setStatus(ProjectStatus.FAILED);
            return;
        }

        String prompt = promptRegistry.get("rishi.spec_gen")
                .orElseThrow(() -> new RuntimeException("Prompt 'rishi.spec_gen' not found"))
                .render(java.util.Map.of("prd_content", prdContent));

        try {
            // Use chain-of-thought with tools
            String specContent = chatWithTools(context, prompt);

            context.addArtifact("SPEC.md", specContent);
            context.log(getName(), "Technical Specification (SPEC.md) generated.");

        } catch (Exception e) {
            context.log(getName(), "Error generating Spec: " + e.getMessage());
            context.setStatus(ProjectStatus.FAILED);
            e.printStackTrace();
        }
    }

    public String updateSpec(ProjectContext context, String feedback) {
        logThought(context,
                "I have received feedback from QA. I need to update the specification to satisfy the failing requirements.");
        context.log(getName(), "Updating Specification based on QA Feedback...");
        String currentSpec = context.getArtifacts().get("SPEC.md");
        if (currentSpec == null)
            return "Error: No Spec found to update.";

        String prompt = promptRegistry.get("rishi.spec_update")
                .orElseThrow(() -> new RuntimeException("Prompt 'rishi.spec_update' not found"))
                .render(java.util.Map.of("current_spec", currentSpec, "feedback", feedback));

        try {
            String response = chatWithTools(context, prompt);

            // Parse Summary
            String summary = "Updated Spec based on feedback.";
            java.util.regex.Pattern summaryPattern = java.util.regex.Pattern.compile("\\[SUMMARY: (.*?)\\]");
            java.util.regex.Matcher summaryMatcher = summaryPattern.matcher(response);
            if (summaryMatcher.find()) {
                summary = summaryMatcher.group(1).trim();
            }

            // Parse File (Reuse parseFiles or simple regex)
            // Parse Files
            java.util.regex.Pattern filePattern = java.util.regex.Pattern.compile("\\[FILE: (.*?)\\](.*?)\\[EOF\\]",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher fileMatcher = filePattern.matcher(response);

            while (fileMatcher.find()) {
                String filePath = fileMatcher.group(1).trim();
                String fileContent = fileMatcher.group(2).trim();
                // Clean Markdown Code Blocks
                if (fileContent.startsWith("```")) {
                    int firstNewLine = fileContent.indexOf("\n");
                    if (firstNewLine != -1) {
                        fileContent = fileContent.substring(firstNewLine + 1);
                    }
                }
                if (fileContent.endsWith("```")) {
                    fileContent = fileContent.substring(0, fileContent.length() - 3);
                }
                context.addArtifact(filePath, fileContent.trim());
                if (filePath.equals("SPEC.md"))
                    context.log(getName(), "SPEC.md updated.");
            }
            return summary;

        } catch (Exception e) {
            context.log(getName(), "Error updating Spec: " + e.getMessage());
            return "Failed to update Spec: " + e.getMessage();
        }
    }
}
