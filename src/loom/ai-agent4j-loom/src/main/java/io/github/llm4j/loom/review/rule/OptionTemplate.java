package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.model.FixSafety;
import java.util.List;

/**
 * One way to resolve a finding, written as data: a label, what choosing it changes, the questions
 * it asks the developer ({@code {ask.name}} in the text of an operation takes the answer) and the
 * edit operations that carry it out. Unlike a rule's single {@code fix}, an option is never applied
 * on its own: a person picks it, answers its questions and sees the change before it is written.
 *
 * @param id a short name, stable within the rule
 * @param label what it does, in a few words
 * @param explain what changes in the workflow if it is chosen
 * @param safety whether the change is mechanical or needs the developer's thought
 * @param asks the questions
 * @param operations the edits, from the same closed set a fix uses
 * @param showReferences true to show the developer where else the thing is used first
 */
public record OptionTemplate(
        String id,
        String label,
        String explain,
        FixSafety safety,
        List<Ask> asks,
        List<FixTemplate.Operation> operations,
        boolean showReferences) {

    /** A question: the name used in {@code {ask.name}}, the words shown, and the default answer. */
    public record Ask(String name, String prompt, String defaultValue) {}

    public OptionTemplate {
        asks = List.copyOf(asks);
        operations = List.copyOf(operations);
    }
}
