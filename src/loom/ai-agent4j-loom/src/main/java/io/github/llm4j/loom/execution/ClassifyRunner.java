package io.github.llm4j.loom.execution;

import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.ChoiceAnswer;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Option;
import io.github.llm4j.classify.Scale;
import io.github.llm4j.classify.ScoreAnswer;
import io.github.llm4j.classify.YesNoAnswer;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Runs {@code intentclassify Intent on message -> intent} steps: one {@code Classifier.classify}
 * call that answers every question of the declared {@code intentclassifier}, bound as a record
 * (docs/specs/07-classifier, DESIGN 5.2 to 5.4). The record has {@code <question>} (the label,
 * "yes"/"no" or the scale point), {@code <question>_confidence}, {@code <question>_probability},
 * {@code <question>_position}, {@code confidence} (the lowest among the choose questions), {@code
 * calibrated}, {@code backend} and {@code model}.
 *
 * <p>The answer is journaled, so a resumed run reuses it and never asks the backend twice. The call
 * is charged to the budget like a model call and a breach halts the run. The trace carries labels,
 * numbers and names, never the state.
 */
final class ClassifyRunner {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS,
                            true);

    private final HarnessExecutor executor;

    ClassifyRunner(HarnessExecutor executor) {
        this.executor = executor;
    }

    void run(IntentClassifyStmt stmt) {
        IntentClassifierDef def = executor.script().findIntentClassifier(stmt.getClassifier());
        if (def == null)
            throw new IllegalStateException(
                    "intentclassifier not found: " + stmt.getClassifier()); // validated earlier
        String stepId = executor.currentStep();
        String variable = executor.resolveVariableName(stmt.getVariableName());
        RunJournal journal = executor.journal();

        // A resumed run: the step already happened, so its recorded answer is reused and the
        // backend is not asked.
        var recorded = journal.get(stepId).filter(e -> !"retry".equals(e.kind()));
        if (recorded.isPresent() && executor.decisions().asking()) {
            try {
                ClassifyRequest before = request(stmt, def);
                executor.decisions()
                        .ask(
                                new Ask(
                                        stepId,
                                        "classify",
                                        def.getName(),
                                        String.valueOf(before.state()),
                                        String.valueOf(ModelNames.real(def.getModel()))),
                                true);
            } catch (StepFailure ignored) {
                // a recorded failure of the request itself has no question to report
            }
        }
        if (recorded.isPresent()) {
            RunJournal.Entry entry = recorded.get();
            if ("failed".equals(entry.kind())) {
                fail(stmt, def, stepId, String.valueOf(entry.value()), null, false, 0);
            } else {
                executor.setVariable(variable, entry.value());
                if (entry.value() instanceof Map<?, ?> answered) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    answered.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    executor.decisions().classified(stmt, stepId, copy);
                }
                replayed(def, entry.value());
            }
            return;
        }

        ClassifyRequest request;
        try {
            request = request(stmt, def);
        } catch (StepFailure bad) {
            fail(stmt, def, stepId, bad.getMessage(), bad, true, 0);
            return;
        }
        if (executor.decisions().asking()) {
            Object known =
                    executor.decisions()
                            .ask(
                                    new Ask(
                                            stepId,
                                            "classify",
                                            def.getName(),
                                            String.valueOf(request.state()),
                                            String.valueOf(ModelNames.real(def.getModel()))),
                                    false);
            if (known instanceof Map<?, ?> given) {
                Map<String, Object> copy = new LinkedHashMap<>();
                given.forEach((k, v) -> copy.put(String.valueOf(k), v));
                journal.put(stepId, new RunJournal.Entry("classify", copy));
                executor.setVariable(variable, copy);
                executor.decisions().classified(stmt, stepId, copy);
                replayed(def, copy);
                return;
            }
        }

        Classifier classifier = executor.classifierFor(def);
        int maxAttempts = stmt.getRetryCount() + 1;
        Throwable last = null;
        int tried = 0;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            tried++;
            try {
                if (attempt > 0 && stmt.getBackoffMillis() > 0) {
                    executor.sleeper()
                            .sleep(
                                    Duration.ofMillis(
                                            stmt.getBackoffMillis() << Math.min(attempt - 1, 10)));
                }
                ClassifyResult result =
                        stmt.getTimeoutMillis() > 0
                                ? executor.callWithTimeout(
                                        () -> classifier.classify(request),
                                        stmt.getTimeoutMillis(),
                                        "intentclassify " + def.getName())
                                : classifier.classify(request);
                answered(stmt, def, stepId, variable, request, result, tried);
                return;
            } catch (BudgetExceeded breach) {
                throw breach; // a breach halts the run, as it does for any model call
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while classifying with " + def.getName(), e);
            } catch (
                    io.github.llm4j.agent.AgentInterrupt
                            pause) { // includes RunSuspended: waiting for a person is not a failure
                throw pause;
            } catch (Exception e) {
                last = e;
            }
        }
        String message =
                last == null || last.getMessage() == null ? "unknown error" : last.getMessage();
        fail(stmt, def, stepId, message, last, true, tried);
    }

    // ---- the state

    private ClassifyRequest request(IntentClassifyStmt stmt, IntentClassifierDef def) {
        ClassifyRequest.Builder b = ClassifyRequest.builder();
        for (RunStmt.Arg in : stmt.getInputs()) {
            String text =
                    switch (in.kind()) {
                        case STRING -> executor.resolve(in.text());
                        default -> {
                            // A variable that was never set reads as "" elsewhere in Loom;
                            // classifying "" would fail open, so the step fails instead.
                            String head =
                                    in.text().contains(".")
                                            ? in.text().substring(0, in.text().indexOf('.'))
                                            : in.text();
                            Object value =
                                    executor.view().getAll().containsKey(head)
                                            ? ConditionEvaluator.resolvePath(
                                                    in.text(), executor.view())
                                            : null;
                            if (value == null) {
                                throw new StepFailure(
                                        "what to classify refers to "
                                                + in.text()
                                                + ", which has no value, so "
                                                + def.getName()
                                                + " was not asked",
                                        null);
                            }
                            yield text(value);
                        }
                    };
            text = guardPii(def, text);
            if (in.name().isEmpty()) b.state(text);
            else b.state(in.name(), text);
        }
        for (IntentClassifierDef.Question q : def.getQuestions()) {
            String instructions = def.effectiveInstructions(q);
            switch (q.kind()) {
                case CHOOSE -> b.choice(
                        q.name(), instructions, q.labels().stream().map(Option::of).toList());
                case ASK -> b.yesNo(q.name(), instructions);
                case RATE -> b.score(q.name(), instructions, new Scale(q.labels()));
            }
        }
        try {
            return b.build();
        } catch (IllegalArgumentException e) {
            throw new StepFailure(e.getMessage(), e); // for example a state over the limit
        }
    }

    private static final io.github.llm4j.privacy.PIIDetector DETECTOR =
            new io.github.llm4j.privacy.RegexPIIDetector();

    /**
     * The classifier's {@code pii:} setting, applied with the masker an agent's {@code guard} uses:
     * {@code mask} replaces personal data before the state leaves, {@code block} refuses a state
     * that carries any, {@code warn} sends it and records the finding. Findings name types and
     * counts, never values.
     */
    private String guardPii(IntentClassifierDef def, String text) {
        String mode = def.getPii();
        if (mode == null) return text;
        java.util.Map<io.github.llm4j.privacy.PIIType, Integer> found =
                new java.util.EnumMap<>(io.github.llm4j.privacy.PIIType.class);
        for (io.github.llm4j.privacy.PIIEntity e : DETECTOR.detect(text).getEntities()) {
            if (io.github.llm4j.privacy.MaskingLLMClient.PERSONAL.contains(e.getType()))
                found.merge(e.getType(), 1, Integer::sum);
        }
        if (found.isEmpty()) return text;
        String types =
                String.join(
                        ", ",
                        new java.util.TreeSet<>(found.keySet().stream().map(Enum::name).toList()));
        String event =
                switch (mode) {
                    case "mask" -> "pii_masked";
                    case "block" -> "pii_blocked";
                    default -> "pii_detected";
                };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("classifier", def.getName());
        data.put("step", executor.currentStep());
        data.put(
                "types",
                new java.util.TreeMap<>(
                                found.entrySet().stream()
                                        .collect(
                                                java.util.stream.Collectors.toMap(
                                                        e -> e.getKey().name(),
                                                        Map.Entry::getValue)))
                        .toString());
        data.put("event", event);
        executor.audit(event, data);
        executor.trace(
                TraceEvent.GUARD,
                null,
                event.replace('_', ' ')
                        + " in the state of "
                        + def.getName()
                        + ": "
                        + data.get("types"),
                data);
        if (mode.equals("block")) {
            throw new StepFailure(
                    "guard: the state for "
                            + def.getName()
                            + " contains personal data ("
                            + types
                            + "); pii: block keeps it from the backend",
                    null);
        }
        return mode.equals("mask")
                ? io.github.llm4j.privacy.MaskingLLMClient.mask(
                        text, DETECTOR, io.github.llm4j.privacy.MaskingLLMClient.PERSONAL, null)
                : text;
    }

    private static String text(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
            return String.valueOf(value);
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    // ---- the answers

    private void answered(
            IntentClassifyStmt stmt,
            IntentClassifierDef def,
            String stepId,
            String variable,
            ClassifyRequest request,
            ClassifyResult result,
            int attempts) {
        Map<String, Object> bound = new LinkedHashMap<>();
        Map<String, OptionalDouble> choose = new LinkedHashMap<>();
        boolean calibrated = true;
        for (IntentClassifierDef.Question q : def.getQuestions()) {
            Answer a = result.answer(q.name());
            calibrated &= a.calibrated();
            if (a instanceof ChoiceAnswer c) {
                bound.put(q.name(), c.label());
                if (c.confidence().isPresent())
                    bound.put(q.name() + "_confidence", c.confidence().getAsDouble());
                choose.put(q.name(), c.confidence());
            } else if (a instanceof YesNoAnswer y) {
                bound.put(q.name(), y.yes() ? "yes" : "no");
                bound.put(q.name() + "_probability", y.probability());
            } else if (a instanceof ScoreAnswer s) {
                bound.put(q.name(), s.nearest());
                bound.put(q.name() + "_position", s.position());
                if (s.confidence().isPresent())
                    bound.put(q.name() + "_confidence", s.confidence().getAsDouble());
            }
        }
        OptionalDouble lowest =
                choose.values().stream()
                        .filter(OptionalDouble::isPresent)
                        .mapToDouble(OptionalDouble::getAsDouble)
                        .min();
        if (lowest.isPresent()) bound.put("confidence", lowest.getAsDouble());
        ClassifyUsage usage = result.usage();
        bound.put("calibrated", calibrated);
        bound.put("backend", usage.backend());
        bound.put("model", usage.model());

        String outcome = "ok";
        List<String> escalated = new ArrayList<>();
        IntentClassifierDef.Confidence rule = def.getConfidence();
        if (rule != null) {
            List<String> under = new ArrayList<>();
            for (var e : choose.entrySet()) {
                // a choose question with no confidence at all cannot be trusted: it counts as below
                // the threshold
                if (e.getValue().isEmpty() || e.getValue().getAsDouble() < rule.threshold())
                    under.add(e.getKey());
            }
            if (!under.isEmpty()) {
                switch (rule.action()) {
                    case ESCALATE -> {
                        for (String name : under) bound.put(name, IntentClassifierDef.UNSURE);
                        escalated.addAll(under);
                        outcome = "unsure";
                    }
                    case ASK_PERSON -> {
                        for (String name : under) {
                            bound.put(
                                    name,
                                    askPerson(
                                            def,
                                            stepId,
                                            name,
                                            (String) bound.get(name),
                                            choose.get(name),
                                            rule.threshold(),
                                            request));
                        }
                        outcome = "asked";
                    }
                    case FAIL -> {
                        String q = under.get(0);
                        OptionalDouble c = choose.get(q);
                        fail(
                                stmt,
                                def,
                                stepId,
                                "the confidence of "
                                        + q
                                        + " ("
                                        + (c.isPresent() ? c.getAsDouble() : "none")
                                        + ") is below "
                                        + rule.threshold(),
                                null,
                                true,
                                attempts);
                        return;
                    }
                }
            }
        }

        executor.journal().put(stepId, new RunJournal.Entry("classify", bound));
        executor.setVariable(variable, bound);
        executor.decisions().classified(stmt, stepId, bound);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("classifier", def.getName());
        data.put("step", executor.currentStep());
        data.put("backend", usage.backend());
        data.put("model", usage.model());
        data.put("requested_model", def.getModel());
        data.put("calibrated", calibrated);
        data.put("samples", def.getSamples());
        data.put("questions", def.getQuestions().size());
        data.put("input_tokens", usage.inputTokens());
        data.put("output_tokens", usage.outputTokens());
        data.put("duration_ms", usage.latencyMillis());
        data.put("attempts", attempts);
        data.put("outcome", outcome);
        Map<String, Object> labels = new LinkedHashMap<>();
        for (IntentClassifierDef.Question q : def.getQuestions())
            labels.put(q.name(), bound.get(q.name()));
        data.put("labels", labels);
        if (lowest.isPresent()) data.put("confidence", lowest.getAsDouble());
        if (!escalated.isEmpty()) data.put("escalated", escalated);
        executor.trace(TraceEvent.CLASSIFY, null, summary(def, labels, lowest), data);
        executor.audit("classify", data);
    }

    private static String summary(
            IntentClassifierDef def, Map<String, Object> labels, OptionalDouble confidence) {
        StringBuilder sb = new StringBuilder(def.getName()).append(" →");
        labels.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        confidence.ifPresent(c -> sb.append(" (confidence ").append(c).append(')'));
        return sb.toString();
    }

    private void replayed(IntentClassifierDef def, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("classifier", def.getName());
        data.put("step", executor.currentStep());
        data.put("replayed", true);
        data.put("outcome", "replayed");
        executor.trace(
                TraceEvent.CLASSIFY, null, def.getName() + ": reused the recorded answer", data);
    }

    // ---- ask person

    private String askPerson(
            IntentClassifierDef def,
            String stepId,
            String question,
            String given,
            OptionalDouble confidence,
            double threshold,
            ClassifyRequest request) {
        HumanInterface human = executor.humanInterface();
        IntentClassifierDef.Question q = def.question(question);
        if (human == null) {
            throw new IllegalStateException(
                    "intentclassifier "
                            + def.getName()
                            + ": "
                            + question
                            + " has confidence "
                            + (confidence.isPresent() ? confidence.getAsDouble() : "none")
                            + ", below "
                            + threshold
                            + ", and the rule says ask person, but no person is configured to ask");
        }
        StringBuilder text = new StringBuilder();
        text.append(def.getName())
                .append(" is not sure enough about ")
                .append(question)
                .append(" (confidence ")
                .append(confidence.isPresent() ? confidence.getAsDouble() : "none")
                .append(", below ")
                .append(threshold)
                .append("). It leans to ")
                .append(given)
                .append(". Please choose ")
                .append(question)
                .append(" (")
                .append(String.join(" / ", q.labels()))
                .append(")\n");
        request.state()
                .forEach(
                        (k, v) ->
                                text.append(k.isEmpty() ? "state" : k)
                                        .append(" = ")
                                        .append(Decider.neutralise(v))
                                        .append('\n'));
        for (int round = 0; round < 2; round++) {
            String key = stepId + "#classify-ask:" + question + (round == 0 ? "" : "2");
            String asked =
                    round == 0
                            ? text.toString()
                            : text
                                    + "\nPlease answer with exactly one of: "
                                    + String.join(", ", q.labels())
                                    + ".";
            String answer =
                    executor.atStep(
                            key,
                            () -> {
                                String recorded = executor.recordedAnswer(asked);
                                if (recorded != null) return recorded;
                                String got =
                                        human.promptHuman(
                                                key,
                                                asked,
                                                new HumanInterface.Hints(
                                                        HumanInterface.Hints.Kind.DECIDE,
                                                        q.labels(),
                                                        null));
                                executor.recordAnswer(asked, got);
                                return got;
                            });
            String label = match(q.labels(), answer);
            if (label != null) return label;
        }
        throw new IllegalStateException(
                "intentclassifier "
                        + def.getName()
                        + ": the answer for "
                        + question
                        + " was not one of "
                        + q.labels());
    }

    /** An answer matched to a label: exactly (any case), or as the one label it is the start of. */
    static String match(List<String> labels, String answer) {
        if (answer == null) return null;
        String a = answer.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return null;
        for (String l : labels) if (l.equalsIgnoreCase(a)) return l;
        List<String> starts =
                labels.stream().filter(l -> l.toLowerCase(Locale.ROOT).startsWith(a)).toList();
        return starts.size() == 1 ? starts.get(0) : null;
    }

    // ---- failure

    /**
     * The step failed: run {@code on_failure} (recording the failure so a resume goes straight
     * there), or fail the run.
     */
    private void fail(
            IntentClassifyStmt stmt,
            IntentClassifierDef def,
            String stepId,
            String message,
            Throwable cause,
            boolean record,
            int attempts) {
        executor.traceFailure(
                "classify",
                def.getName(),
                message,
                cause,
                null,
                attempts,
                !stmt.getOnFailure().isEmpty());
        if (stmt.getOnFailure().isEmpty()) {
            throw new RuntimeException(
                    "intentclassify " + def.getName() + " failed: " + message, cause);
        }
        if (record) executor.journal().put(stepId, new RunJournal.Entry("failed", message));
        executor.runFailureHandler(stmt.getOnFailure(), message);
    }
}
