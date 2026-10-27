package io.github.llm4j.loom.review.model;

/**
 * A finding the project set aside, with the scope and reason it gave, who accepted it and when, and
 * the day it is due to be looked at again. It is listed in the report so that nothing is hidden.
 *
 * @param finding what was set aside
 * @param place the scope of the entry, such as {@code Router} or {@code finding 3fa9…}
 * @param reason why
 * @param until the day the entry stops applying, or empty
 * @param by who accepted it, or empty
 * @param at the day it was accepted, or empty
 */
public record Suppressed(
        Finding finding, String place, String reason, String until, String by, String at) {

    public Suppressed(Finding finding, String place, String reason) {
        this(finding, place, reason, "", "", "");
    }
}
