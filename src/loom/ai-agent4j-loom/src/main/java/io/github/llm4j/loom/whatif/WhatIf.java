package io.github.llm4j.loom.whatif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.Ask;
import io.github.llm4j.loom.execution.AskHook;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.wtest.Failures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What would have happened if something had been different: a past run's journal is copied, cut
 * back to a step, one or more things are changed there (a variable, what a model or classifier
 * said, a person's answer, a failure, a tool's result), and the script is run forward from the step
 * over the copy, with nothing that has an effect. The original run is never written.
 *
 * <p>A model call is never invented. Where the question put to a model is unchanged by the change
 * (same agent, model, prompts and task text), the recorded answer is reused; where an earlier
 * change flowed into the question, the candidate stops there and says what it needs.
 */
public final class WhatIf {

    private WhatIf() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What to change. {@code kind}: set, answer, human, fail or tool. */
    public record Change(String kind, String target, String value, int times) {
        public String describe() {
            return switch (kind) {
                case "set" -> "set " + target + " = " + value;
                case "answer" -> target + " answers " + value;
                case "human" -> "a person answers " + value;
                case "fail" -> target
                        + " fails ("
                        + value
                        + (times > 1 ? ", " + times + " times" : "")
                        + ")";
                case "tool" -> "tool " + target + " returns " + value;
                default -> kind + " " + target;
            };
        }
    }

    /**
     * @param models {@code recorded} (reuse a recorded answer when its question is unchanged) or
     *     {@code scripted} (only the answers the changes give)
     * @param until stop before this step
     */
    public record Request(
            Path script,
            String workflow,
            Map<String, String> inputs,
            RunJournal journal,
            Why.Selector at,
            List<Change> changes,
            String models,
            String until,
            Set<String> masked,
            Path candidateScript,
            Map<String, String> prompts,
            Map<String, String> modelEdits,
            ObserveReplay.Live live) {
        public Request {
            prompts = prompts == null ? Map.of() : prompts;
            modelEdits = modelEdits == null ? Map.of() : modelEdits;
        }

        public Request(
                Path script,
                String workflow,
                Map<String, String> inputs,
                RunJournal journal,
                Why.Selector at,
                List<Change> changes,
                String models,
                String until,
                Set<String> masked) {
            this(
                    script, workflow, inputs, journal, at, changes, models, until, masked, null,
                    null, null, null);
        }
    }

    /** A question the candidate could not answer, and why it was left. */
    public record Need(String step, String statement, String what, String because) {}

    /** A way the run may reach outside itself. */
    public record Effect(String step, String statement, String via) {}

    /** The effects the two trajectories may cause after the point, and what the change flips. */
    public record EffectDelta(
            List<Effect> onlyOriginal, List<Effect> onlyCandidate, boolean unsafe) {}

    /** Everything a what-if found. */
    public record Outcome(
            String at,
            String atStatement,
            List<Change> changes,
            String models,
            ObserveReplay.Result original,
            ObserveReplay.Result candidate,
            TrajectoryDiff.Diff diff,
            Object originalResult,
            Object candidateResult,
            Need need,
            EffectDelta effects,
            List<String> notes,
            String originalHash,
            String candidateHash) {}

    public static Outcome run(Request r) throws IOException {
        if (!List.of("recorded", "scripted", "live").contains(r.models()))
            throw new IllegalArgumentException("--models takes recorded, scripted or live.");
        if (r.models().equals("live") && r.live() == null)
            throw new IllegalArgumentException(
                    "--models live needs models to call and a spend cap.");
        ObserveReplay.Options options =
                new ObserveReplay.Options(r.script(), r.workflow(), r.inputs(), r.masked());
        ObserveReplay.Options candidateOptions =
                new ObserveReplay.Options(
                        r.candidateScript() != null ? r.candidateScript() : r.script(),
                        r.workflow(),
                        r.inputs(),
                        r.masked(),
                        edits(r));

        // 1. the run as it was, and what each of its steps asked
        Map<String, Ask> originalAsks = new LinkedHashMap<>();
        ObserveReplay.Result original =
                ObserveReplay.run(
                        options,
                        r.journal(),
                        new ObserveReplay.Hooks(
                                (ask, recorded) -> {
                                    if (recorded) originalAsks.put(ask.step(), ask);
                                    return null;
                                },
                                null,
                                null,
                                null,
                                null,
                                null));
        String at =
                Why.locate(r.script(), r.at(), original.steps())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "That statement never ran in this run, so there is no moment to go back to: "
                                                        + describe(r.at())
                                                        + "."));
        Function<String, String> label = Why.labels(r.script());
        LoomScript script = new LoomLoader().load(r.script().toString());

        // 2. the changes
        Map<String, Deque<Object>> answers = new LinkedHashMap<>();
        Map<String, Map<String, String>> setBefore = new LinkedHashMap<>();
        Map<String, ObserveReplay.Fail> failures = new LinkedHashMap<>();
        List<String> humans = new ArrayList<>();
        Map<String, String> tools = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (Change c : r.changes()) {
            switch (c.kind()) {
                case "set" -> setBefore
                        .computeIfAbsent(at, k -> new LinkedHashMap<>())
                        .put(c.target(), c.value());
                case "answer" -> answers.computeIfAbsent(c.target(), k -> new ArrayDeque<>())
                        .add(answerValue(script, c.target(), c.value()));
                case "human" -> humans.add(c.value());
                case "fail" -> {
                    if (!Failures.CATEGORIES.contains(c.value()))
                        throw new IllegalArgumentException(
                                "Unknown failure category \""
                                        + c.value()
                                        + "\"; use one of "
                                        + Failures.CATEGORIES
                                        + ".");
                    failures.put(c.target(), new ObserveReplay.Fail(c.value(), c.times()));
                }
                case "tool" -> tools.put(c.target(), c.value());
                default -> throw new IllegalArgumentException("Unknown change " + c.kind());
            }
        }
        for (String target : answers.keySet())
            if (script.getAgents().stream().noneMatch(a -> a.getName().equals(target))
                    && script.findIntentClassifier(target) == null)
                throw new IllegalArgumentException(
                        "--answer names "
                                + target
                                + ", which is not an agent or a classifier of this script.");

        // 3. the copy of the journal, cut back to the step
        RunJournal overlay = cutBack(r.journal(), original.steps(), original.steps().indexOf(at));

        if (!setBefore.isEmpty())
            overlay.put(
                    at + ObserveReplay.SET, new RunJournal.Entry("whatif_set", setBefore.get(at)));

        // 4. forward
        Map<String, String> because = new LinkedHashMap<>();
        boolean recordedModels = !r.models().equals("scripted");
        boolean liveModels = r.models().equals("live");
        AskHook hook =
                (ask, recorded) -> {
                    if (recorded) return null;
                    Deque<Object> queue = answers.get(ask.target());
                    if (queue != null && !queue.isEmpty()) return queue.poll();
                    if (!recordedModels) return null;
                    Ask before = originalAsks.get(ask.step());
                    RunJournal.Entry entry = r.journal().get(ask.step()).orElse(null);
                    if (before == null || entry == null || "failed".equals(entry.kind())) {
                        because.put(ask.step(), "nothing was recorded for this step");
                        return null;
                    }
                    String why = differs(before, ask);
                    if (why == null) return entry.value();
                    because.put(ask.step(), why);
                    return null;
                };
        ObserveReplay.Result candidate =
                ObserveReplay.run(
                        candidateOptions,
                        overlay,
                        new ObserveReplay.Hooks(
                                hook,
                                failures,
                                humans,
                                tools,
                                r.until(),
                                liveModels ? r.live() : null));

        // 5. what differs
        Function<String, String> candidateLabel =
                r.candidateScript() == null ? label : Why.labels(r.candidateScript());
        Set<String> originalSteps = new LinkedHashSet<>(original.steps());
        TrajectoryDiff.Diff diff =
                TrajectoryDiff.between(
                        original,
                        candidate,
                        at,
                        step ->
                                originalSteps.contains(step)
                                        ? label.apply(step)
                                        : candidateLabel.apply(step));
        Need need = null;
        if (candidate.status().equals("needs")) {
            String step = candidate.stoppedAt();
            need =
                    new Need(
                            step,
                            label.apply(step),
                            candidate.reason(),
                            because.getOrDefault(
                                    step,
                                    "the answers you gave do not cover it, and a model is never guessed"));
        }
        if (candidate.divergence() != null)
            notes.add(
                    "The candidate and the journal disagree: "
                            + candidate.divergence().detail()
                            + ".");
        for (Change c : r.changes())
            if (c.kind().equals("set")
                    && original.steps().indexOf(at) >= 0
                    && !original.variables().containsKey(c.target()))
                notes.add(
                        "The run never set "
                                + c.target()
                                + ", so this adds a variable the script did not have.");
        if (candidate.status().equals("budget"))
            notes.add(
                    "The live run stopped at its spend cap after "
                            + candidate.calls()
                            + " model calls ("
                            + candidate.tokens()
                            + " tokens): "
                            + candidate.reason()
                            + ".");
        else if (liveModels)
            notes.add(
                    "Live models made "
                            + candidate.calls()
                            + " calls ("
                            + candidate.tokens()
                            + " tokens); every tool, task and person was simulated.");
        LoomScript candidateScript =
                r.candidateScript() == null
                        ? script
                        : new LoomLoader().load(r.candidateScript().toString());
        EffectDelta effects = effects(script, candidateScript, original, candidate, at, label);
        return new Outcome(
                at,
                label.apply(at),
                r.changes(),
                r.models(),
                original,
                candidate,
                diff,
                original.variables().get("result"),
                candidate.variables().get("result"),
                need,
                effects,
                notes,
                io.github.llm4j.loom.travel.ScriptHash.of(r.script()),
                io.github.llm4j.loom.travel.ScriptHash.of(
                        r.candidateScript() != null ? r.candidateScript() : r.script()));
    }

    /** Prompt and model edits as a change to the loaded candidate script. */
    private static java.util.function.Consumer<LoomScript> edits(Request r) {
        if (r.prompts().isEmpty() && r.modelEdits().isEmpty()) return null;
        return script -> {
            for (Map.Entry<String, String> e : r.prompts().entrySet()) {
                AgentDef a = agent(script, e.getKey(), "--prompt");
                String v = e.getValue();
                String ref = a.getPromptRef();
                if (v.startsWith("@")) {
                    if (ref == null)
                        throw new IllegalArgumentException(
                                "--prompt "
                                        + a.getName()
                                        + "="
                                        + v
                                        + ": this agent has no prompt: file to take a version of.");
                    a.setPrompt(io.github.llm4j.loom.prompt.PromptRef.parse(ref).id() + v);
                } else {
                    a.setPrompt(null);
                    a.setSystemTemplate(null);
                    a.setSystemPrompt(v);
                }
            }
            for (Map.Entry<String, String> e : r.modelEdits().entrySet())
                agent(script, e.getKey(), "--model").setModel(e.getValue());
        };
    }

    private static AgentDef agent(LoomScript script, String name, String flag) {
        return script.getAgents().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        flag
                                                + " names "
                                                + name
                                                + ", which is not an agent of this script."));
    }

    private static String describe(Why.Selector s) {
        if (s.step() != null) return "step " + s.step();
        if (s.node() != null) return "node " + s.node();
        return "line " + s.line();
    }

    /** Why two questions are not the same question, or null when they are. */
    private static String differs(Ask before, Ask now) {
        if (!before.kind().equals(now.kind()) || !before.target().equals(now.target()))
            return "a different agent is asked here ("
                    + before.target()
                    + " before, "
                    + now.target()
                    + " now)";
        if (!before.input().equals(now.input()))
            return "its task text changed (it was \""
                    + cut(before.input())
                    + "\", now \""
                    + cut(now.input())
                    + "\")";
        if (!before.config().equals(now.config())) return "the agent's model or prompt changed";
        return null;
    }

    private static String cut(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    /** The journal without the results of the step and everything after it. */
    static RunJournal cutBack(RunJournal journal, List<String> steps, int atIndex) {
        Set<String> after = new LinkedHashSet<>(steps.subList(Math.max(atIndex, 0), steps.size()));
        Set<String> before = new LinkedHashSet<>(steps.subList(0, Math.max(atIndex, 0)));
        RunJournal copy = RunJournal.inMemory();
        boolean rederive = scriptRewindsOnly(journal);
        journal.all()
                .forEach(
                        (key, entry) -> {
                            if (rederive && key.equals(Generations.KEY)) return;
                            String base =
                                    key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
                            if (after.contains(base) && !before.contains(base)) return;
                            if (base.matches(".*/p\\d+")
                                    && after.contains(base.replaceFirst("/p\\d+$", ""))) return;
                            copy.put(key, entry);
                        });
        return copy;
    }

    private static boolean scriptRewindsOnly(RunJournal journal) {
        Generations g = new Generations(journal);
        return g.any() && g.all().stream().allMatch(b -> "script".equals(b.by()));
    }

    // ---- answers

    private static Object answerValue(LoomScript script, String target, String text) {
        IntentClassifierDef def = script.findIntentClassifier(target);
        if (def != null) return classifierRecord(def, text);
        try {
            JsonNode n = JSON.readTree(text);
            if (n != null && (n.isObject() || n.isArray()))
                return JSON.convertValue(n, Object.class);
        } catch (IOException e) {
            // not JSON: it is the text of the answer
        }
        return text;
    }

    /** A classifier's answer as the journal holds it, from {@code {"question": "label", ...}}. */
    static Map<String, Object> classifierRecord(IntentClassifierDef def, String json) {
        JsonNode given;
        try {
            given = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "--answer "
                            + def.getName()
                            + " takes JSON with a label for each question, for example {\""
                            + def.getQuestions().get(0).name()
                            + "\": \"...\"}.");
        }
        if (given == null || !given.isObject())
            throw new IllegalArgumentException(
                    "--answer "
                            + def.getName()
                            + " takes a JSON object with a label for each question.");
        Map<String, Object> out = new LinkedHashMap<>();
        for (IntentClassifierDef.Question q : def.getQuestions()) {
            JsonNode label = given.get(q.name());
            if (label == null)
                throw new IllegalArgumentException(
                        "--answer " + def.getName() + " needs an answer for " + q.name() + ".");
            String value = label.asText();
            List<String> allowed =
                    q.kind() == IntentClassifierDef.Kind.ASK
                            ? List.of("yes", "no")
                            : def.possibleLabels(q);
            if (!allowed.contains(value))
                throw new IllegalArgumentException(
                        q.name()
                                + " of "
                                + def.getName()
                                + " can be "
                                + String.join(", ", allowed)
                                + ", not "
                                + value
                                + ".");
            out.put(q.name(), value);
            if (q.kind() == IntentClassifierDef.Kind.ASK)
                out.put(q.name() + "_probability", value.equals("yes") ? 0.99 : 0.01);
            else out.put(q.name() + "_confidence", 0.99);
        }
        out.put("confidence", 0.99);
        out.put("calibrated", false);
        out.put("backend", "what if");
        out.put("model", def.getName());
        return out;
    }

    // ---- effects

    private static final Set<String> EFFECT_KINDS =
            Set.of("webhook", "email", "http", "file", "shell", "sql");

    private static EffectDelta effects(
            LoomScript script,
            LoomScript candidateScript,
            ObserveReplay.Result original,
            ObserveReplay.Result candidate,
            String at,
            Function<String, String> label) {
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        script.getTools().forEach(t -> tools.put(t.getName(), t));
        Map<String, ToolDef> candidateTools = new LinkedHashMap<>();
        candidateScript.getTools().forEach(t -> candidateTools.put(t.getName(), t));
        List<Effect> a = effectsOf(script, tools, original, at, label);
        List<Effect> b = effectsOf(candidateScript, candidateTools, candidate, at, label);
        Set<String> inA = new LinkedHashSet<>();
        Set<String> inB = new LinkedHashSet<>();
        a.forEach(e -> inA.add(e.step() + "|" + e.via()));
        b.forEach(e -> inB.add(e.step() + "|" + e.via()));
        List<Effect> onlyA =
                a.stream().filter(e -> !inB.contains(e.step() + "|" + e.via())).toList();
        List<Effect> onlyB =
                b.stream().filter(e -> !inA.contains(e.step() + "|" + e.via())).toList();
        return new EffectDelta(onlyA, onlyB, !onlyB.isEmpty());
    }

    private static List<Effect> effectsOf(
            LoomScript script,
            Map<String, ToolDef> tools,
            ObserveReplay.Result run,
            String at,
            Function<String, String> label) {
        List<Effect> out = new ArrayList<>();
        int from = run.steps().indexOf(at);
        if (from < 0) return out;
        for (String step : run.steps().subList(from, run.steps().size())) {
            String statement = label.apply(step);
            if (!statement.startsWith("delegate ")) continue;
            String agent = statement.substring("delegate ".length()).trim();
            for (AgentDef def : script.getAgents()) {
                if (!def.getName().equals(agent)) continue;
                for (String tool : def.getTools()) {
                    ToolDef t = tools.get(tool);
                    if (t != null && t.getKind() != null && EFFECT_KINDS.contains(t.getKind()))
                        out.add(new Effect(step, statement, tool + " (" + t.getKind() + ")"));
                }
            }
        }
        return out;
    }
}
