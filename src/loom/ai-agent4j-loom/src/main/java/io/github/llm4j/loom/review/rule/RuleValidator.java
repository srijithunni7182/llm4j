package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.fact.EntityDef;
import io.github.llm4j.loom.review.fact.FactModel;
import io.github.llm4j.loom.review.fact.FieldDef;
import io.github.llm4j.loom.review.fact.FieldType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks a parsed rule against the fact model, so a rule that cannot work is refused when its pack
 * loads and never fails later on someone's script: every fact it names exists, every operator fits
 * the type of the fact, every enum word is one the fact can have, every placeholder is a real path,
 * and the rule carries the examples that prove it.
 */
public final class RuleValidator {

    private static final Pattern REQUIRES = Pattern.compile("setting\\..+|graph");
    private static final Pattern OWASP = Pattern.compile("LLM(0[1-9]|10)");

    private final FactModel model;
    private final List<String> problems = new ArrayList<>();

    private RuleValidator(FactModel model) {
        this.model = model;
    }

    /** Every problem with {@code rule}, or an empty list when it is sound. */
    public static List<String> validate(RuleDoc rule, FactModel model) {
        RuleValidator v = new RuleValidator(model);
        v.rule(rule);
        return v.problems;
    }

    private void rule(RuleDoc r) {
        String at = r.id();
        EntityDef about = model.entity(r.about()).orElse(null);
        if (about == null) {
            problems.add(
                    at
                            + ": about '"
                            + r.about()
                            + "' is not an entity"
                            + model.hintEntity(r.about())
                            + "; the entities are "
                            + model.entities().stream().map(EntityDef::kind).toList());
            return;
        }
        condition(r.when(), r.about(), at + ".when");
        for (String q : r.requires())
            if (!REQUIRES.matcher(q).matches())
                problems.add(at + ".requires: '" + q + "' is not setting.<key> or graph");
        for (String o : r.references())
            if (!OWASP.matcher(o).matches())
                problems.add(at + ".references: '" + o + "' is not an OWASP LLM id such as LLM06");
        placeholders(r.title(), r.about(), at + ".title");
        placeholders(r.why(), r.about(), at + ".why");
        placeholders(r.fixHint(), r.about(), at + ".fixHint");
        if (r.fix() != null) {
            placeholders(r.fix().label(), r.about(), at + ".fix.label");
            for (FixTemplate.Operation op : r.fix().operations()) {
                placeholders(op.text(), r.about(), at + ".fix." + op.kind());
                String anchor = op.anchor();
                if (anchor != null && !op.kind().equals("createFile")) {
                    String base = anchor.startsWith("lineOf:") ? "lineOf" : anchor;
                    if (!FixTemplate.ANCHORS.contains(base))
                        problems.add(
                                at
                                        + ".fix."
                                        + op.kind()
                                        + ": '"
                                        + anchor
                                        + "' is not an anchor; the anchors are "
                                        + FixTemplate.ANCHORS);
                    if (anchor.startsWith("lineOf:") && about.field(anchor.substring(7)).isEmpty())
                        problems.add(
                                at
                                        + ".fix."
                                        + op.kind()
                                        + ": lineOf names '"
                                        + anchor.substring(7)
                                        + "', which is not a fact of "
                                        + r.about());
                }
            }
        }
        if (r.fires().isEmpty())
            problems.add(at + ".examples.fires: a rule needs at least one script it fires on");
        if (r.quiet().isEmpty())
            problems.add(
                    at + ".examples.quiet: a rule needs at least one script it stays quiet on");
    }

    private void placeholders(String text, String kind, String where) {
        for (String path : Template.placeholders(text)) {
            FactModel.PathResult p = model.resolve(kind, path);
            if (!p.valid()) problems.add(where + ": {" + path + "}: " + p.problem());
        }
    }

    private void condition(Condition c, String kind, String where) {
        if (c instanceof Condition.All a)
            for (int i = 0; i < a.all().size(); i++)
                condition(a.all().get(i), kind, where + ".all[" + i + "]");
        else if (c instanceof Condition.Any a)
            for (int i = 0; i < a.any().size(); i++)
                condition(a.any().get(i), kind, where + ".any[" + i + "]");
        else if (c instanceof Condition.Not n) condition(n.not(), kind, where + ".not");
        else leaf((Condition.Leaf) c, kind, where);
    }

    private void leaf(Condition.Leaf l, String kind, String where) {
        FactModel.PathResult p = model.resolve(kind, l.path());
        if (!p.valid()) {
            problems.add(where + ": " + p.problem());
            return;
        }
        FieldDef f = p.field();
        Op op = l.op();
        String at = where + "(" + l.path() + " " + op.key() + ")";
        switch (op) {
            case IS, IS_NOT -> {
                if (!f.type().scalar())
                    problems.add(
                            at
                                    + ": "
                                    + op.key()
                                    + " compares one value, but "
                                    + l.path()
                                    + " is a "
                                    + f.type().name().toLowerCase());
                else literals(l.operand(), f, kind, at, false);
            }
            case IN, NOT_IN -> {
                if (!f.type().scalar())
                    problems.add(
                            at
                                    + ": "
                                    + op.key()
                                    + " needs a single value, but "
                                    + l.path()
                                    + " is a "
                                    + f.type().name().toLowerCase());
                else literals(l.operand(), f, kind, at, true);
            }
            case GT, GTE, LT, LTE -> {
                if (f.type() != FieldType.NUMBER)
                    problems.add(
                            at
                                    + ": "
                                    + op.key()
                                    + " needs a number, but "
                                    + l.path()
                                    + " is a "
                                    + f.type().name().toLowerCase());
                else if (l.operand() instanceof Operand.Literal lit
                        && !(lit.value() instanceof Number))
                    problems.add(at + ": compare with a number");
                else reference(l.operand(), kind, at);
            }
            case CONTAINS -> {
                if (f.type() != FieldType.STRING && f.type() != FieldType.LIST)
                    problems.add(
                            at
                                    + ": contains needs text or a list, but "
                                    + l.path()
                                    + " is a "
                                    + f.type().name().toLowerCase());
                else reference(l.operand(), kind, at);
            }
            case STARTS_WITH -> {
                if (f.type() != FieldType.STRING) problems.add(at + ": startsWith needs text");
                else reference(l.operand(), kind, at);
            }
            case MATCHES -> {
                if (f.type() != FieldType.STRING) problems.add(at + ": matches needs text");
                else if (l.operand() instanceof Operand.Literal lit) {
                    try {
                        Pattern.compile(String.valueOf(lit.value()));
                    } catch (java.util.regex.PatternSyntaxException e) {
                        problems.add(
                                at + ": not a valid regular expression: " + e.getDescription());
                    }
                } else reference(l.operand(), kind, at);
            }
            case EXISTS, MISSING -> {}
            case COUNT -> {
                if (!f.type().list())
                    problems.add(
                            at
                                    + ": count needs a list, but "
                                    + l.path()
                                    + " is a "
                                    + f.type().name().toLowerCase());
            }
            case SOME, EVERY, NONE -> {
                if (f.type() != FieldType.ENTITY_LIST)
                    problems.add(
                            at
                                    + ": "
                                    + op.key()
                                    + " needs a list of entities, but "
                                    + l.path()
                                    + " is a "
                                    + f.type().name().toLowerCase());
                else condition(l.nested(), f.entity(), at);
            }
        }
    }

    /** Checks literal operands against the fact: right kind, and a word the enum can have. */
    private void literals(Operand o, FieldDef f, String kind, String at, boolean list) {
        if (o instanceof Operand.Literal lit) {
            if (list) problems.add(at + ": in and notIn take a list");
            else literal(lit.value(), f, at);
        } else if (o instanceof Operand.Literals lits) {
            if (!list) problems.add(at + ": is and isNot take one value, not a list (use in)");
            else for (Object v : lits.values()) literal(v, f, at);
        } else reference(o, kind, at);
    }

    private void literal(Object v, FieldDef f, String at) {
        switch (f.type()) {
            case BOOL -> {
                if (!(v instanceof Boolean))
                    problems.add(at + ": " + f.name() + " is true or false");
            }
            case NUMBER -> {
                if (!(v instanceof Number)) problems.add(at + ": " + f.name() + " is a number");
            }
            case ENUM -> {
                if (!f.values().contains(String.valueOf(v)))
                    problems.add(
                            at
                                    + ": '"
                                    + v
                                    + "' is not a value of "
                                    + f.name()
                                    + "; it can be "
                                    + f.values());
            }
            default -> {
                if (!(v instanceof String)) problems.add(at + ": " + f.name() + " is text");
            }
        }
    }

    private void reference(Operand o, String kind, String at) {
        if (o instanceof Operand.Field fr) {
            FactModel.PathResult p = model.resolve(kind, fr.path());
            if (!p.valid()) problems.add(at + ": " + p.problem());
        } else if (o instanceof Operand.Setting s && s.key().isBlank()) {
            problems.add(at + ": a setting reference needs a key");
        }
    }
}
