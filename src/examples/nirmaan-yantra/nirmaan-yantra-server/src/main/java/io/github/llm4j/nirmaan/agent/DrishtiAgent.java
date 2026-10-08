package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DrishtiAgent extends BaseNirmaanAgent {

    private final io.github.llm4j.agent.prompt.PromptRegistry promptRegistry;

    public DrishtiAgent(io.github.llm4j.agent.prompt.PromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String getName() {
        return "Drishti";
    }

    @Override
    public String getRole() {
        return "QA Engineer";
    }

    @Override
    public void execute(ProjectContext context) {
        // This method is now primarily for parsing and reporting,
        // or triggering the full QA sequence if called generically.
        // Ideally, Orchestrator calls specific methods: prepareEnvironment,
        // generateE2E, runE2E.
        // For backward compatibility, we'll map execute to "Run QA Analysis" assuming
        // env is ready.

        context.log(getName(), "Starting QA Analysis...");
        runQA(context);
    }

    public void prepareEnvironment(ProjectContext context) {
        logThought(context,
                "I need to set up an isolated E2E testing environment with its own dependencies (POM) to avoid polluting the main project.");
        context.log(getName(), "Setting up E2E Test Environment...");
        try {
            java.nio.file.Path e2ePath = context.getSandboxPath().resolve("e2e-tests");
            java.nio.file.Files.createDirectories(e2ePath);

            // Create specific POM for E2E
            String e2ePom = """
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId>
                      <artifactId>e2e-tests</artifactId>
                      <version>1.0-SNAPSHOT</version>
                      <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                      </properties>
                      <dependencies>
                        <!-- JUnit 5 -->
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-api</artifactId>
                            <version>5.9.2</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-engine</artifactId>
                            <version>5.9.2</version>
                            <scope>test</scope>
                        </dependency>
                        <!-- Add Selenium/RestAssured here if dynamic search finds they are needed -->
                      </dependencies>
                      <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.0.0</version>
                            </plugin>
                        </plugins>
                      </build>
                    </project>
                    """;

            context.saveFile("e2e-tests/pom.xml", e2ePom);
            context.log(getName(), "E2E Environment configured (Separate Project).");

            // Pre-download dependencies to avoid timeout later (Optimistic)
            // We'll run this in background or short timeout
            runCommandInDir(context, "mvn dependency:resolve", e2ePath);

        } catch (Exception e) {
            context.log(getName(), "Failed to setup E2E environment: " + e.getMessage());
        }
    }

    public void runQA(ProjectContext context) {
        logThought(context,
                "Spec and Code are ready. I will now generate a comprehensive E2E test suite and execute it.");
        context.setStatus(ProjectStatus.TESTING);

        // 1. Run Regression (Unit Tests in Main Project) - Optional Check
        // ... (Skipping for brevity, focusing on E2E as requested)

        // 2. Generate and Run New E2E Automation
        context.log(getName(), "Generating new E2E Suite in 'e2e-tests'...");
        String e2eReport = generateAndRunE2ETest(context);

        // 3. Generate Report
        String report = generateQAReport(!e2eReport.contains("FAILED"), e2eReport);
        context.addArtifact("QA_REPORT.md", report);

        if (!e2eReport.contains("FAILED")) {
            context.log(getName(), "QA Verified. Report generated.");
        } else {
            context.log(getName(), "QA Failed. Issues found.");
            context.setStatus(ProjectStatus.FAILED);
        }
    }

    private String generateAndRunE2ETest(ProjectContext context) {
        // 1. Read Codebase
        StringBuilder codebase = new StringBuilder();
        try {
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> java.nio.file.Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            if (p.toString().endsWith(".log") || p.toString().contains(".git"))
                                return;
                            codebase.append("\n--- ").append(context.getSandboxPath().relativize(p)).append(" ---\n");
                            codebase.append(java.nio.file.Files.readString(p));
                        } catch (Exception e) {
                        }
                    });
        } catch (Exception e) {
        }

        // 2. Prompt LLM
        // 2. Prompt LLM
        String prompt = promptRegistry.get("drishti.e2e_gen")
                .orElseThrow(() -> new RuntimeException("Prompt 'drishti.e2e_gen' not found"))
                .render(java.util.Map.of("codebase", codebase.toString()));

        String response = chatWithTools(context, prompt);

        // 3. Parse and Save
        String runCommand = null;
        StringBuilder report = new StringBuilder();

        // Parse Files
        Pattern filePattern = Pattern.compile("\\[FILE: (.*?)\\](.*?)\\[EOF\\]", Pattern.DOTALL);
        Matcher fileMatcher = filePattern.matcher(response);

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

            context.saveFile(filePath, fileContent.trim());
            context.log(getName(), "Generated E2E Test: " + filePath);
        }

        // Parse Run Command
        Pattern commandPattern = Pattern.compile("\\[RUN_COMMAND: (.*?)\\]");
        Matcher commandMatcher = commandPattern.matcher(response);
        if (commandMatcher.find()) {
            runCommand = commandMatcher.group(1).trim();
        }

        // 4. Run
        if (runCommand != null) {
            context.log(getName(), "Executing E2E Test: " + runCommand);
            boolean p = runCommand(context, runCommand, report);
            return p ? "E2E PASSED\n" + report : "E2E FAILED\n" + report;
        } else {
            return "No Run Command found in E2E generation.";
        }
    }

    public String generateQAReport(boolean passed, String details) {
        return String.format("""
                # QA Report
                **Status**: %s

                ## Execution Check
                %s

                ## Agent Sign-off
                %s
                """,
                passed ? "PASSED" : "FAILED",
                details,
                passed ? "Approved for Release." : "Reject. Needs Fixes.");
    }

    // Helper similar to Dhruv's (Duplication to be refactored later, or moved to
    // Base)
    private String extractCommand(String text, String key) {
        if (text == null)
            return null;
        for (String line : text.split("\n")) {
            if (line.toLowerCase().contains(key.toLowerCase())) {
                int colonIndex = line.indexOf(":");
                if (colonIndex != -1) {
                    String value = line.substring(colonIndex + 1).trim();
                    value = value.replaceAll("^[`'\"*]+|[`'\"*]+$", "").trim();
                    if (!value.isEmpty())
                        return value;
                }
            }
        }
        return null;
    }

    private boolean runCommand(ProjectContext context, String command, StringBuilder outputLog) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(context.getSandboxPath().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLog.append(line).append("\n");
                }
            }

            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            outputLog.append("Execution Error: ").append(e.getMessage());
            return false;
        }
    }

    private boolean runCommandInDir(ProjectContext context, String command, java.nio.file.Path dir) {
        StringBuilder outputLog = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLog.append(line).append("\n");
                }
            }

            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            context.log(getName(), "Execution Error in " + dir.getFileName() + ": " + e.getMessage());
            return false;
        }
    }
}
