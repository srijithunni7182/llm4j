package io.github.llm4j.loom.review.settings;

import io.github.llm4j.loom.review.model.Finding;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's decision to set one finding aside, written in {@code review.properties} as {@code
 * LR05.suppress = Router "free text on purpose"}. The place is a name (an agent, tool, workflow or
 * classifier that the finding's title mentions), {@code line N}, or a graph node id such as {@code
 * n3}. The reason is required: a suppression without one does not hide anything and is itself a
 * finding.
 *
 * @param rule the rule it applies to
 * @param place what narrows it to one place
 * @param reason why, or empty when the project left it out
 * @param line the line of {@code review.properties} it was written on
 */
public record Suppression(String rule, Place place, String reason, int line) {

    private static final Pattern VALUE =
            Pattern.compile("(line\\s+\\d+|n\\d+|[A-Za-z_][A-Za-z0-9_.-]*)(?:\\s+\"(.*)\")?");

    /** Where a suppression applies. */
    public record Place(Kind kind, String name, int line) {
        public enum Kind {
            NAME,
            NODE,
            LINE
        }

        @Override
        public String toString() {
            return switch (kind) {
                case LINE -> "line " + line;
                default -> name;
            };
        }
    }

    /** Parses the value after {@code =}. @throws IllegalArgumentException when it is not valid */
    static Suppression parse(String rule, String value, int line) {
        Matcher m = VALUE.matcher(value.strip());
        if (!m.matches())
            throw new IllegalArgumentException(
                    rule
                            + ".suppress takes a place and a reason, such as: "
                            + rule
                            + ".suppress = Router \"why this is fine\" (the place is a name, 'line 12' or a node id like n3)");
        String where = m.group(1);
        Place place;
        if (where.toLowerCase(Locale.ROOT).startsWith("line"))
            place = new Place(Place.Kind.LINE, null, Integer.parseInt(where.substring(4).strip()));
        else if (where.matches("n\\d+")) place = new Place(Place.Kind.NODE, where, 0);
        else place = new Place(Place.Kind.NAME, where, 0);
        String reason = m.group(2) == null ? "" : m.group(2).strip();
        return new Suppression(rule, place, reason, line);
    }

    public boolean hasReason() {
        return !reason.isBlank();
    }

    /** True when this suppression's rule and place are the finding's. */
    public boolean matches(Finding f) {
        if (!f.rule().equals(rule)) return false;
        return switch (place.kind()) {
            case LINE -> f.where().line() == place.line();
            case NODE -> place.name().equals(f.where().nodeId());
            case NAME -> Pattern.compile(
                            "(?<![A-Za-z0-9_])" + Pattern.quote(place.name()) + "(?![A-Za-z0-9_])")
                    .matcher(f.title())
                    .find();
        };
    }
}
