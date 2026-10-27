package io.github.llm4j.loom.review.settings;

import io.github.llm4j.loom.review.model.Finding;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's decision to set findings aside, written in {@code review.properties} as {@code
 * RULE.accept = <scope> "reason" [until DATE] [by "NAME"] [at DATE]} (the older {@code
 * RULE.suppress = <place> "reason"} is read the same way). The reason is required: an entry without
 * one hides nothing and is itself a finding.
 *
 * <p>The scope says how much the entry covers:
 *
 * <ul>
 *   <li>{@code finding 3fa9c2d1e0b47a55}: one finding, by its fingerprint
 *   <li>{@code on Router}: the rule where the finding names Router (an agent, tool, workflow…)
 *   <li>{@code line 12}, {@code node n3}: a place
 *   <li>{@code script main.loom}: the rule everywhere in that script
 *   <li>{@code project}: the rule everywhere
 * </ul>
 *
 * @param rule the rule it applies to
 * @param place the scope
 * @param reason why, or empty when the project left it out
 * @param line the line of {@code review.properties} it was written on
 * @param until the day the entry stops hiding the finding, or null
 * @param by who accepted it, or empty
 * @param at the day it was accepted, or null
 */
public record Suppression(
        String rule,
        Place place,
        String reason,
        int line,
        LocalDate until,
        String by,
        LocalDate at) {

    private static final Pattern SCOPE =
            Pattern.compile(
                    "(?:finding\\s+([0-9a-f]{16})|on\\s+([A-Za-z_][A-Za-z0-9_.-]*)|line\\s+(\\d+)|node\\s+(n\\d+)|script\\s+(\\S+)|(project)|(n\\d+)|([A-Za-z_][A-Za-z0-9_.-]*))");
    private static final Pattern OPTION =
            Pattern.compile("\\s+(until|at)\\s+(\\d{4}-\\d{2}-\\d{2})|\\s+by\\s+\"([^\"]*)\"");

    /** Where a suppression applies. */
    public record Place(Kind kind, String name, int line) {
        public enum Kind {
            NAME,
            NODE,
            LINE,
            FINGERPRINT,
            SCRIPT,
            PROJECT
        }

        @Override
        public String toString() {
            return switch (kind) {
                case LINE -> "line " + line;
                case FINGERPRINT -> "finding " + name;
                case SCRIPT -> "script " + name;
                case PROJECT -> "project";
                default -> name;
            };
        }
    }

    /** Parses the value after {@code =}. @throws IllegalArgumentException when it is not valid */
    static Suppression parse(String key, String rule, String value, int line) {
        String v = value.strip();
        String usage =
                rule
                        + "."
                        + key
                        + " takes a scope and a reason, such as: "
                        + rule
                        + "."
                        + key
                        + " = on Router \"why this is fine\" (a scope is finding <fingerprint>, on <name>, line N, node nN, script <file> or project)";
        Matcher sm = SCOPE.matcher(v);
        if (!sm.lookingAt()) throw new IllegalArgumentException(usage);
        Place place;
        if (sm.group(1) != null) place = new Place(Place.Kind.FINGERPRINT, sm.group(1), 0);
        else if (sm.group(2) != null) place = new Place(Place.Kind.NAME, sm.group(2), 0);
        else if (sm.group(3) != null)
            place = new Place(Place.Kind.LINE, null, Integer.parseInt(sm.group(3)));
        else if (sm.group(4) != null) place = new Place(Place.Kind.NODE, sm.group(4), 0);
        else if (sm.group(5) != null) place = new Place(Place.Kind.SCRIPT, sm.group(5), 0);
        else if (sm.group(6) != null) place = new Place(Place.Kind.PROJECT, null, 0);
        else if (sm.group(7) != null) place = new Place(Place.Kind.NODE, sm.group(7), 0);
        else place = new Place(Place.Kind.NAME, sm.group(8), 0);
        String rest = v.substring(sm.end());
        String reason = "";
        Matcher rm = Pattern.compile("^\\s+\"(.*?)\"(?=\\s|$)").matcher(rest);
        if (rm.find()) {
            reason = rm.group(1).strip();
            rest = rest.substring(rm.end());
        }
        LocalDate until = null;
        LocalDate at = null;
        String by = "";
        Matcher om = OPTION.matcher(rest);
        int end = 0;
        while (om.find()) {
            if (om.start() != end) throw new IllegalArgumentException(usage);
            end = om.end();
            try {
                if ("until".equals(om.group(1))) until = LocalDate.parse(om.group(2));
                else if ("at".equals(om.group(1))) at = LocalDate.parse(om.group(2));
                else by = om.group(3);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        rule
                                + "."
                                + key
                                + ": '"
                                + om.group(2)
                                + "' is not a date (write 2026-12-31)");
            }
        }
        if (!rest.substring(end).isBlank()) throw new IllegalArgumentException(usage);
        return new Suppression(rule, place, reason, line, until, by, at);
    }

    public boolean hasReason() {
        return !reason.isBlank();
    }

    /** True when {@code today} is after the day this entry was to be looked at again. */
    public boolean expired(LocalDate today) {
        return until != null && today != null && today.isAfter(until);
    }

    /** True when this entry's rule and scope are the finding's. */
    public boolean matches(Finding f) {
        if (!f.rule().equals(rule)) return false;
        return switch (place.kind()) {
            case LINE -> f.where().line() == place.line();
            case NODE -> place.name().equals(f.where().nodeId());
            case FINGERPRINT -> place.name().equals(f.fingerprint());
            case SCRIPT -> place.name().equals(f.where().file());
            case PROJECT -> true;
            case NAME -> {
                // the finding names the thing in its title, or is anchored to it (an agent, or the
                // workflow a step is in)
                Pattern word =
                        Pattern.compile(
                                "(?<![A-Za-z0-9_])"
                                        + Pattern.quote(place.name())
                                        + "(?![A-Za-z0-9_])");
                yield word.matcher(f.title()).find() || word.matcher(f.anchor()).find();
            }
        };
    }
}
