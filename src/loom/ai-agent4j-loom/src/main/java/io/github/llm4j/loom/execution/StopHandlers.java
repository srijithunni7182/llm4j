package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.StopReport;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.InterventionDef;
import io.github.llm4j.loom.ast.OutcomeStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.DefaultVariableContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a script says to do with a stopped step: the step's own {@code on_stopped { }}, then the script's {@code intervention { }} defaults. Neither
 * says anything about a stop that is not covered, and the interpreter's own table then applies.
 *
 * <p>Inside a handler, {@code _stop} describes the stop: {@code reason} (one of {@link InterventionDef#REASONS}), {@code note} (the words the person gave),
 * {@code kind} (cancel or wrap_up), {@code phase}, {@code partial}, {@code changed} (yes or no) and {@code origin}.
 */
final class StopHandlers {

    /** The fields a handler can read from {@code _stop}. */
    static final List<String> FIELDS = InterventionDef.STOP_FIELDS;

    private StopHandlers() { }

    /** The reason as a script sees it: one of the known reasons, or {@code user} for anything a person wrote. */
    static String category(StopReport stop) {
        String r = stop.reason() == null ? "" : stop.reason().strip().toLowerCase(Locale.ROOT);
        return InterventionDef.REASONS.contains(r) && !r.equals("user") ? r : "user";
    }

    /** What the script declares for a stop: the outcome and, for {@link StopOutcome#REWIND}, the rewind to run. */
    record Declared(StopOutcome outcome, RewindStmt rewind) { }

    /** The outcome the script declares for this stop, if any. */
    static Optional<StopOutcome> declared(List<Statement> onStopped, InterventionDef defaults, StopReport stop) {
        return resolve(onStopped, defaults, stop).map(Declared::outcome);
    }

    static Optional<Declared> resolve(List<Statement> onStopped, InterventionDef defaults, StopReport stop) {
        String category = category(stop);
        if (onStopped != null && !onStopped.isEmpty()) {
            DefaultVariableContext scope = new DefaultVariableContext();
            java.util.Map<String, Object> fields = fieldsOf(stop);
            scope.setVariable("_stop", fields);
            Optional<Declared> fromHandler = first(onStopped, scope);
            if (fromHandler.isPresent()) return fromHandler;
        }
        if (defaults != null && defaults.getByReason().containsKey(category)) {
            return Optional.of(new Declared(StopOutcome.valueOf(defaults.getByReason().get(category).name()), null));
        }
        return Optional.empty();
    }

    /** The step's own rewind, aimed at the checkpoint a person chose: its limit, its side-effects policy and what it carries stay. */
    static RewindStmt sendBackTo(RewindStmt template, String checkpoint) {
        RewindStmt r = new RewindStmt(checkpoint, null, template.getAtMost());
        r.getCarrying().putAll(template.getCarrying());
        if (template.isEffectsStated()) r.setEffects(template.getEffects());
        r.getIfStillFails().addAll(template.getIfStillFails());
        r.getIfBlocked().addAll(template.getIfBlocked());
        r.setLine(template.getLine());
        return r;
    }

    /** What {@code _stop} holds for a stop. */
    static java.util.Map<String, Object> fieldsOf(StopReport stop) {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("reason", category(stop));
        fields.put("note", stop.reason() == null ? "" : stop.reason());
        fields.put("kind", stop.kind().name().toLowerCase(Locale.ROOT));
        fields.put("phase", stop.phase() == null ? "" : stop.phase().name().toLowerCase(Locale.ROOT));
        fields.put("partial", stop.partialAnswer() == null ? "" : stop.partialAnswer());
        fields.put("changed", stop.mayHaveChangedThings() ? "yes" : "no");
        fields.put("origin", stop.origin() == null ? "" : stop.origin());
        return fields;
    }

    private static Optional<Declared> first(List<Statement> statements, DefaultVariableContext scope) {
        for (Statement s : statements) {
            if (s instanceof OutcomeStmt o) return Optional.of(new Declared(StopOutcome.valueOf(o.getKind().name()), null));
            if (s instanceof RewindStmt r) return Optional.of(new Declared(StopOutcome.REWIND, r));
            if (s instanceof AltStmt a) {
                List<Statement> branch = ConditionEvaluator.evaluate(a.getCondition(), scope) ? a.getIfBranch() : a.getElseBranch();
                Optional<Declared> found = first(branch, scope);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }
}
