package io.github.llm4j.nirmaan;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import io.github.llm4j.nirmaan.service.NirmaanOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class NirmaanIntegrationTest {

    @Autowired
    private NirmaanOrchestrator orchestrator;

    @Test
    void testEndToEndWorkflow() throws InterruptedException {
        // Idea: Something simple but requiring code
        String idea = "Create a simple Python script 'calc.py' that adds two numbers and prints the result.";

        ProjectContext context = orchestrator.startProject(idea);
        String projectId = context.getProjectId();

        System.out.println("Integration Test Started: Project ID " + projectId);

        // Wait for workflow (Poll status)
        ProjectStatus finalStatus = ProjectStatus.CREATED;
        int maxRetries = 40; // 200 seconds (approx 3 mins)

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(5000);
            System.out.println("Current Status: " + context.getStatus());

            if (context.getStatus() == ProjectStatus.FAILED) {
                fail("Project Failed! Logs: \n" + context.getActivityLog());
            }
            if (context.getActivityLog().toString().contains("Workflow Cycle Complete")) {
                finalStatus = ProjectStatus.COMPLETED;
                break;
            }
        }

        System.out.println("Final Logs:\n" + context.getActivityLog());

        // Assertions
        assertNotEquals(ProjectStatus.FAILED, context.getStatus(), "Project should not fail.");
        assertTrue(context.getActivityLog().toString().contains("Implementation Generated (GREEN)"),
                "Should reach Green Phase");
        assertTrue(context.getActivityLog().toString().contains("TDD SUCCESS"), "Should pass TDD check");
        assertTrue(context.getArtifacts().containsKey("QA_REPORT.md"), "QA Report should exist");
        assertTrue(context.getArtifacts().containsKey("APPROVAL.md"), "Project should be Approved by Vishnu");
        assertEquals(ProjectStatus.COMPLETED, context.getStatus(), "Final Status should be COMPLETED");
    }
}
