package io.github.llm4j.loom.generate;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks for a patch, applies it to a trial copy, runs the gates on the copy and feeds back what
 * failed, always asking for the whole patch against the original files so the result is one clean
 * diff. Monotone like {@link RepairLoop}: a patch that does worse than the best so far is dropped.
 * Nothing is written to the project.
 */
public final class EditLoop {

    private EditLoop() {}

    public enum Status {
        DONE,
        GATE_FAILED,
        CAP,
        /** The model asked a question instead of guessing. */
        QUESTION
    }

    public record Outcome(
            Status status,
            Map<String, String> changed,
            List<GateResult> results,
            List<RepairLoop.Attempt> attempts,
            String question,
            String stoppedBy) {
        public boolean passed() {
            return status == Status.DONE;
        }
    }

    public record Settings(int maxPerGate, int maxAttempts, Spend spend, Path tmpRoot) {}

    public static Outcome run(
            LLMClient client,
            String model,
            Project project,
            String system,
            String firstUser,
            String request,
            List<Gate> gates,
            Settings s) {
        Map<String, String> base = project.files();
        List<RepairLoop.Attempt> attempts = new ArrayList<>();
        Map<String, Integer> perGate = new LinkedHashMap<>();
        Map<String, String> best = Map.of();
        List<GateResult> bestResults = List.of();
        RepairLoop.Score bestScore = null;
        String user = firstUser;
        boolean discarded = false;
        try {
            for (int n = 1; n <= s.maxAttempts(); n++) {
                s.spend().beforeCall();
                LLMResponse reply =
                        client.chat(
                                LLMRequest.builder()
                                        .model(model)
                                        .temperature(0.2)
                                        .addSystemMessage(system)
                                        .addUserMessage(user)
                                        .build());
                s.spend().add(reply);
                PatchSet patch = PatchSet.parse(reply.getContent());
                if (patch.question() != null)
                    return new Outcome(
                            Status.QUESTION, best, bestResults, attempts, patch.question(), "");
                List<GateResult> results;
                Map<String, String> changed = Map.of();
                boolean ran = false;
                if (patch.isEmpty()) {
                    results =
                            List.of(
                                    new GateResult(
                                            "G0",
                                            "patch",
                                            List.of(
                                                    new GateResult.Problem(
                                                            "",
                                                            0,
                                                            "",
                                                            "Your reply held no edit and no new file. Reply with === edit: PATH === blocks (SEARCH and REPLACE) or === file: PATH === blocks."))));
                } else {
                    PatchSet.Applied applied = patch.apply(base);
                    if (!applied.problems().isEmpty()) {
                        results =
                                List.of(
                                        new GateResult(
                                                "G0",
                                                "patch",
                                                applied.problems().stream()
                                                        .map(
                                                                p ->
                                                                        new GateResult.Problem(
                                                                                "", 0, "", p))
                                                        .toList()));
                    } else {
                        changed = applied.changed();
                        try (Workspace ws =
                                Writer.trial(project, project.with(changed), s.tmpRoot())) {
                            results = new ArrayList<>();
                            for (Gate g : gates) results.add(g.run(ws, null));
                        }
                        ran = true;
                    }
                }
                RepairLoop.Score score = RepairLoop.Score.of(results);
                String first =
                        results.stream()
                                .filter(r -> !r.passed())
                                .map(GateResult::gate)
                                .findFirst()
                                .orElse(null);
                if (first == null) {
                    attempts.add(new RepairLoop.Attempt(n, null, 0, true, "all gates passed"));
                    return new Outcome(Status.DONE, changed, results, attempts, null, "");
                }
                boolean keep = ran && (bestScore == null || score.compareTo(bestScore) <= 0);
                if (keep) {
                    best = changed;
                    bestResults = results;
                    bestScore = score;
                }
                discarded = ran && !keep;
                attempts.add(
                        new RepairLoop.Attempt(
                                n,
                                first,
                                score.problems(),
                                keep,
                                !ran
                                        ? "not a patch that applies"
                                        : keep ? "" : "worse than the best so far; thrown away"));
                if (perGate.merge(first, 1, Integer::sum) >= s.maxPerGate())
                    return new Outcome(
                            Status.GATE_FAILED,
                            best,
                            bestResults.isEmpty() ? results : bestResults,
                            attempts,
                            null,
                            "gate "
                                    + first
                                    + " still failed after "
                                    + perGate.get(first)
                                    + " attempts");
                StringBuilder feedback = new StringBuilder();
                for (GateResult r : results)
                    if (!r.passed()) feedback.append(r.feedback()).append('\n');
                user =
                        EditPrompt.repair(
                                request,
                                PatchSet.diff(base, project.with(ran ? changed : best)),
                                feedback.toString(),
                                discarded);
            }
            return new Outcome(
                    Status.GATE_FAILED,
                    best,
                    bestResults,
                    attempts,
                    null,
                    "the limit of " + s.maxAttempts() + " attempts was reached");
        } catch (Spend.CapReached cap) {
            return new Outcome(Status.CAP, best, bestResults, attempts, null, cap.getMessage());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
