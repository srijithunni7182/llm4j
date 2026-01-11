package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;

@Component
public class VishnuAgent extends BaseNirmaanAgent {

    private final io.github.llm4j.agent.prompt.PromptRegistry promptRegistry;

    public VishnuAgent(io.github.llm4j.agent.prompt.PromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String getName() {
        return "Vishnu";
    }

    @Override
    public String getRole() {
        return "Lead Developer";
    }

    @Override
    public void execute(ProjectContext context) {
        // Default if called generically
        reviewImplementation(context);
    }

    public boolean reviewImplementation(ProjectContext context) {
        context.log(getName(), "Phase 1: Reviewing Implementation and Unit Tests...");

        // 1. Gather Code
        StringBuilder codeContent = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> java.nio.file.Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            if (p.toString().endsWith(".log") || p.toString().contains(".git")
                                    || p.toString().contains("e2e-tests"))
                                return;
                            codeContent.append("\n--- FILE: ").append(context.getSandboxPath().relativize(p))
                                    .append(" ---\n");
                            codeContent.append(java.nio.file.Files.readString(p));
                        } catch (Exception e) {
                        }
                    });
        } catch (Exception e) {
            context.log(getName(), "Error reading source files: " + e.getMessage());
            return false;
        }

        // Fail-Fast: Check if tests exist
        boolean hasTests = codeContent.toString().contains("src/test/java") || codeContent.toString().contains("@Test");
        if (!hasTests) {
            context.log(getName(), "Phase 1 Failed: No Unit Tests found. Skipping LLM Review.");
            context.addArtifact("REVIEW_PHASE1.md",
                    "# Phase 1 Rejection\n**Reason**: No Unit Tests detected.\n\nPlease write JUnit tests in `src/test/java`.");
            return false;
        }

        // 2. LLM Review (Phase 1)
        // 2. LLM Review (Phase 1)
        String prompt = promptRegistry.get("vishnu.review_phase1")
                .orElseThrow(() -> new RuntimeException("Prompt 'vishnu.review_phase1' not found"))
                .render(java.util.Map.of("codebase", codeContent.toString()));

        try {
            String response = chatWithTools(context, prompt);

            if (response.contains("[APPROVED]")) {
                context.log(getName(), "Phase 1 Review Passed. Proceeding to QA.");
                return true;
            } else {
                context.log(getName(), "Phase 1 Review Failed.");
                context.addArtifact("REVIEW_PHASE1.md", response);
                return false;
            }

        } catch (Exception e) {
            context.log(getName(), "LLM Error: " + e.getMessage());
            return false;
        }
    }

    // Deprecated method for backward compat if needed during refactor, but removing
    // now.
    // public boolean performReview(ProjectContext context) { ... }
    // Final Signoff logic (Phase 2)
    public void finalSignOff(ProjectContext context) {
        context.log(getName(), "Phase 2: Final Sign-off. Verifying QA results and code compliance.");

        // xAI Thought Process Log
        context.log(getName(),
                "[Thought] I need to check if Drishti's E2E tests passed. If they did, I will take a final look at the code structure to ensure no regressions were introduced during refinements.");

        String qaReport = context.getArtifacts().get("QA_REPORT.md");
        if (qaReport == null || !qaReport.contains("PASSED")) {
            context.log(getName(), "[Thought] QA Report is missing or failed. I cannot release this.");
            context.addArtifact("REVIEW.md", "# Review Failed\n\n**Reason**: QA Validation Failed or Missing.");
            return;
        }

        // 1. Gather Code (Reuse logic for final check)
        StringBuilder codeContent = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> java.nio.file.Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            if (p.toString().endsWith(".log") || p.toString().contains(".git")
                                    || p.toString().contains("e2e-tests"))
                                return;
                            codeContent.append("\n--- FILE: ").append(context.getSandboxPath().relativize(p))
                                    .append(" ---\n");
                            codeContent.append(java.nio.file.Files.readString(p));
                        } catch (Exception e) {
                        }
                    });
        } catch (Exception e) {
            context.log(getName(), "Error reading source files: " + e.getMessage());
            return;
        }

        // 2. LLM Final Check
        // 2. LLM Final Check
        String prompt = promptRegistry.get("vishnu.review_final")
                .orElseThrow(() -> new RuntimeException("Prompt 'vishnu.review_final' not found"))
                .render(java.util.Map.of("codebase", codeContent.toString()));

        try {
            String response = chatWithTools(context, prompt);

            if (response.contains("[APPROVED]")) {
                context.log(getName(), "[Thought] The code looks solid and QA is green. Authorizing release.");
                context.log(getName(), "Final Review Passed. Releasing...");
                context.setStatus(ProjectStatus.COMPLETED);

                String approvalDoc = """
                        # Deployment Approval
                        **Authorized By**: Vishnu (Lead Developer)
                        **Decision**: RELEASE
                        **Status**: GA Candidate
                        """;
                context.addArtifact("APPROVAL.md", approvalDoc);

            } else {
                context.log(getName(), "[Thought] Found last-minute issues in the code. Sending back for polish.");
                context.log(getName(), "Final Review Failed.");
                context.addArtifact("REVIEW.md", response);
            }

        } catch (Exception e) {
            context.log(getName(), "LLM Error: " + e.getMessage());
        }
    }
}
