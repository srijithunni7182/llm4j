package io.github.llm4j.loom.cli;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.generate.CheckGate;
import io.github.llm4j.loom.generate.DeltaGate;
import io.github.llm4j.loom.generate.EditLoop;
import io.github.llm4j.loom.generate.EditPrompt;
import io.github.llm4j.loom.generate.FileFidelityGate;
import io.github.llm4j.loom.generate.Gate;
import io.github.llm4j.loom.generate.GateResult;
import io.github.llm4j.loom.generate.PatchSet;
import io.github.llm4j.loom.generate.PlanJson;
import io.github.llm4j.loom.generate.PlanMarkdown;
import io.github.llm4j.loom.generate.Project;
import io.github.llm4j.loom.generate.Spend;
import io.github.llm4j.loom.generate.TestsGate;
import io.github.llm4j.loom.generate.Transcript;
import io.github.llm4j.loom.generate.Workspace;
import io.github.llm4j.loom.generate.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code weave generate --edit script "change"}: the model proposes a patch, the patch is applied
 * to a trial copy and held to the gates (check, the review compared with before, the project's own
 * tests, the plan), and the developer reads the diff and the delta. Nothing is written to the
 * project until {@code --apply} (or {@code --yes}), and a change that makes the review or the cost
 * worse also needs {@code --accept-worse}.
 */
final class GenerateEdit {

    private GenerateEdit() {}

    static int run(GenerateCommand c, WeaveEnv env) {
        Path script = c.edit.toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(script) || !script.toString().endsWith(".loom")) {
            env.err().println("Error: --edit takes a .loom script; " + script + " is not one.");
            return 2;
        }
        boolean fromSaved = c.applyPatch != null;
        if (!fromSaved && (c.description == null || c.description.isBlank())) {
            env.err()
                    .println(
                            "Error: say what to change (weave generate --edit main.loom \"add a human approval before publishing\"), or give --apply-patch.");
            return 1;
        }
        Project project;
        DeltaGate.Snapshot baseline;
        try {
            project = Project.read(script);
            baseline = GenerateReview.snapshotter(env, c.prices).snapshot(script);
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
        DeltaGate delta =
                new DeltaGate(baseline, GenerateReview.snapshotter(env, c.prices), c.worseFactor);
        List<Gate> gates =
                List.of(
                        new CheckGate(GenerateCommand.checker(env)),
                        delta,
                        TestsGate.passing(),
                        new FileFidelityGate(project.relOf("plan.json")));
        Spend spend = new Spend(c.maxTokens, c.maxCost, null);
        Map<String, String> changed;
        List<GateResult> results;
        String request;
        int attempts = 0;
        if (fromSaved) {
            PatchSet.Saved saved;
            try {
                saved =
                        PatchSet.read(
                                Files.readString(c.applyPatch.toPath(), StandardCharsets.UTF_8));
            } catch (java.io.IOException | IllegalArgumentException e) {
                env.err().println("Error: " + c.applyPatch + ": " + e.getMessage());
                return 2;
            }
            for (var w : saved.was().entrySet()) {
                String now = project.files().get(w.getKey());
                if (now == null || !PatchSet.sha(now).equals(w.getValue())) {
                    env.err()
                            .println(
                                    "Error: "
                                            + w.getKey()
                                            + " has changed since the patch was made, so it would not apply to what is there now. Make the patch again.");
                    return 2;
                }
            }
            for (String path : saved.files().keySet()) {
                String bad = Writer.problemWith(path);
                if (bad != null) {
                    env.err().println("Error: the saved patch names " + bad + ".");
                    return 2;
                }
            }
            changed = saved.files();
            request = saved.request();
            attempts = 0;
            results = new ArrayList<>();
            try (Workspace ws =
                    Writer.trial(
                            project,
                            project.with(changed),
                            Path.of(System.getProperty("java.io.tmpdir")))) {
                for (Gate g : gates) results.add(g.run(ws, null));
            } catch (java.io.IOException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            }
        } else {
            LLMClient client;
            String model;
            Transcript.Recording recording = null;
            try {
                model = c.replay != null ? "replay" : GenerateCommand.modelName(c);
                client = GenerateCommand.clientFor(c, env);
            } catch (IllegalArgumentException | java.io.IOException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            }
            if (c.record != null) {
                recording = new Transcript.Recording(client);
                client = recording;
            }
            request =
                    c.description
                            + (c.answer == null
                                    ? ""
                                    : "\n(Answer to your question: " + c.answer + ")");
            List<String> open = new ArrayList<>();
            baseline.open()
                    .forEach(f -> open.add(f.rule() + " line " + f.line() + ": " + f.title()));
            String system =
                    EditPrompt.system(
                            GenerateCommand.reference(),
                            io.github.llm4j.loom.review.Reviews.catalog().all());
            EditLoop.Outcome outcome;
            c.progress.event("stage", Map.of("stage", "edit"));
            try {
                outcome =
                        EditLoop.run(
                                client,
                                model,
                                project,
                                system,
                                EditPrompt.change(project.files(), open, request),
                                request,
                                gates,
                                new EditLoop.Settings(
                                        c.maxAttempts,
                                        Math.max(12, c.maxAttempts),
                                        spend,
                                        Path.of(System.getProperty("java.io.tmpdir")),
                                        c.progress));
                if (recording != null) recording.save(c.record.toPath());
            } catch (IllegalStateException | java.io.IOException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            } catch (RuntimeException e) {
                env.err()
                        .println(
                                "Error: the model could not be asked: "
                                        + (e.getMessage() == null ? e : e.getMessage()));
                return 2;
            }
            if (outcome.status() == EditLoop.Status.QUESTION) {
                if (c.interactive) {
                    String given = env.human().promptHuman(outcome.question());
                    if (given != null && !given.isBlank()) {
                        c.answer = given.strip();
                        return run(c, env);
                    }
                }
                env.out()
                        .println(
                                "The change is ambiguous, so nothing was guessed. The question:\n  "
                                        + outcome.question());
                env.out()
                        .println(
                                "Answer it with: weave generate --edit "
                                        + c.edit
                                        + " \""
                                        + c.description
                                        + "\" --answer \"...\"");
                return 2;
            }
            changed = outcome.changed();
            results = outcome.results();
            attempts = outcome.attempts().size();
            if (outcome.status() == EditLoop.Status.CAP) {
                env.out().println("✗ Stopped by the spend cap: " + outcome.stoppedBy());
                env.out().println("Nothing was changed.");
                return 4;
            }
        }
        return present(c, env, project, request, changed, results, delta.last(), spend, attempts);
    }

    /** " (about $a → $b typical, $c → $d worst)" when both sides are priced. */
    private static String money(DeltaGate.WorkflowTokens was, DeltaGate.WorkflowTokens now) {
        if (was.typicalCost() == null || now.typicalCost() == null) return "";
        return " (about $"
                + was.typicalCost().toPlainString()
                + " → $"
                + now.typicalCost().toPlainString()
                + " typical, $"
                + (was.worstCost() == null ? "?" : was.worstCost().toPlainString())
                + " → $"
                + (now.worstCost() == null ? "?" : now.worstCost().toPlainString())
                + " worst)";
    }

    private static int present(
            GenerateCommand c,
            WeaveEnv env,
            Project project,
            String request,
            Map<String, String> changed,
            List<GateResult> results,
            DeltaGate.Delta delta,
            Spend spend,
            int attempts) {
        boolean noChange = changed.isEmpty();
        Map<String, String> after = project.with(changed);
        List<GateResult> failing = results.stream().filter(r -> !r.passed()).toList();
        boolean onlyWorse =
                !failing.isEmpty() && failing.stream().allMatch(r -> r.gate().equals("G2"));
        boolean passed = failing.isEmpty();
        StringBuilder out = new StringBuilder();
        if (noChange) {
            out.append("The model proposed no change that holds up.\n");
        } else {
            out.append(PatchSet.diff(project.files(), after)).append('\n');
        }
        for (GateResult r : results)
            out.append(r.passed() ? "✓ " : "✗ ")
                    .append(r.gate())
                    .append(' ')
                    .append(r.name())
                    .append('\n');
        for (GateResult r : failing)
            for (var p : r.problems()) out.append("    ").append(p.text()).append('\n');
        if (delta != null) {
            out.append("\nReview: ")
                    .append(delta.added().size())
                    .append(" finding")
                    .append(delta.added().size() == 1 ? "" : "s")
                    .append(" added, ")
                    .append(delta.removed().size())
                    .append(" removed.\n");
            delta.added()
                    .forEach(
                            f ->
                                    out.append("  + ")
                                            .append(f.severity())
                                            .append(' ')
                                            .append(f.rule())
                                            .append(" line ")
                                            .append(f.line())
                                            .append(": ")
                                            .append(f.title())
                                            .append('\n'));
            delta.removed()
                    .forEach(
                            f ->
                                    out.append("  - ")
                                            .append(f.rule())
                                            .append(": ")
                                            .append(f.title())
                                            .append('\n'));
            for (var e : delta.after().entrySet()) {
                var was = delta.before().get(e.getKey());
                if (was == null) continue;
                out.append("  cost of ")
                        .append(e.getKey())
                        .append(": typical ")
                        .append(was.typical())
                        .append(" → ")
                        .append(e.getValue().typical())
                        .append(" tokens, worst ")
                        .append(was.unbounded() ? "unbounded" : was.worst())
                        .append(" → ")
                        .append(e.getValue().unbounded() ? "unbounded" : e.getValue().worst())
                        .append(money(was, e.getValue()))
                        .append('\n');
            }
        }
        List<String> tests = changed.keySet().stream().filter(k -> k.startsWith("tests/")).toList();
        if (!tests.isEmpty())
            out.append("Tests changed (check they say what you now want): ")
                    .append(String.join(", ", tests))
                    .append('\n');
        if (spend.calls() > 0)
            out.append("Generation cost: ")
                    .append(spend.calls())
                    .append(" model calls, ")
                    .append(spend.tokens())
                    .append(" tokens")
                    .append(attempts > 1 ? ", " + attempts + " attempts" : "")
                    .append('\n');
        if (onlyWorse)
            out.append(
                    "\n⚠ This change makes the project WORSE than it was (see G2 above). It needs --accept-worse to be applied.\n");
        env.out().print(out);
        try {
            if (c.patchOut != null && !noChange) {
                Files.writeString(
                        c.patchOut.toPath(),
                        PatchSet.save(request, project.files(), after),
                        StandardCharsets.UTF_8);
                env.out()
                        .println(
                                "Patch saved to "
                                        + c.patchOut
                                        + " (apply it later with --apply-patch, without asking the model again).");
            }
        } catch (java.io.IOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        if (noChange) return 3;
        if (!passed && !(onlyWorse && c.acceptWorse)) {
            env.out()
                    .println(
                            "\nNothing was changed. "
                                    + (onlyWorse
                                            ? "Run with --accept-worse to apply it anyway."
                                            : "The gates above did not pass."));
            return 3;
        }
        boolean apply = c.apply || c.yes;
        if (!apply && c.interactive) {
            String a = env.human().promptHuman("Apply this change? (yes/no)");
            apply = a != null && a.strip().toLowerCase().startsWith("y");
        }
        if (!apply) {
            env.out()
                    .println(
                            "\nNothing was changed. Apply it with --apply"
                                    + (c.patchOut == null
                                            ? " (add --patch-out FILE to keep the patch and apply it without asking the model again)."
                                            : " or --apply-patch " + c.patchOut + "."));
            return 0;
        }
        try {
            List<String> written = write(project, changed);
            env.out().println("\nApplied to " + project.root() + ": " + String.join(", ", written));
            return 0;
        } catch (java.io.IOException | IllegalStateException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    /** Writes the changed files; refuses if one has changed on disk since it was read. */
    static List<String> write(Project project, Map<String, String> changed)
            throws java.io.IOException {
        Map<String, String> toWrite = new LinkedHashMap<>(changed);
        String plan = changed.get("plan.json");
        if (plan != null) {
            try {
                toWrite.put("PLAN.md", PlanMarkdown.render(PlanJson.read(plan)));
            } catch (IllegalArgumentException e) {
                // an unreadable plan is already G5's to report
            }
        }
        for (String logical : toWrite.keySet()) {
            Path to = project.place(logical);
            if (!to.startsWith(project.root()))
                throw new IllegalStateException(logical + " would leave the project folder");
            String old = project.files().get(logical);
            if (old != null
                    && Files.isRegularFile(to)
                    && !Files.readString(to, StandardCharsets.UTF_8).equals(old))
                throw new IllegalStateException(
                        logical
                                + " changed on disk while the change was made; nothing was written. Make the change again.");
            if (old == null && Files.exists(to) && !logical.equals("PLAN.md"))
                throw new IllegalStateException(logical + " already exists; nothing was written.");
        }
        List<String> written = new ArrayList<>();
        for (var e : toWrite.entrySet()) {
            Path to = project.place(e.getKey());
            Files.createDirectories(to.getParent());
            Files.writeString(to, e.getValue(), StandardCharsets.UTF_8);
            written.add(e.getKey());
        }
        return written;
    }
}
