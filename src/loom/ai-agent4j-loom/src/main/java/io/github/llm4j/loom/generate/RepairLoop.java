package io.github.llm4j.loom.generate;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the project, runs the gates, and feeds what failed back to the model until they pass or a
 * limit is reached. Each round asks for only the files that must change. The loop is monotone: an
 * attempt that leaves more failing gates, or more problems, than the best so far is thrown away and
 * the best is retried with the feedback, so it cannot oscillate. It stops at an attempt limit (per
 * gate and overall) or at the spend cap, with the best draft kept.
 */
public final class RepairLoop {

    private RepairLoop() {}

    public enum Status {
        /** Every gate passed. */
        DONE,
        /** The loop ended with a gate still failing. */
        GATE_FAILED,
        /** The spend cap stopped it. */
        CAP
    }

    /** How the loop is bounded and where it works. */
    public record Settings(
            int maxPerGate,
            int maxAttempts,
            Spend spend,
            Writer.Layout layout,
            Writer.Stamp stamp,
            Path tmpRoot,
            Progress progress) {

        public Settings(
                int maxPerGate,
                int maxAttempts,
                Spend spend,
                Writer.Layout layout,
                Writer.Stamp stamp,
                Path tmpRoot) {
            this(maxPerGate, maxAttempts, spend, layout, stamp, tmpRoot, Progress.NONE);
        }
    }

    /**
     * One round: what failed first, how many problems there were in all, and whether the change was
     * kept.
     */
    public record Attempt(
            int number, String firstFailingGate, int problems, boolean kept, String note) {}

    /**
     * What the loop ended with.
     *
     * @param workspace the best draft, open: the caller commits it and closes it; null when nothing
     *     was ever written
     * @param files the best draft's files, as the model wrote them (without headers)
     * @param results the gate results of the best draft
     */
    public record Outcome(
            Status status,
            Workspace workspace,
            Map<String, String> files,
            List<GateResult> results,
            List<Attempt> attempts,
            String stoppedBy) {
        public boolean passed() {
            return status == Status.DONE;
        }
    }

    /** How good a draft is: fewer failing gates first, then fewer problems. */
    record Score(int failingGates, int problems) implements Comparable<Score> {
        @Override
        public int compareTo(Score o) {
            return failingGates != o.failingGates
                    ? Integer.compare(failingGates, o.failingGates)
                    : Integer.compare(problems, o.problems);
        }

        static Score of(List<GateResult> results) {
            return new Score(
                    (int) results.stream().filter(r -> !r.passed()).count(),
                    results.stream().mapToInt(r -> r.problems().size()).sum());
        }
    }

    public static Outcome run(
            LLMClient client,
            String model,
            Plan plan,
            String system,
            String firstUser,
            Map<String, String> planFiles,
            List<Gate> gates,
            Settings s) {
        List<Attempt> attempts = new ArrayList<>();
        Map<String, Integer> perGate = new LinkedHashMap<>();
        Map<String, String> best = null;
        Workspace bestWs = null;
        List<GateResult> bestResults = List.of();
        Score bestScore = null;
        Map<String, String> current = new LinkedHashMap<>();
        String user = firstUser;
        boolean discarded = false;
        try {
            for (int n = 1; n <= s.maxAttempts(); n++) {
                s.spend().beforeCall();
                s.progress().event("attempt", Map.of("n", n, "of", s.maxAttempts()));
                LLMResponse reply =
                        client.chat(
                                LLMRequest.builder()
                                        .model(model)
                                        .temperature(0.2)
                                        .addSystemMessage(system)
                                        .addUserMessage(user)
                                        .build());
                s.spend().add(reply);
                Map<String, String> written = FileBlocks.parse(reply.getContent());
                Map<String, String> candidate = new LinkedHashMap<>(best == null ? Map.of() : best);
                candidate.putAll(written);
                current = candidate;
                Workspace ws = null;
                List<GateResult> results;
                String note = "";
                if (written.isEmpty()) {
                    results =
                            List.of(
                                    new GateResult(
                                            "G0",
                                            "write",
                                            List.of(
                                                    new GateResult.Problem(
                                                            "",
                                                            0,
                                                            "",
                                                            reply.getContent() != null
                                                                            && reply.getContent()
                                                                                    .contains(
                                                                                            "=== file:")
                                                                    ? "Your reply was cut off or a file did not end with a line === end ===. Reply with fewer or shorter files if needed, each between === file: PATH === and === end ==="
                                                                    : "Your reply held no files. Reply with files only, each between === file: PATH === and === end ==="
                                                                            + "."))));
                } else {
                    List<String> bad = Writer.problems(withPlan(candidate, planFiles));
                    if (!bad.isEmpty()) {
                        results =
                                List.of(
                                        new GateResult(
                                                "G0",
                                                "write",
                                                bad.stream()
                                                        .map(
                                                                b ->
                                                                        new GateResult.Problem(
                                                                                "", 0, "", b))
                                                        .toList()));
                    } else {
                        ws =
                                Writer.render(
                                        withPlan(candidate, planFiles),
                                        plan,
                                        s.layout(),
                                        s.stamp(),
                                        s.tmpRoot());
                        results = new ArrayList<>();
                        for (Gate g : gates) results.add(g.run(ws, plan));
                        // fixes the tool applied (safe ones) are part of the draft now
                        candidate = readBack(ws, candidate, s.layout());
                    }
                }
                for (GateResult r : results)
                    s.progress()
                            .event(
                                    "gate",
                                    Map.of(
                                            "attempt",
                                            n,
                                            "gate",
                                            r.gate(),
                                            "name",
                                            r.name(),
                                            "passed",
                                            r.passed(),
                                            "problems",
                                            r.problems().size()));
                Score score = Score.of(results);
                String first =
                        results.stream()
                                .filter(r -> !r.passed())
                                .map(GateResult::gate)
                                .findFirst()
                                .orElse(null);
                if (first != null && ws == null) {
                    // the reply could not even be written: it never becomes the best draft
                    attempts.add(
                            new Attempt(
                                    n, first, score.problems(), false, "not a writable project"));
                    int tries = perGate.merge(first, 1, Integer::sum);
                    if (tries >= s.maxPerGate())
                        return new Outcome(
                                Status.GATE_FAILED,
                                bestWs,
                                best,
                                bestResults,
                                attempts,
                                "gate " + first + " still failed after " + tries + " attempts");
                    StringBuilder feedback = new StringBuilder();
                    for (GateResult r : results) feedback.append(r.feedback()).append('\n');
                    user =
                            PromptAssembly.repair(
                                    plan,
                                    best == null ? Map.of() : best,
                                    feedback.toString(),
                                    false);
                    continue;
                }
                if (first == null) {
                    attempts.add(new Attempt(n, null, 0, true, "all gates passed"));
                    close(bestWs);
                    return new Outcome(Status.DONE, ws, candidate, results, attempts, "");
                }
                boolean keep = bestScore == null || score.compareTo(bestScore) <= 0;
                if (keep) {
                    close(bestWs);
                    best = candidate;
                    bestWs = ws;
                    bestResults = results;
                    bestScore = score;
                } else {
                    close(ws);
                    note = "worse than the best so far; thrown away";
                }
                discarded = !keep;
                attempts.add(new Attempt(n, first, score.problems(), keep, note));
                int tries = perGate.merge(first, 1, Integer::sum);
                if (tries >= s.maxPerGate())
                    return new Outcome(
                            Status.GATE_FAILED,
                            bestWs,
                            best,
                            bestResults,
                            attempts,
                            "gate " + first + " still failed after " + tries + " attempts");
                StringBuilder feedback = new StringBuilder();
                for (GateResult r : results)
                    if (!r.passed())
                        feedback.append(quoted(shifted(r), candidate).feedback()).append('\n');
                user =
                        PromptAssembly.repair(
                                plan,
                                best == null ? Map.of() : best,
                                feedback.toString(),
                                discarded);
            }
            return new Outcome(
                    Status.GATE_FAILED,
                    bestWs,
                    best,
                    bestResults,
                    attempts,
                    "the limit of " + s.maxAttempts() + " attempts was reached");
        } catch (Spend.CapReached cap) {
            return new Outcome(Status.CAP, bestWs, best, bestResults, attempts, cap.getMessage());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The result as the model should read it: every file has one header line the model never wrote,
     * so the line numbers are one less than in the file on disk.
     */
    private static GateResult shifted(GateResult r) {
        return new GateResult(
                r.gate(),
                r.name(),
                r.problems().stream()
                        .map(
                                p ->
                                        new GateResult.Problem(
                                                p.file(),
                                                p.line() > 1 ? p.line() - 1 : p.line(),
                                                p.rule(),
                                                p.message()))
                        .toList());
    }

    /**
     * Each problem that points at a line of a file the model wrote also shows that line, so a
     * syntax error is read against the text and not against a number.
     */
    private static GateResult quoted(GateResult r, Map<String, String> files) {
        return new GateResult(
                r.gate(),
                r.name(),
                r.problems().stream()
                        .map(
                                p -> {
                                    String text = files.get(p.file());
                                    if (text == null || p.line() <= 0) return p;
                                    String[] lines = text.split("\\R", -1);
                                    if (p.line() > lines.length) return p;
                                    String at = lines[p.line() - 1].strip();
                                    if (at.length() > 240) at = at.substring(0, 240) + "...";
                                    return new GateResult.Problem(
                                            p.file(),
                                            p.line(),
                                            p.rule(),
                                            p.message() + "\n      that line reads: " + at);
                                })
                        .toList());
    }

    /** The model's files plus the ones the generator writes itself (the plan). */
    private static Map<String, String> withPlan(
            Map<String, String> files, Map<String, String> planFiles) {
        Map<String, String> all = new LinkedHashMap<>(files);
        all.putAll(planFiles);
        return all;
    }

    private static Map<String, String> readBack(
            Workspace ws, Map<String, String> files, Writer.Layout layout) throws IOException {
        Map<String, String> out = new LinkedHashMap<>(files);
        for (String f : files.keySet()) {
            Path p = ws.dir().resolve(Writer.placed(layout, f));
            if (Files.isRegularFile(p)) out.put(f, Writer.withoutHeader(Files.readString(p)));
        }
        return out;
    }

    private static void close(Workspace ws) {
        if (ws == null) return;
        try {
            ws.close();
        } catch (IOException ignored) {
            // a temporary folder that will not go is not worth failing a generation over
        }
    }
}
