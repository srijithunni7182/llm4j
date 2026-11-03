package io.github.llm4j.loom.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A policy file, parsed (docs/specs/26-policy-boundary, R1). The file is plain markdown written for
 * the person who owns compliance: each rule is a level-two heading that is its id, followed by one
 * or more sentences saying what breaking it looks like. Text before the first rule heading is a
 * description and is never sent to a model.
 *
 * <p>This is the one parser; the runtime, {@code weave check} and the editor all use it, so they
 * show the same rules.
 *
 * @param description the text before the first rule heading
 * @param rules the rules in file order
 * @param problems what is wrong with the file, in words; empty when it is usable
 */
public record PolicyFile(String description, List<Rule> rules, List<String> problems) {

    /** The longest a rule may be unless a setting says otherwise. */
    public static final int DEFAULT_MAX_RULE_CHARS = 1000;

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");

    /**
     * One rule.
     *
     * @param id the lowercase id with underscores
     * @param text what breaking the rule looks like
     */
    public record Rule(String id, String text) {}

    /** True when the file has no problems and at least one rule. */
    public boolean usable() {
        return problems.isEmpty();
    }

    /** Parses with the default rule length limit. */
    public static PolicyFile parse(String markdown) {
        return parse(markdown, DEFAULT_MAX_RULE_CHARS);
    }

    /**
     * Parses {@code markdown}.
     *
     * @param markdown the file's text
     * @param maxRuleChars the longest a rule's text may be, so a rule stays a rule and not a manual
     */
    public static PolicyFile parse(String markdown, int maxRuleChars) {
        List<String> problems = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder description = new StringBuilder();
        String currentId = null;
        StringBuilder current = new StringBuilder();
        boolean any = false;
        for (String line : markdown.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                if (any) finish(currentId, current, maxRuleChars, rules, seen, problems);
                any = true;
                currentId = line.substring(3).strip();
                current = new StringBuilder();
                if (!ID.matcher(currentId).matches())
                    problems.add(
                            "\""
                                    + currentId
                                    + "\" is not a rule id; use lowercase words joined by"
                                    + " underscores, as in no_refund_promise");
            } else if (any) {
                current.append(line).append('\n');
            } else {
                description.append(line).append('\n');
            }
        }
        if (any) finish(currentId, current, maxRuleChars, rules, seen, problems);
        if (rules.isEmpty() && problems.isEmpty())
            problems.add(
                    "the file has no rule; write each rule as a level-two heading (## rule_id)"
                            + " followed by what breaking it looks like");
        return new PolicyFile(description.toString().strip(), List.copyOf(rules), List.copyOf(problems));
    }

    private static void finish(
            String id,
            StringBuilder body,
            int max,
            List<Rule> rules,
            Set<String> seen,
            List<String> problems) {
        String text = body.toString().strip();
        if (!ID.matcher(id).matches()) return; // already reported
        if (!seen.add(id)) {
            problems.add("rule " + id + " is written twice; each id must be unique in a file");
            return;
        }
        if (text.isEmpty()) {
            problems.add("rule " + id + " has no text; say what breaking it looks like");
            return;
        }
        if (text.length() > max) {
            problems.add(
                    "rule "
                            + id
                            + " is "
                            + text.length()
                            + " characters; keep it under "
                            + max
                            + " (policy.max-rule-chars) so it stays a rule and not a manual");
            return;
        }
        rules.add(new Rule(id, text));
    }
}
