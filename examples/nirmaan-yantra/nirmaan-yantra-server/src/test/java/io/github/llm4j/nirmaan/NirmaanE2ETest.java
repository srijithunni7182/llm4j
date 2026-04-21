package io.github.llm4j.nirmaan;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import io.github.llm4j.nirmaan.service.NirmaanOrchestrator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
@Tag("e2e")
public class NirmaanE2ETest {

    @Autowired
    private NirmaanOrchestrator orchestrator;

    @Test
    public void testSnakeGameWorkflow() throws InterruptedException {
        String idea = "Snake game in java";

        System.out.println("Starting E2E Test for: " + idea);
        ProjectContext initialContext = orchestrator.startProject(idea);
        String projectId = initialContext.getProjectId();
        System.out.println("Project Started: " + projectId);

        // Trigger workflow manually as we are bypassing Controller
        orchestrator.runWorkflow(projectId);

        // Poll for completion (max 30 minutes) - Increased from 15m to 30m to support
        // 15 retries
        ProjectContext context = null;
        for (int i = 0; i < 360; i++) { // 360 * 5s = 30 mins
            context = orchestrator.getProjectContext(projectId);
            if (context != null) {
                System.out.println("Status: " + context.getStatus());
                if (context.getStatus() == ProjectStatus.COMPLETED) {
                    break;
                }
                if (context.getStatus() == ProjectStatus.FAILED) {
                    System.out.println("=== PROJECT FAILED - LOGS ===");
                    System.out.println(context.getActivityLog().toString());
                    System.out.println("=== ARTIFACTS DUMP ===");
                    context.getArtifacts().forEach((k, v) -> {
                        if (k.endsWith(".md") || k.endsWith(".txt") || k.endsWith(".log")) {
                            System.out.println("--- " + k + " ---");
                            System.out.println(v);
                        }
                    });
                    System.out.println("=============================");
                    Assertions.fail("Project failed during workflow.");
                }
            }
            Thread.sleep(5000);
        }

        Assertions.assertNotNull(context, "Context should not be null");
        Assertions.assertEquals(ProjectStatus.COMPLETED, context.getStatus(), "Project should complete successfully.");

        // Verify files
        Assertions.assertTrue(java.nio.file.Files.exists(context.getSandboxPath().resolve("pom.xml")),
                "pom.xml should exist");
        Assertions.assertTrue(java.nio.file.Files.exists(context.getSandboxPath().resolve("src/main/java")),
                "Source dir should exist");

        // Final Zip Assertion
        String expectedZipName = projectId + "_FINAL.zip";
        Assertions.assertTrue(java.nio.file.Files.exists(context.getSandboxPath().resolve(expectedZipName)),
                "Final Project Archive (" + expectedZipName + ") should be generated");
    }

    @Test
    public void testCalculatorPythonWorkflow() throws InterruptedException {
        String idea = "Simple Calculator in Python";

        System.out.println("Starting E2E Test for: " + idea);
        ProjectContext initialContext = orchestrator.startProject(idea);
        String projectId = initialContext.getProjectId();
        System.out.println("Project Started: " + projectId);

        // Poll for completion (max 5 minutes)
        ProjectContext context = null;
        for (int i = 0; i < 60; i++) {
            context = orchestrator.getProjectContext(projectId);
            if (context != null) {
                if (context.getStatus() == ProjectStatus.COMPLETED) {
                    break;
                }
                if (context.getStatus() == ProjectStatus.FAILED) {
                    Assertions.fail("Project failed during workflow.");
                }
            }
            Thread.sleep(5000);
        }

        Assertions.assertNotNull(context, "Context should not be null");
        Assertions.assertEquals(ProjectStatus.COMPLETED, context.getStatus(), "Project should complete successfully.");

        // Verify files
        Assertions.assertTrue(
                java.nio.file.Files.exists(context.getSandboxPath().resolve("requirements.txt")) ||
                        java.nio.file.Files.exists(context.getSandboxPath().resolve("pyproject.toml")),
                "requirements.txt or pyproject.toml should exist");
        boolean hasPyFiles = false;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(context.getSandboxPath())) {
            hasPyFiles = stream.anyMatch(p -> p.toString().endsWith(".py"));
        } catch (java.io.IOException e) {
        }

        Assertions.assertTrue(hasPyFiles, "Should contain .py files");

        // Assert QA and Approval Artifacts (User Requirement Check)
        java.util.Map<String, String> artifacts = context.getArtifacts();

        Assertions.assertTrue(artifacts.containsKey("QA_REPORT.md"), "Drishti should generate QA Report");
        String qaReport = artifacts.get("QA_REPORT.md");
        Assertions.assertTrue(qaReport.contains("E2E PASSED") || qaReport.contains("PASSED"),
                "QA Report should pass verification");

        Assertions.assertTrue(artifacts.containsKey("APPROVAL.md"), "Vishnu should generate Approval Document");
        String approval = artifacts.get("APPROVAL.md");
        Assertions.assertTrue(approval.contains("RELEASE"), "Approval document should authorize release");
    }
}
