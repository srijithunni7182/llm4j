package io.github.llm4j.loom.review.rule;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a YAML or JSON document into {@link RuleDoc}s. It checks shape only (known keys, the right
 * kind of value, the closed vocabulary); that the facts a rule names exist is {@link
 * RuleValidator}'s job. Every problem is collected with the place it is in, so a rule author sees
 * them all at once.
 */
public final class RuleParser {

    private static final List<String> RULE_KEYS =
            List.of(
                    "id",
                    "lens",
                    "severity",
                    "about",
                    "when",
                    "requires",
                    "title",
                    "why",
                    "fixHint",
                    "fix",
                    "heuristic",
                    "since",
                    "references",
                    "explain",
                    "escalate",
                    "examples");

    /** Deepest nesting of conditions accepted, and the most nodes in one condition tree. */
    static final int MAX_DEPTH = 6;

    static final int MAX_NODES = 200;

    private final List<String> problems = new ArrayList<>();
    private int nodes;

    private RuleParser() {}

    /** The result of parsing: the rules that were well formed, and every problem found. */
    public record Parsed(List<RuleDoc> rules, List<String> problems) {}

    /**
     * A document is one rule (a map) or a list of rules. {@code where} names the file in messages.
     */
    public static Parsed parse(JsonNode root, String where) {
        RuleParser p = new RuleParser();
        List<RuleDoc> rules = new ArrayList<>();
        if (root == null || root.isNull() || root.isMissingNode()) {
            p.problems.add(where + ": the file is empty");
        } else if (root.isArray()) {
            for (int i = 0; i < root.size(); i++) p.rule(root.get(i), where + "[" + i + "]", rules);
        } else {
            p.rule(root, where, rules);
        }
        return new Parsed(rules, p.problems);
    }

    private void rule(JsonNode n, String where, List<RuleDoc> out) {
        if (!n.isObject()) {
            problems.add(where + ": a rule is a map of keys");
            return;
        }
        String id = text(n, "id");
        String at = id == null ? where : id;
        int before = problems.size();
        unknownKeys(n, RULE_KEYS, at);
        if (id == null) problems.add(where + ": a rule needs an id");
        Lens lens = null;
        String lensText = text(n, "lens");
        if (lensText == null) problems.add(at + ": a rule needs a lens");
        else {
            lens = Lens.parse(lensText).orElse(null);
            if (lens == null)
                problems.add(
                        at
                                + ": '"
                                + lensText
                                + "' is not a lens"
                                + Suggest.hint(
                                        lensText,
                                        java.util.Arrays.stream(Lens.values())
                                                .map(Lens::id)
                                                .toList()));
        }
        Severity severity = null;
        String sevText = text(n, "severity");
        if (sevText == null) problems.add(at + ": a rule needs a severity");
        else {
            severity = Severity.parse(sevText).orElse(null);
            if (severity == null)
                problems.add(
                        at + ": severity takes high, medium, low or info, not '" + sevText + "'");
        }
        String about = text(n, "about");
        if (about == null)
            problems.add(at + ": a rule needs 'about' (the kind of thing it looks at)");
        String title = text(n, "title");
        if (title == null) problems.add(at + ": a rule needs a title");
        String fixHint = text(n, "fixHint");
        if (fixHint == null)
            problems.add(at + ": a rule needs a fixHint: every finding says what to do");
        Condition when = null;
        if (!n.has("when")) problems.add(at + ": a rule needs a 'when' condition");
        else {
            nodes = 0;
            when = condition(n.get("when"), at + ".when", 1);
        }
        List<String> requires = strings(n.get("requires"), at + ".requires");
        List<String> references = strings(n.get("references"), at + ".references");
        FixTemplate fix = n.has("fix") ? fix(n.get("fix"), at + ".fix") : null;
        boolean heuristic = n.has("heuristic") && n.get("heuristic").asBoolean(false);
        if (n.has("heuristic") && !n.get("heuristic").isBoolean())
            problems.add(at + ".heuristic: true or false");
        List<RuleDoc.Escalation> escalations = escalations(n.get("escalate"), at + ".escalate");
        List<Example> fires = new ArrayList<>();
        List<Example> quiet = new ArrayList<>();
        if (n.has("examples")) {
            JsonNode ex = n.get("examples");
            if (!ex.isObject()) problems.add(at + ".examples: a map with 'fires' and 'quiet'");
            else {
                unknownKeys(ex, List.of("fires", "quiet"), at + ".examples");
                examples(ex.get("fires"), at + ".examples.fires", fires);
                examples(ex.get("quiet"), at + ".examples.quiet", quiet);
            }
        }
        if (problems.size() > before || id == null) return;
        out.add(
                new RuleDoc(
                        id,
                        lens,
                        severity,
                        about,
                        when,
                        requires,
                        title,
                        n.has("why") ? text(n, "why") : "",
                        fixHint,
                        fix,
                        heuristic,
                        text(n, "since"),
                        references,
                        text(n, "explain"),
                        fires,
                        quiet,
                        escalations,
                        null));
    }

    private List<RuleDoc.Escalation> escalations(JsonNode n, String where) {
        List<RuleDoc.Escalation> out = new ArrayList<>();
        if (n == null) return out;
        if (!n.isArray()) {
            problems.add(where + ": a list of {when, severity}");
            return out;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode e = n.get(i);
            String at = where + "[" + i + "]";
            if (!e.isObject() || !e.has("when") || !e.has("severity")) {
                problems.add(at + ": needs when and severity");
                continue;
            }
            unknownKeys(e, List.of("when", "severity"), at);
            Severity s = Severity.parse(e.get("severity").asText()).orElse(null);
            if (s == null) problems.add(at + ".severity: high, medium, low or info");
            nodes = 0;
            Condition c = condition(e.get("when"), at + ".when", 1);
            if (s != null && c != null) out.add(new RuleDoc.Escalation(c, s));
        }
        return out;
    }

    // ---- conditions

    private Condition condition(JsonNode n, String where, int depth) {
        if (++nodes > MAX_NODES) {
            problems.add(where + ": the condition has more than " + MAX_NODES + " parts");
            return null;
        }
        if (depth > MAX_DEPTH) {
            problems.add(where + ": conditions are nested more than " + MAX_DEPTH + " deep");
            return null;
        }
        if (!n.isObject()) {
            problems.add(
                    where + ": a condition is a map (all, any, not, or field with an operator)");
            return null;
        }
        if (n.has("all")) return combinator(n, "all", where, depth);
        if (n.has("any")) return combinator(n, "any", where, depth);
        if (n.has("not")) {
            only(n, "not", where);
            Condition inner = condition(n.get("not"), where + ".not", depth + 1);
            return inner == null ? null : new Condition.Not(inner);
        }
        return leaf(n, where, depth);
    }

    private Condition combinator(JsonNode n, String key, String where, int depth) {
        only(n, key, where);
        JsonNode list = n.get(key);
        if (!list.isArray() || list.isEmpty()) {
            problems.add(where + "." + key + ": a non-empty list of conditions");
            return null;
        }
        List<Condition> parts = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < list.size(); i++) {
            Condition c = condition(list.get(i), where + "." + key + "[" + i + "]", depth + 1);
            if (c == null) ok = false;
            else parts.add(c);
        }
        if (!ok) return null;
        return key.equals("all") ? new Condition.All(parts) : new Condition.Any(parts);
    }

    private void only(JsonNode n, String key, String where) {
        Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!f.equals(key))
                problems.add(where + ": '" + f + "' cannot sit beside '" + key + "'");
        }
    }

    private Condition leaf(JsonNode n, String where, int depth) {
        String field = text(n, "field");
        if (field == null) {
            problems.add(
                    where + ": a condition needs 'field' with one operator, or all, any or not");
            return null;
        }
        Op op = null;
        JsonNode value = null;
        Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (key.equals("field")) continue;
            Optional<Op> found = Op.ofKey(key);
            if (found.isEmpty()) {
                List<String> keys =
                        new ArrayList<>(java.util.Arrays.stream(Op.values()).map(Op::key).toList());
                keys.addAll(List.of("field", "all", "any", "not"));
                problems.add(
                        where + ": '" + key + "' is not an operator" + Suggest.hint(key, keys));
            } else if (op != null) {
                problems.add(
                        where
                                + ": one operator per condition, found '"
                                + op.key()
                                + "' and '"
                                + key
                                + "'");
            } else {
                op = found.get();
                value = n.get(key);
            }
        }
        if (op == null) {
            problems.add(
                    where
                            + ": '"
                            + field
                            + "' needs an operator (is, in, gt, contains, exists, count, some …)");
            return null;
        }
        return switch (op) {
            case EXISTS, MISSING -> {
                if (!value.isBoolean() || !value.asBoolean()) {
                    problems.add(where + "." + op.key() + ": write " + op.key() + ": true");
                    yield null;
                }
                yield new Condition.Leaf(field, op, new Operand.None(), null);
            }
            case COUNT -> {
                Operand.Count c = count(value, where + ".count");
                yield c == null ? null : new Condition.Leaf(field, op, c, null);
            }
            case SOME, EVERY, NONE -> {
                Condition nested = condition(value, where + "." + op.key(), depth + 1);
                yield nested == null
                        ? null
                        : new Condition.Leaf(field, op, new Operand.None(), nested);
            }
            case IN, NOT_IN -> {
                Operand o = listOperand(value, where + "." + op.key());
                yield o == null ? null : new Condition.Leaf(field, op, o, null);
            }
            default -> {
                Operand o = operand(value, where + "." + op.key());
                yield o == null ? null : new Condition.Leaf(field, op, o, null);
            }
        };
    }

    private Operand operand(JsonNode v, String where) {
        if (v.isObject()) return reference(v, where);
        Object lit = literal(v);
        if (lit == null) {
            problems.add(where + ": expected a string, a number or true or false");
            return null;
        }
        return new Operand.Literal(lit);
    }

    private Operand listOperand(JsonNode v, String where) {
        if (v.isObject()) return reference(v, where);
        if (!v.isArray() || v.isEmpty()) {
            problems.add(where + ": a non-empty list of values, or { setting: key }");
            return null;
        }
        List<Object> out = new ArrayList<>();
        for (JsonNode e : v) {
            Object lit = literal(e);
            if (lit == null) {
                problems.add(where + ": list entries are strings, numbers or true or false");
                return null;
            }
            out.add(lit);
        }
        return new Operand.Literals(out);
    }

    private Operand reference(JsonNode v, String where) {
        if (v.size() == 1 && v.has("setting") && v.get("setting").isTextual())
            return new Operand.Setting(v.get("setting").asText());
        if (v.size() == 1 && v.has("field") && v.get("field").isTextual())
            return new Operand.Field(v.get("field").asText());
        problems.add(where + ": a reference is { setting: key } or { field: path }");
        return null;
    }

    private Operand.Count count(JsonNode v, String where) {
        if (!v.isObject() || v.size() != 1) {
            problems.add(where + ": one comparison, such as { gt: 0 }");
            return null;
        }
        String key = v.fieldNames().next();
        Op cmp = Op.ofKey(key).orElse(null);
        if (cmp == null
                || !(cmp.comparison() || cmp == Op.IS || cmp == Op.IS_NOT)
                || !v.get(key).isIntegralNumber()) {
            problems.add(where + ": use gt, gte, lt, lte, is or isNot with a whole number");
            return null;
        }
        return new Operand.Count(cmp, v.get(key).asLong());
    }

    private static Object literal(JsonNode v) {
        if (v.isTextual()) return v.asText();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isBoolean()) return v.asBoolean();
        return null;
    }

    // ---- fix and examples

    private FixTemplate fix(JsonNode n, String where) {
        if (!n.isObject()) {
            problems.add(where + ": a map with safety, label and operations");
            return null;
        }
        List<String> allowed = new ArrayList<>(List.of("safety", "label"));
        allowed.addAll(FixTemplate.OPERATIONS);
        unknownKeys(n, allowed, where);
        FixSafety safety = null;
        String s = text(n, "safety");
        if (s == null || !(s.equals("safe") || s.equals("review")))
            problems.add(where + ".safety: safe or review");
        else safety = s.equals("safe") ? FixSafety.SAFE : FixSafety.REVIEW;
        String label = text(n, "label");
        if (label == null) problems.add(where + ".label: what the fix does, in a few words");
        List<FixTemplate.Operation> ops = new ArrayList<>();
        for (String op : FixTemplate.OPERATIONS) {
            if (!n.has(op)) continue;
            JsonNode o = n.get(op);
            if (!o.isObject()) {
                problems.add(where + "." + op + ": a map with an anchor and a text");
                continue;
            }
            String anchor =
                    o.has("anchor")
                            ? (o.get("anchor").isObject() && o.get("anchor").has("lineOf")
                                    ? "lineOf:" + o.get("anchor").get("lineOf").asText()
                                    : text(o, "anchor"))
                            : text(o, "path");
            if (anchor == null)
                problems.add(
                        where
                                + "."
                                + op
                                + ": needs an anchor"
                                + (op.equals("createFile") ? " or a path" : ""));
            ops.add(new FixTemplate.Operation(op, anchor, text(o, "text"), text(o, "find")));
        }
        if (ops.isEmpty())
            problems.add(
                    where
                            + ": a fix needs at least one operation ("
                            + String.join(", ", FixTemplate.OPERATIONS)
                            + ")");
        return safety == null || label == null || ops.isEmpty()
                ? null
                : new FixTemplate(safety, label, ops);
    }

    private void examples(JsonNode n, String where, List<Example> out) {
        if (n == null) return;
        if (!n.isArray()) {
            problems.add(where + ": a list of examples");
            return;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode e = n.get(i);
            String at = where + "[" + i + "]";
            if (e.isTextual()) {
                out.add(new Example(e.asText(), null, null));
            } else if (e.isObject()) {
                unknownKeys(e, List.of("file", "inline", "expect", "settings"), at);
                boolean file = e.has("file");
                boolean inline = e.has("inline");
                if (file == inline) problems.add(at + ": give either file or inline");
                else
                    out.add(
                            new Example(
                                    file ? text(e, "file") : null,
                                    inline ? text(e, "inline") : null,
                                    e.has("expect") ? e.get("expect").asInt() : null,
                                    exampleSettings(e.get("settings"), at)));
            } else {
                problems.add(at + ": a file name, or a map with file or inline");
            }
        }
    }

    private java.util.Map<String, String> exampleSettings(JsonNode n, String at) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (n == null) return out;
        if (!n.isObject()) {
            problems.add(at + ".settings: a map of setting names to values");
            return out;
        }
        n.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue().asText()));
        return out;
    }

    // ---- small helpers

    private List<String> strings(JsonNode n, String where) {
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        if (!n.isArray()) {
            problems.add(where + ": a list of strings");
            return out;
        }
        for (JsonNode e : n) {
            if (e.isTextual()) out.add(e.asText());
            else problems.add(where + ": a list of strings");
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private void unknownKeys(JsonNode n, List<String> allowed, String where) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        n.fieldNames().forEachRemaining(seen::add);
        for (String k : seen)
            if (!allowed.contains(k))
                problems.add(where + ": unknown key '" + k + "'" + Suggest.hint(k, allowed));
    }
}
