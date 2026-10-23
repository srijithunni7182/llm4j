package io.github.llm4j.loom.review.model;

import java.util.List;

/**
 * One way to resolve a finding, to be offered to the developer: a label, what choosing it means,
 * the questions it needs answered (such as how many retries), and the edits that carry it out. The
 * text of the edits may hold {@code {ask.name}} where an answer goes.
 *
 * @param id a short name for the option, stable within its rule ({@code retry}, {@code timeout})
 * @param label what it does, in a few words
 * @param explain what changes in the workflow if it is chosen, in plain words
 * @param safety {@link FixSafety#SAFE} for a mechanical change, {@link FixSafety#REVIEW} for one
 *     whose effect the developer should think about
 * @param params the questions to ask before applying it
 * @param edits the changes, with the answers not yet filled in
 * @param showReferences true when the developer should first be shown where else the thing is
 *     mentioned in the project (before deleting it)
 */
public record FixOption(
        String id,
        String label,
        String explain,
        FixSafety safety,
        List<Param> params,
        List<TextEdit> edits,
        boolean showReferences) {

    /**
     * A question an option asks: its name, the question, and the answer used when none is given.
     */
    public record Param(String name, String prompt, String defaultValue) {}

    public FixOption {
        params = List.copyOf(params);
        edits = List.copyOf(edits);
    }

    /** The edits with the answers put in; a missing answer takes the question's default. */
    public List<TextEdit> editsWith(java.util.Map<String, String> answers) {
        List<TextEdit> out = new java.util.ArrayList<>();
        for (TextEdit e : edits) {
            String text = e.newText();
            for (Param p : params) {
                String v = answers.getOrDefault(p.name(), p.defaultValue());
                text =
                        text.replace(
                                "{ask." + p.name() + "}",
                                v == null ? "" : v.replace('\n', ' ').replace('\r', ' ').strip());
            }
            out.add(
                    new TextEdit(
                            e.file(),
                            e.startLine(),
                            e.startColumn(),
                            e.endLine(),
                            e.endColumn(),
                            text));
        }
        return out;
    }
}
