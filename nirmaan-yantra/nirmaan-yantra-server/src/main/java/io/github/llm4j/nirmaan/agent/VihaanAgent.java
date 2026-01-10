package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VihaanAgent extends BaseNirmaanAgent {

    @Override
    public String getName() {
        return "Vihaan";
    }

    @Override
    public String getRole() {
        return "Lead Developer";
    }

    // Default execute - preserved for interface, but Orchestrator should call
    // specific methods
    @Override
    public void execute(ProjectContext context) {
        generatePassingCode(context);
    }

    public void generateFailingTest(ProjectContext context) {
        generateFailingTest(context, null);
    }

    public void generateFailingTest(ProjectContext context, String feedback) {
        logThought(context,
                "I must follow the 1st Law of TDD: Write a failing test before any production code. This defines the requirement.");
        context.log(getName(), "TDD RED PHASE: Writing failing tests...");
        String specContent = getSpec(context);
        if (specContent == null)
            return;

        String feedbackSection = (feedback != null && !feedback.isEmpty())
                ? "\nWARNING: PREVIOUS ATTEMPT FAILED.\nFeedback: " + feedback
                        + "\nEnsure the new test fails as expected.\n"
                : "";

        String prompt = String.format(
                """
                        You are Vihaan. Follow the **First Law of TDD**:
                        "You are not allowed to write any production code unless it is to make a failing unit test pass."

                        Task: Write a **Unit Test** for the application defined in the Spec.
                        %s
                        Spec:
                        %s

                        Instructions:
                        1. Identify the Tech Stack.
                        2. Write a Unit Test file (e.g., `AppTest.java`, `test_app.py`).
                        3. **CRITICAL**: If the language (like Java) requires the implementation class to exist to compile, create a **Skeleton/Stub** implementation file as well. It MUST throw an Exception (e.g. `throw new UnsupportedOperationException()`) or fail to ensure the test turns RED.
                        4. **CRITICAL**: Generate the **Build File** (pom.xml, package.json, requirements.txt) so the test can actually run.
                        5. The test MUST FAIL when run.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                feedbackSection, specContent);

        executeLLM(context, prompt, "Tests Generated (RED)");
    }

    public void generatePassingCode(ProjectContext context) {
        logThought(context, "Now for the 3rd Law of TDD: I will write only enough code to pass the failing test(s).");
        context.log(getName(), "TDD GREEN PHASE: Writing code to pass tests...");
        String specContent = getSpec(context);
        if (specContent == null)
            return;

        String prompt = String.format(
                """
                        You are Vihaan. Follow the **Third Law of TDD**:
                        "You are not allowed to write more production code than is sufficient to pass the one failing unit test."

                        Task: Write the **Implementation Code** to make the existing tests pass.

                        Spec:
                        %s

                        Instructions:
                        1. Update/Overwrite the implementation files with working logic.
                        2. Ensure it implements the requirements in the Spec.
                        3. Do NOT modify the test files unless absolutely necessary to fix syntax errors.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                specContent);

        executeLLM(context, prompt, "Implementation Generated (GREEN)");
    }

    public void refactorCode(ProjectContext context, String feedback) {
        logThought(context,
                "I have received code review feedback. I need to improve the code structure without breaking functionality.");
        context.log(getName(), "REFACTOR MODE: Improving code based on feedback...");
        String specContent = getSpec(context);

        // Gather current code
        StringBuilder currentCode = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> java.nio.file.Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            currentCode.append("\n--- FILE: ").append(p.getFileName()).append(" ---\n");
                            currentCode.append(java.nio.file.Files.readString(p));
                        } catch (Exception e) {
                        }
                    });
        } catch (Exception e) {
        }

        String prompt = String.format(
                """
                        You are Vihaan.
                        Task: Refactor the code based on the Lead Developer's feedback.

                        Feedback:
                        %s

                        Current Code:
                        %s

                        Instructions:
                        1. Apply changes ONLY requested in the Feedback.
                        2. Maintain functional correctness (Tests must still pass).
                        3. Output any modified files.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                feedback, currentCode.toString());

        executeLLM(context, prompt, "Refactoring Complete");
    }

    public void fixCode(ProjectContext context, String errorLog) {
        logThought(context, "I see a build/test failure. I will analyze the error log and patch the code to fix it.");
        context.log(getName(), "DEBUG MODE: Fixing code based on errors...");
        String specContent = getSpec(context);

        // Gather current code (read all files)
        StringBuilder currentCode = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> java.nio.file.Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            // Skip hidden files or logs
                            if (p.toString().contains(".git") || p.toString().endsWith(".log"))
                                return;

                            currentCode.append("\n--- FILE: ").append(context.getSandboxPath().relativize(p))
                                    .append(" ---\n");
                            currentCode.append(java.nio.file.Files.readString(p));
                        } catch (Exception e) {
                        }
                    });
        } catch (Exception e) {
        }

        String prompt = String.format(
                """
                        You are Vihaan, the Lead Developer.

                        Problem: The current build/test failed.

                        Error Log:
                        %s

                        Current Codebase:
                        %s

                        Instructions:
                        1. Analyze the Error Log (Compilation failures, Test failures).
                        2. Fix the code (Implementation OR Tests) to resolve the error.
                        3. If the Test is wrong (e.g., constructor mismatch), update the Test.
                        4. If the Implementation is wrong, update the Implementation.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                errorLog, currentCode.toString());

        executeLLM(context, prompt, "Fixes Applied");
    }

    public String performSelfReview(ProjectContext context) {
        logThought(context, "Tests passed. Now verifying if I missed any requirements or files from the Spec.");
        context.log(getName(), "Self-Reviewing code completeness...");
        String specContent = getSpec(context);

        // List all files
        StringBuilder fileList = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> java.nio.file.Files.isRegularFile(p))
                    .forEach(p -> fileList.append(context.getSandboxPath().relativize(p)).append("\n"));
        } catch (Exception e) {
        }

        String prompt = String.format(
                """
                        You are Vihaan.
                        Task: Self-Review against the Spec.

                        Spec:
                        %s

                        Current Files:
                        %s

                        Instructions:
                        1. Check if ANY required files (classes, resources, descriptors) are missing.
                        2. Do NOT generate them. Just list what is missing.
                        3. If everything looks good, output "COMPLETE".

                        Output Format:
                        MISSING: [file1, file2...] or description of missing logic.
                        """,
                specContent, fileList.toString());

        try {
            // No tools needed, just analysis
            String response = chatWithTools(context, prompt);
            if (response.contains("COMPLETE") && !response.contains("MISSING")) {
                return null; // All good
            }
            return response; // Return the missing details
        } catch (Exception e) {
            context.log(getName(), "Self-Review Error: " + e.getMessage());
        }
        return null;
    }

    private String getSpec(ProjectContext context) {
        String spec = context.getArtifacts().get("SPEC.md");
        if (spec == null) {
            context.log(getName(), "Error: SPEC.md not found!");
            context.setStatus(ProjectStatus.FAILED);
            return null;
        }
        return spec;
    }

    private void executeLLM(ProjectContext context, String prompt, String successMessage) {
        // Append Search Instructions
        String promptWithTools = prompt
                + """

                        **DEBUGGING TOOLS**:
                        If you need to check documentation or find the latest version of a library to fix a build error, output: `[SEARCH: your query]`.
                        For example: `[SEARCH: maven central testfx-headless version]` or `[SEARCH: python requests documentation]`.
                        """;

        try {
            String rawContent = chatWithTools(context, promptWithTools);

            // Parse Files using helper
            java.util.Map<String, String> files = parseFiles(rawContent);

            if (files.isEmpty()) {
                context.log(getName(), "Warning: No files generated.");
                return;
            }

            for (java.util.Map.Entry<String, String> entry : files.entrySet()) {
                context.saveFile(entry.getKey(), entry.getValue());
                context.log(getName(), "Generated: " + entry.getKey());
            }

            context.log(getName(), successMessage + " (" + files.size() + " files).");

        } catch (Exception e) {
            context.log(getName(), "Error: " + e.getMessage());
            context.setStatus(ProjectStatus.FAILED);
            e.printStackTrace();
        }
    }

    // Public for Testing
    public java.util.Map<String, String> parseFiles(String rawContent) {
        java.util.Map<String, String> startMap = new java.util.HashMap<>();
        Pattern pattern = Pattern.compile("\\[FILE: (.*?)\\](.*?)\\[EOF\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(rawContent);

        while (matcher.find()) {
            String filePath = matcher.group(1).trim();
            String fileContent = matcher.group(2).trim();

            // Clean Markdown Code Blocks
            // Remove start block (e.g., ```java, ```xml, or just ```)
            if (fileContent.startsWith("```")) {
                int firstNewLine = fileContent.indexOf("\n");
                if (firstNewLine != -1) {
                    fileContent = fileContent.substring(firstNewLine + 1);
                } else {
                    fileContent = ""; // Empty block
                }
            }
            // Remove end block (```)
            if (fileContent.endsWith("```")) {
                fileContent = fileContent.substring(0, fileContent.length() - 3);
            }

            startMap.put(filePath, fileContent.trim());
        }
        return startMap;
    }
}
