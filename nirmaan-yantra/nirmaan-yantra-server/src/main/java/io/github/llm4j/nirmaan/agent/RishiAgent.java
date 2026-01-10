package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;

@Component
public class RishiAgent extends BaseNirmaanAgent {

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

        String prompt = String.format(
                """
                        You are Rishi, a brilliant Solutions Architect with deep knowledge of all modern tech stacks (Java, Node.js, Python, Go, Rust, etc.).
                        Based on the following PRD, select the **optimal** technology stack for the project.

                        PRD Content:
                        %s

                        Output a **Technical Specification (SPEC.md)** in Markdown.

                        **CRITICAL**: You must define variable commands that the Build and QA agents will execute.

                        Structure:
                        1. **Tech Stack**: Selected Language and Frameworks (e.g., "Python with Flask", "Node.js with Express", "Java with Spring Boot").
                        2. **Rationale**: Why this stack was chosen.
                        4. **Test Strategy**:
                           - Unit Tests: Framework and approach.
                           - E2E Automation: Strategy for headless CI environments.
                        5. **Operational Requirements**:
                           - README.md: Documentation plan.
                           - Scripts: Must include `start.sh` (launcher) and `test.sh`.
                        6. **Build Command**: The exact single-line shell command to install dependencies and build the project.
                           - Node/JS: `npm install` (or `npm install && npm run build`)
                           - Python: `pip install -r requirements.txt`
                           - Java: `mvn clean package -DskipTests`
                           - Go: `go build -o app .`
                        7. **Test Command**: The exact single-line shell command to run tests.
                           - Node/JS: `npm test`
                           - Python: `pytest`
                           - Java: `mvn test`
                        8. **Run Command**: Command to start the app.
                        9. **Project Structure**: JSON-like file tree.
                        10. **Implementation Plan**: Step-by-step guide.

                        FORMATTING RULES:
                        - Use the exact keys provided (e.g., "Build Command:", "Test Command:").
                        - Do NOT wrap the keys in markdown bolding (i.e. use "Build Command:" not "**Build Command**:").
                        - Ensure the command is on the same line or the immediate next line.

                        Do not include conversational filler. Output ONLY the SPEC.md content.

                        **RESEARCH CAPABILITY**:
                        If you are unsure about a library version or command, output `[SEARCH: maven central <lib>]` or `[SEARCH: python package <name>]`.
                        The system will provide results, and you can then generate the SPEC.
                        """,
                prdContent);

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

        String prompt = String.format(
                """
                        You are Rishi, the Solutions Architect.
                        Goal: Update the Technical Specification to address QA Feedback.

                        Current Spec:
                        %s

                        QA Feedback:
                        %s

                        Instructions:
                        1. Update the Test Strategy or Implementation Plan to address the gaps.
                        2. Do NOT change the core stack unless requested.
                        3. Output a concise summary of changes.
                        4. Output the FULL updated SPEC.md.

                        Output Format:
                        [SUMMARY: One sentence description of what you changed]
                        [FILE: SPEC.md]
                        ... full spec content ...
                        [EOF]
                        """,
                currentSpec, feedback);

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
