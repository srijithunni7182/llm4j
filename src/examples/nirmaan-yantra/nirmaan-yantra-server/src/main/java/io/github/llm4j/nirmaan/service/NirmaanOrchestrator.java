package io.github.llm4j.nirmaan.service;

import io.github.llm4j.nirmaan.agent.AditiAgent;
import io.github.llm4j.nirmaan.agent.DhruvAgent;
import io.github.llm4j.nirmaan.agent.DrishtiAgent;
import io.github.llm4j.nirmaan.agent.RishiAgent;
import io.github.llm4j.nirmaan.agent.VihaanAgent;
import io.github.llm4j.nirmaan.agent.VishnuAgent;
import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NirmaanOrchestrator {

    private final Map<String, ProjectContext> activeProjects = new ConcurrentHashMap<>();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private final AditiAgent aditiAgent;
    private final RishiAgent rishiAgent;
    private final VihaanAgent vihaanAgent;
    private final DhruvAgent dhruvAgent;
    private final DrishtiAgent drishtiAgent;
    private final VishnuAgent vishnuAgent;

    public NirmaanOrchestrator(AditiAgent aditiAgent, RishiAgent rishiAgent, VihaanAgent vihaanAgent,
            DhruvAgent dhruvAgent, DrishtiAgent drishtiAgent, VishnuAgent vishnuAgent) {
        this.aditiAgent = aditiAgent;
        this.rishiAgent = rishiAgent;
        this.vihaanAgent = vihaanAgent;
        this.dhruvAgent = dhruvAgent;
        this.drishtiAgent = drishtiAgent;
        this.vishnuAgent = vishnuAgent;
    }

    public ProjectContext startProject(String userIdea) {
        ProjectContext context = new ProjectContext(userIdea);
        activeProjects.put(context.getProjectId(), context);
        // runWorkflow is now called by Controller to ensure @Async works
        return context;
    }

    public ProjectContext getProjectContext(String projectId) {
        return activeProjects.get(projectId);
    }

    public SseEmitter subscribe(String projectId) {
        SseEmitter emitter = new SseEmitter(3600000L);
        emitters.put(projectId, emitter);

        ProjectContext context = activeProjects.get(projectId);
        if (context != null) {
            String[] pastLogs = context.getActivityLog().toString().split("\n");
            try {
                for (String log : pastLogs) {
                    if (!log.isBlank())
                        emitter.send(SseEmitter.event().name("log").data(log));
                }
            } catch (Exception e) {
            }
        }

        emitter.onCompletion(() -> emitters.remove(projectId));
        emitter.onTimeout(() -> emitters.remove(projectId));
        return emitter;
    }

    @Async
    public void runWorkflow(String projectId) {
        ProjectContext context = activeProjects.get(projectId);
        if (context == null)
            return;

        emitUpdate(projectId, "System", "Initializing Nirmaan Yantra Team...");

        try {
            // Phase 1: Planning (Aditi)
            emitUpdate(projectId, "Aditi", "Hello! I'm analyzing your request to understand the requirements.");
            aditiAgent.execute(context);
            if (context.getStatus() == ProjectStatus.FAILED)
                return;
            emitUpdate(projectId, "Aditi", "I've drafted the Product Requirements Document (PRD). Handoff to Rishi.");

            // Phase 2: Architecture (Rishi)
            emitUpdate(projectId, "Rishi", "Thanks Aditi. I'm reviewing the PRD to select the best tech stack.");
            rishiAgent.execute(context);
            if (context.getStatus() == ProjectStatus.FAILED)
                return;
            emitUpdate(projectId, "Rishi",
                    "I've generated the Technical Specification. Vihaan, you can start building.");

            // Phase 3: Parallel Prep & Construction
            emitUpdate(projectId, "System", "Starting Parallel Tracks: Construction & E2E Prep.");

            // Phase 3: Parallel Prep & Construction
            emitUpdate(projectId, "System", "Starting Parallel Tracks: Construction & E2E Prep.");

            // Running Tracks in Parallel Futures
            java.util.concurrent.CompletableFuture<Void> trackB_Prep = java.util.concurrent.CompletableFuture
                    .runAsync(() -> {
                        emitUpdate(projectId, "Drishti", "Setting up isolated E2E Environment (Track B)...");
                        drishtiAgent.prepareEnvironment(context);
                    });

            java.util.concurrent.CompletableFuture<Boolean> trackA_Construction = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        emitUpdate(projectId, "Vihaan", "Starting TDD Cycle (Red-Green-Refactor) (Track A).");
                        return executeConstructionPhase(context, projectId);
                    });

            // Wait for both to complete
            java.util.concurrent.CompletableFuture.allOf(trackB_Prep, trackA_Construction).join();

            if (!trackA_Construction.get()) {
                context.setStatus(ProjectStatus.FAILED);
                emitUpdate(projectId, "System", "Construction Phase Failed. Workflow Stopped.");
                return;
            }

            // Phase 4: Phase 1 Review (Vishnu)
            emitUpdate(projectId, "Vishnu", "Phase 1 Review: Checking Code Quality & Unit Tests...");
            boolean phase1Approved = vishnuAgent.reviewImplementation(context);
            if (!phase1Approved) {
                emitUpdate(projectId, "Vishnu", "Code Review Failed. Vihaan, please fix highlighted issues.");
                vihaanAgent.refactorCode(context, context.getArtifacts().get("REVIEW_PHASE1.md"));
                // Simplification: We assume one fix loop or fail here for now
            }

            // Phase 5: Final QA Execution (Drishti)
            emitUpdate(projectId, "Drishti", "Running E2E Validation Suite against the built application...");
            if (!executeQAPhase(context, projectId)) {
                emitUpdate(projectId, "System", "QA Phase Failed. Workflow Stopped.");
                return;
            }

            // Phase 6: Final Approval & Refinement (Vishnu/Vihaan)
            emitUpdate(projectId, "Vishnu", "I'll conduct the final code review before release.");
            executeRefinementPhase(context, projectId);

            if (context.getStatus() == ProjectStatus.COMPLETED) {
                emitUpdate(projectId, "System", "Workflow Cycle Complete. Project Ready.");
                emitUpdate(projectId, "System", "Generating Final Project Archive...");
                try {
                    // Create a Zip of the Sandbox
                    java.nio.file.Path zipPath = context.getSandboxPath().resolve(projectId + "_FINAL.zip");
                    // Assuming we have a Zip Utility or we can implement a simple one here or in
                    // BaseAgent.
                    // For now, logging the intent as a placeholder or using a simple command if
                    // possible.
                    // Using 'zip' command line if available or skipping implementation detail for
                    // this turn if no utility exists.
                    // But user asked to ASSERT generation. Let's try to generate it.
                    Process zipProc = new ProcessBuilder("zip", "-r", zipPath.toString(), ".")
                            .directory(context.getSandboxPath().toFile())
                            .start();
                    zipProc.waitFor();
                    if (zipProc.exitValue() == 0) {
                        emitUpdate(projectId, "System", "Archive Generated: " + zipPath.toString());
                    } else {
                        emitUpdate(projectId, "System", "Archive Generation Failed.");
                    }
                } catch (Exception e) {
                    emitUpdate(projectId, "System", "Archive Error: " + e.getMessage());
                }
            } else {
                emitUpdate(projectId, "System", "Workflow Stopped (Status: " + context.getStatus() + ").");
            }

        } catch (Exception e) {
            emitUpdate(projectId, "System", "Critical Error - " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean executeConstructionPhase(ProjectContext context, String projectId) {
        // --- Construction Loop (Red-Green-Refactor + Self Review) ---
        int maxRetries = 15;
        int attempts = 0;

        while (attempts < maxRetries) {
            attempts++;

            // 1. RED PHASE
            String feedback = (attempts > 1)
                    ? "Previous attempt failed or was incomplete. Please write a stricter test or cover missing requirements."
                    : null;

            // Check if we have specific feedback from Self-Review (stored in context or
            // passed around? simpler to just use generic message or log)
            // But if we are restarting loop, we need to know why.
            // Let's assume Vihaan gets context from previous artifacts/logs?
            // Better: Pass explicit feedback if we can.

            emitUpdate(projectId, "Vihaan", "(Red Phase) Writing a failing test...");
            vihaanAgent.generateFailingTest(context, feedback);

            emitUpdate(projectId, "Dhruv", "Running the test to confirm it fails...");
            boolean testFailed = !dhruvAgent.runTests(context); // Expect Fail

            if (!testFailed) {
                emitUpdate(projectId, "Dhruv", "Test PASSED unexpectedy (TDD Violation). Retrying Red Phase...");
                continue; // Retry Red
            }

            // 2. GREEN PHASE
            emitUpdate(projectId, "Vihaan", "(Green Phase) Writing the minimal code to pass the test...");
            vihaanAgent.generatePassingCode(context);

            // 3. VERIFY (Green Loop)
            boolean greenVerified = false;
            int greenAttempts = 0;
            while (greenAttempts < 5 && !greenVerified) {
                greenAttempts++;
                emitUpdate(projectId, "Dhruv", "Verifying implementation...");
                if (dhruvAgent.runTests(context)) {
                    greenVerified = true;
                } else {
                    String buildLog = context.getArtifacts().getOrDefault("LAST_BUILD_LOG.txt", "");

                    if (greenAttempts == 5) {
                        emitUpdate(projectId, "System",
                                "Green Phase failed 5 verification attempts. Initiating **FRESH START**...");
                        vihaanAgent.regenerateImplementation(context);
                        // Give it one more chance after fresh start? Or just reset count?
                        // Let's reset the attempt count to give the fresh code a fair chance similar to
                        // new cycle
                        greenAttempts = 0;
                        // Limit total fresh starts? For now, infinite loop prevention is handled by
                        // outer 'attempts' < 15
                        emitUpdate(projectId, "System", "Code regenerated. Resuming verification...");
                    } else {
                        emitUpdate(projectId, "Vihaan", "Build/Test failed. Fixing...");
                        vihaanAgent.fixCode(context, buildLog);
                    }
                }
            }

            if (!greenVerified) {
                emitUpdate(projectId, "System",
                        "Green Phase failed to stabilize even after fresh start. Restarting TDD cycle...");
                continue;
            }

            // 4. SELF REVIEW
            String missingItems = vihaanAgent.performSelfReview(context);
            if (missingItems == null) {
                emitUpdate(projectId, "Vihaan", "Self-Review Complete. All requirements met.");
                return true;
            }

            boolean hasAssets = missingItems.contains("MISSING_ASSETS");
            boolean hasLogic = missingItems.contains("MISSING_LOGIC");

            if (hasAssets) {
                emitUpdate(projectId, "Vihaan",
                        "Self-Review found missing documentation/scripts. Generating them now...");
                vihaanAgent.generateArtifacts(context, missingItems);
                emitUpdate(projectId, "Vihaan", "Assets generated.");
            }

            if (hasLogic) {
                // MISSING_LOGIC
                emitUpdate(projectId, "Vihaan", "Self-Review found missing Code Logic: " + missingItems);
                emitUpdate(projectId, "System", "Restarting TDD Cycle to implement missing logic...");
                // Loop continues, effectively restarting Red Phase
            } else {
                // Only assets were missing, and we generated them.
                emitUpdate(projectId, "Vihaan", "Construction Phase Complete.");
                return true;
            }
        }

        emitUpdate(projectId, "System", "Max Construction Retries reached.");
        return false;
    }

    private boolean executeQAPhase(ProjectContext context, String projectId) {
        int maxRetries = 15;
        int attempt = 0;

        while (attempt < maxRetries) {
            attempt++;

            // Build first
            emitUpdate(projectId, "Dhruv", "Running a final clean build (Attempt " + attempt + ")...");
            boolean buildSuccess = dhruvAgent.build(context);

            if (!buildSuccess) {
                String buildLog = context.getArtifacts().getOrDefault("LAST_BUILD_LOG.txt", "Build Failed (No Log)");
                emitUpdate(projectId, "Dhruv", "Build failed. Passing error log to Vihaan...");
                vihaanAgent.fixCode(context, "Build Failed during QA Phase.\nLOG:\n" + buildLog);
                continue; // Retry build
            }

            // QA Agent
            emitUpdate(projectId, "Drishti", "Running validation suite...");
            drishtiAgent.execute(context);

            // Refetch QA Report to check detailed status if needed,
            // but relying on Context Status set by DrishtiAgent (FAILED or
            // TESTING/COMPLETED?)
            // DrishtiAgent currently sets FAILED if issues found.

            if (context.getStatus() != ProjectStatus.FAILED) {
                emitUpdate(projectId, "Drishti", "Validation passed. The project is certified.");
                return true;
            }

            // FAILED -> QA Remediation Loop
            String qaReport = context.getArtifacts().get("QA_REPORT.md");

            // Extract a concise error reason from the report (first 100 chars of details?)
            String failureReason = "Issues found in QA";
            if (qaReport != null && qaReport.contains("## Execution Check")) {
                try {
                    String details = qaReport.split("## Execution Check")[1].trim();
                    failureReason = details.split("\n")[0]; // First line of error
                    if (failureReason.length() > 200)
                        failureReason = failureReason.substring(0, 200) + "...";
                } catch (Exception e) {
                }
            }

            emitUpdate(projectId, "Drishti", "QA Failed (" + failureReason + "). Remediation logic active.");

            // 1. Rishi Updates Spec
            emitUpdate(projectId, "Rishi", "Analyzing feedback to update Spec...");
            String rishiSummary = rishiAgent.updateSpec(context, qaReport);
            emitUpdate(projectId, "Rishi", "Spec Updated: " + rishiSummary);

            // 2. Vihaan Implements changes
            emitUpdate(projectId, "Vihaan", "Refactoring code to align with new Spec...");
            vihaanAgent.fixCode(context, "QA Failed. Please fix issues described in: " + qaReport);

            // Loop continues to next attempt...
        }

        emitUpdate(projectId, "System", "Max QA Retries reached. Validation Failed.");
        return false;
    }

    private void executeRefinementPhase(ProjectContext context, String projectId) {
        int maxRefinementCycles = 15;
        int cyles = 0;

        while (cyles < maxRefinementCycles) {
            vishnuAgent.finalSignOff(context);

            if (context.getStatus() == ProjectStatus.COMPLETED) {
                emitUpdate(projectId, "Vishnu", "Code looks excellent. Approved for release!");
                return;
            }

            // If not COMPLETED, check for feedback
            String feedback = context.getArtifacts().get("REVIEW.md");
            if (feedback == null) {
                emitUpdate(projectId, "Vishnu", "Rejected, but I can't find my notes. Stopping.");
                return;
            }

            // REJECTED -> Refactor
            emitUpdate(projectId, "Vishnu", "I've found some issues. Vihaan, please refactor based on my feedback.");
            emitUpdate(projectId, "Vihaan", "On it. Refactoring the code now...");
            vihaanAgent.refactorCode(context, feedback);

            // Verify Fixes (Re-run Tests)
            emitUpdate(projectId, "Dhruv", "Verifying the refactored code...");
            boolean refactorTestPassed = dhruvAgent.runTests(context);

            if (!refactorTestPassed) {
                emitUpdate(projectId, "Dhruv", "Tests failed after refactoring. We have a regression.");
                context.setStatus(ProjectStatus.FAILED);
                return;
            }
            emitUpdate(projectId, "Dhruv", "Tests passed. Sending back to Vishnu.");

            cyles++;
        }

        if (context.getStatus() != ProjectStatus.COMPLETED) {
            emitUpdate(projectId, "System", "Max refinement cycles reached. Manual intervention required.");
        }
    }

    private void emitUpdate(String projectId, String agentName, String message) {
        ProjectContext context = activeProjects.get(projectId);

        // Log to context first (persistent log)
        if (context != null)
            context.log(agentName, message);

        // Construct message for Stream (Agent: Message)
        String streamMessage = agentName.equals("System") ? message : (agentName + ": " + message);

        SseEmitter emitter = emitters.get(projectId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("log").data(streamMessage));
            } catch (Exception e) {
                emitters.remove(projectId);
            }
        }
    }
}
