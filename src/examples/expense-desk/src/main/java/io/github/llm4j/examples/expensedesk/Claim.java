package io.github.llm4j.examples.expensedesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One expense claim as the desk shows it. Immutable: each change makes a new claim. Text that is
 * not known yet is empty, never null.
 *
 * @param id the claim's id, which is also the id of the workflow run behind it
 * @param fields what the model read from the text (merchant, amount, currency, category)
 * @param reason why policy refused it, or why the run failed
 * @param question what a person is being asked, or {@link Question#NONE}
 * @param steps what has happened to the claim, oldest first
 */
public record Claim(
        String id,
        String employee,
        String text,
        String created,
        Status status,
        Map<String, Object> fields,
        String reason,
        Question question,
        String payment,
        String note,
        List<Step> steps) {

    /** Where a claim is. */
    public enum Status {
        READING,
        WAITING,
        PAID,
        DECLINED,
        REJECTED,
        FAILED
    }

    /** A question a run is suspended on. */
    public record Question(String stepId, String prompt) {
        /** No question is open. */
        public static final Question NONE = new Question("", "");

        public boolean open() {
            return !stepId.isEmpty();
        }
    }

    /** One thing that happened: who did it (model, code, person or the desk) and what. */
    public record Step(String at, String actor, String text) {}

    public Claim {
        fields = Map.copyOf(fields);
        steps = List.copyOf(steps);
    }

    /** A claim that was just handed in. */
    public static Claim submitted(String id, String employee, String text, String created) {
        return new Claim(id, employee, text, created, Status.READING, Map.of(), "", Question.NONE, "", "", List.of());
    }

    public Claim withStatus(Status next) {
        return new Claim(id, employee, text, created, next, fields, reason, question, payment, note, steps);
    }

    public Claim withFields(Map<String, Object> read) {
        return new Claim(id, employee, text, created, status, read, reason, question, payment, note, steps);
    }

    public Claim withReason(String why) {
        return new Claim(id, employee, text, created, status, fields, why, question, payment, note, steps);
    }

    public Claim withQuestion(Question asked) {
        return new Claim(id, employee, text, created, status, fields, reason, asked, payment, note, steps);
    }

    public Claim withPayment(String reference) {
        return new Claim(id, employee, text, created, status, fields, reason, question, reference, note, steps);
    }

    public Claim withNote(String written) {
        return new Claim(id, employee, text, created, status, fields, reason, question, payment, written, steps);
    }

    public Claim withStep(Step step) {
        List<Step> longer = new ArrayList<>(steps);
        longer.add(step);
        return new Claim(id, employee, text, created, status, fields, reason, question, payment, note, longer);
    }
}
