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

        String currentCode = readCurrentCode(context);

        String prompt = String.format(
                """
                        You are Vihaan. Follow the **Third Law of TDD**:
                        "You are not allowed to write more production code than is sufficient to pass the one failing unit test."

                        Task: Write the **Implementation Code** to make the existing tests pass.

                        Spec:
                        %s

                        Current Codebase:
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
                specContent, currentCode);

        executeLLM(context, prompt, "Implementation Generated (GREEN)");
    }

    public void refactorCode(ProjectContext context, String feedback) {
        logThought(context,
                "I have received code review feedback. I need to improve the code structure without breaking functionality.");
        context.log(getName(), "REFACTOR MODE: Improving code based on feedback...");
        String specContent = getSpec(context);

        // Gather current code
        String currentCode = readCurrentCode(context);

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
                feedback, currentCode);

        executeLLM(context, prompt, "Refactoring Complete");
    }

    public void fixCode(ProjectContext context, String errorLog) {
        logThought(context, "I see a build/test failure. I will analyze the error log and patch the code to fix it.");
        context.log(getName(), "DEBUG MODE: Fixing code based on errors...");
        String specContent = getSpec(context);

        // Gather current code (read all files)
        String currentCode = readSmartContext(context, errorLog);

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
                        2. If a Class/Symbol is missing, CHECK the `pom.xml` (or build file) included in the context.
                           - If the library is missing, ADD the dependency to `pom.xml`.
                           - If you don't know the dependency version, use `[SEARCH: maven central <library_name>]`.
                        3. Fix the code (Implementation OR Tests) to resolve the error.
                        4. If the Test is wrong (e.g., constructor mismatch), update the Test.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                errorLog, currentCode);

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
                        1. Check if ANY required files (classes, resources, descriptors, docs, scripts) are missing.
                        2. Do NOT generate them. Just list what is missing.
                        3. If everything looks good, output "COMPLETE".
                        4. If missing items are ONLY documentation (README, guides) or scripts (shell scripts, config), output "MISSING_ASSETS: [details]".
                        5. If missing items include CODE LOGIC (classes, methods, tests), output "MISSING_LOGIC: [details]".
                        6. If BOTH are missing, output "MISSING_ASSETS: [details] | MISSING_LOGIC: [details]".

                        Output Format:
                        MISSING_LOGIC: ...
                        OR
                        MISSING_ASSETS: ...
                        OR
                        MISSING_ASSETS: ... | MISSING_LOGIC: ...
                        OR
                        COMPLETE
                        """,
                specContent, fileList.toString());

        try {
            // No tools needed, just analysis
            String response = chatWithTools(context, prompt);
            if (response.contains("COMPLETE") && !response.contains("MISSING")) {
                return null; // All good
            }
            // Return raw response (starting with MISSING_...)
            // Simple cleanup to ensure we just return the line
            if (response.contains("MISSING_"))
                return response.trim();

            return response;
        } catch (Exception e) {
            context.log(getName(), "Self-Review Error: " + e.getMessage());
        }
        return null;
    }

    public void generateArtifacts(ProjectContext context, String missingDetails) {
        logThought(context, "I need to generate some missing non-code artifacts (docs/scripts).");
        context.log(getName(), "Generating missing artifacts...");
        String specContent = getSpec(context);
        String currentCode = readCurrentCode(context);

        String prompt = String.format(
                """
                        You are Vihaan.
                        Task: Generate missing configuration/documentation files.

                        Spec:
                        %s

                        Existing Files:
                        %s

                        Missing Items Description:
                        %s

                        Instructions:
                        1. Generate the missing files described.
                        2. Do NOT change existing code logic.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                specContent, currentCode, missingDetails);

        executeLLM(context, prompt, "Artifacts Generated");
    }

    public void regenerateImplementation(ProjectContext context) {
        logThought(context,
                "Verification failed repeatedly. Initiating **FRESH START**. discarding broken implementation.");
        context.log(getName(),
                "CRITICAL: Stuck in a loop. deleting implementation and regenerating from Spec + Tests...");

        String specContent = getSpec(context);

        // Read ONLY Test files (ignore broken source files)
        StringBuilder testCode = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> p.toString().endsWith("Test.java") || p.toString().endsWith("Tests.java"))
                    .forEach(p -> {
                        try {
                            testCode.append("\n--- TEST FILE: ").append(context.getSandboxPath().relativize(p))
                                    .append(" ---\n");
                            testCode.append(java.nio.file.Files.readString(p));
                        } catch (Exception e) {
                        }
                    });
        } catch (Exception e) {
            context.log(getName(), "Error reading tests: " + e.getMessage());
        }

        String prompt = String.format(
                """
                        You are Vihaan.
                        CRITICAL SITUATION: The previous implementation attempts have failed 5 times in a row.

                        Action: IGNORE previous implementation files. We are starting FRESH.

                        Task: Write the functional implementation for the provided Tests.

                        Spec:
                        %s

                        Existing Tests (Do NOT Modify):
                        %s

                        Instructions:
                        1. Write the Java classes required to pass these tests.
                        2. Do not look at old implementation files (they are broken).
                        3. Ensure strict adherence to the Spec.

                        Output Format:
                        [FILE: path/to/file.ext]
                        ...content...
                        [EOF]
                        """,
                specContent, testCode.toString());

        executeLLM(context, prompt, "Implementation RE-GENERATED (Fresh Start)");
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
        // Fix: Use [^\\n\\]]+ to strictly forbid newlines in the filename capture
        Pattern pattern = Pattern.compile("\\[FILE: ([^\\n\\]]+)\\](.*?)\\[EOF\\]", Pattern.DOTALL);
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
