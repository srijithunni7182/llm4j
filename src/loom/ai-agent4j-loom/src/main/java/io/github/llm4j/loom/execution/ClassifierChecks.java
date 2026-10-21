package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SetStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load-time checks for {@code intentclassifier} and {@code intentclassify} (docs/specs/07-classifier, DESIGN 5.5): the declaration is sound, a statement
 * names a declared classifier and a bound state, and, the main gain over asking an agent for one word, an {@code alt} that compares an answer to a label
 * that can never be produced is an error that says what can be. Nothing here calls a model.
 */
final class ClassifierChecks {

    /** How an answer's fields are read in a condition. */
    enum FieldKind { LABEL, NUMBER, FLAG, TEXT }

    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMPARISON = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");

    private ClassifierChecks() {}

    static void run(ScriptValidator.Checker c) {
        Set<String> seen = new HashSet<>();
        for (IntentClassifierDef def : c.script().getIntentClassifiers()) {
            if (!seen.add(def.getName())) c.error(def.getLine(), "intentclassifier " + def.getName(), "declared twice");
            declaration(c, def);
        }
        for (WorkflowDef w : c.script().getWorkflows()) workflow(c, w);
    }

    // ---- the declaration

    private static void declaration(ScriptValidator.Checker c, IntentClassifierDef def) {
        String who = "intentclassifier " + def.getName();
        if (def.getQuestions().isEmpty()) c.error(def.getLine(), who, "needs at least one question: choose kind: a | b, ask urgent: \"…\", or rate tone: low | high");
        if (def.getPii() != null && !AgentGuard.PII_VALUES.contains(def.getPii())) {
            c.error(def.getPiiLine(), who, "pii: use mask, block or warn; got " + def.getPii());
        }
        Set<String> names = new HashSet<>();
        Map<String, String> produced = new LinkedHashMap<>();
        for (String reserved : IntentClassifierDef.RESERVED_NAMES) produced.put(reserved, "the answer's own field");
        boolean hasChoose = false;
        for (IntentClassifierDef.Question q : def.getQuestions()) {
            int line = q.line();
            if (!PLAIN_NAME.matcher(q.name()).matches()) {
                c.error(line, who, q.name() + " must be a plain name (letters, digits and _): the answer is read as " + "x." + q.name());
                continue;
            }
            if (IntentClassifierDef.RESERVED_NAMES.contains(q.name())) {
                c.error(line, who, q.name() + " is a field of the answer (x." + q.name() + "), so a question cannot be called that");
                continue;
            }
            if (!names.add(q.name())) {
                c.error(line, who, q.name() + " is declared twice");
                continue;
            }
            for (String field : fields(q).keySet()) {
                String owner = produced.putIfAbsent(field, q.name());
                if (owner != null && !owner.equals(q.name())) {
                    c.error(line, who, "the answer field " + field + " is made by " + q.name() + " and clashes with " + owner + "; rename one of the questions");
                }
            }
            switch (q.kind()) {
                case CHOOSE -> {
                    hasChoose = true;
                    labels(c, who, q, 2, Integer.MAX_VALUE, "at least two labels");
                    for (String label : q.labels()) {
                        if (label.equalsIgnoreCase(IntentClassifierDef.UNSURE)) {
                            c.error(line, who, IntentClassifierDef.UNSURE + " is reserved: it is produced only by a confidence … else escalate rule, so " + q.name()
                                    + " cannot list it as a label");
                        }
                    }
                }
                case RATE -> labels(c, who, q, 2, 10, "2 to 10 points on its scale");
                default -> { }
            }
        }
        IntentClassifierDef.Confidence rule = def.getConfidence();
        if (rule != null && !hasChoose && !def.getQuestions().isEmpty()) {
            c.error(rule.line(), who, "the confidence rule measures the choose questions and " + def.getName() + " has none; add a choose question, or remove the rule");
        }
        if (def.getModel() != null && !def.getQuestions().isEmpty()) {
            if (isJev(c, def.getModel())) {
                if (def.getSamples() > 1) {
                    c.warn(def.getSamplesLine(), who, "samples: " + def.getSamples() + " has no effect on Jev, which gives the same answer every time; remove it");
                }
            } else if (rule != null && def.getSamples() == 1) {
                c.warn(rule.line(), who, "this classifier is uncalibrated: " + def.getModel() + " is a chat model, so its confidence is its own report; set samples: 3 or more "
                        + "so the confidence is the share of votes");
            }
        }
    }

    private static void labels(ScriptValidator.Checker c, String who, IntentClassifierDef.Question q, int min, int max, String needs) {
        int n = q.labels().size();
        if (n < min || n > max) c.error(q.line(), who, q.name() + " needs " + needs + "; it has " + n);
        Set<String> seen = new HashSet<>();
        for (String label : q.labels()) {
            if (!seen.add(label)) c.error(q.line(), who, q.name() + ": " + label + " is listed twice");
        }
    }

    /** True when the model resolves to Jev: the built-in {@code jev/…} or {@code jev-…}, or a provider declared with {@code use: jev}. */
    static boolean isJev(ScriptValidator.Checker c, String model) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.startsWith("jev/") || m.startsWith("jev-")) return true;
        int slash = model.indexOf('/');
        if (slash <= 0) return false;
        String alias = model.substring(0, slash);
        return c.script().getProviders().stream().anyMatch(p -> p.getName().equals(alias) && "jev".equals(p.getKind()));
    }

    // ---- what an answer has

    /** The fields of the record a question adds to the answer, and how each is read. */
    static Map<String, FieldKind> fields(IntentClassifierDef.Question q) {
        Map<String, FieldKind> out = new LinkedHashMap<>();
        out.put(q.name(), FieldKind.LABEL);
        switch (q.kind()) {
            case CHOOSE -> out.put(q.name() + "_confidence", FieldKind.NUMBER);
            case ASK -> out.put(q.name() + "_probability", FieldKind.NUMBER);
            case RATE -> {
                out.put(q.name() + "_position", FieldKind.NUMBER);
                out.put(q.name() + "_confidence", FieldKind.NUMBER);
            }
        }
        return out;
    }

    /** Every field of an answer of this classifier: its questions' fields, then {@code confidence}, {@code calibrated}, {@code backend}, {@code model}. */
    static Map<String, FieldKind> allFields(IntentClassifierDef def) {
        Map<String, FieldKind> out = new LinkedHashMap<>();
        for (IntentClassifierDef.Question q : def.getQuestions()) out.putAll(fields(q));
        out.put("confidence", FieldKind.NUMBER);
        out.put("calibrated", FieldKind.FLAG);
        out.put("backend", FieldKind.TEXT);
        out.put("model", FieldKind.TEXT);
        return out;
    }

    /** What a label-valued field of a question can be. */
    static List<String> possible(IntentClassifierDef def, IntentClassifierDef.Question q) {
        return switch (q.kind()) {
            case ASK -> List.of("yes", "no");
            default -> def.possibleLabels(q);
        };
    }

    // ---- the workflow

    private static void workflow(ScriptValidator.Checker c, WorkflowDef w) {
        List<Statement> all = new ArrayList<>();
        StatementWalker.walk(w.getStatements(), all::add);
        Set<String> bound = boundNames(w, all);
        Map<String, List<IntentClassifierDef>> binds = new LinkedHashMap<>();
        for (Statement s : all) {
            if (!(s instanceof IntentClassifyStmt st)) continue;
            IntentClassifierDef def = c.script().findIntentClassifier(st.getClassifier());
            if (def == null) {
                unknown(c, st);
                continue;
            }
            binds.computeIfAbsent(st.getVariableName(), k -> new ArrayList<>()).add(def);
            for (var in : st.getInputs()) {
                if (in.kind() != RunStmt.Kind.REFERENCE) continue;
                String head = in.text().contains(".") ? in.text().substring(0, in.text().indexOf('.')) : in.text();
                if (!bound.contains(head) && !head.startsWith("_")) {
                    c.error(st.getLine(), "intentclassify " + st.getClassifier(), (in.name().isEmpty() ? "" : in.name() + ": ") + head + " is not defined in workflow " + w.getName()
                            + "; defined here: " + String.join(", ", new LinkedHashSet<>(bound)));
                }
            }
        }
        for (Statement s : all) {
            String condition = s instanceof AltStmt a ? a.getCondition() : s instanceof LoopStmt l ? l.getCondition() : null;
            if (condition != null) condition(c, s, condition, binds);
        }
    }

    private static void unknown(ScriptValidator.Checker c, IntentClassifyStmt st) {
        List<String> declared = c.script().getIntentClassifiers().stream().map(IntentClassifierDef::getName).toList();
        if (declared.isEmpty()) {
            c.error(st.getLine(), "intentclassify " + st.getClassifier(), "there is no intentclassifier named " + st.getClassifier() + ": no intentclassifier is declared");
            return;
        }
        List<String> near = PromptCatalog.nearest(st.getClassifier(), declared);
        c.error(st.getLine(), "intentclassify " + st.getClassifier(), "there is no intentclassifier named " + st.getClassifier() + (near.isEmpty() ? "" : "; did you mean "
                + String.join(" or ", near) + "?") + " Declared: " + String.join(", ", declared) + ".");
    }

    /** Every name a workflow can read: its parameters, and whatever any of its steps stores. Order is not checked: only a name nothing ever defines is wrong. */
    private static Set<String> boundNames(WorkflowDef w, List<Statement> all) {
        Set<String> names = new LinkedHashSet<>(w.getParameters());
        for (Statement s : all) {
            String name = null;
            if (s instanceof DelegateStmt d) name = d.getVariableName();
            else if (s instanceof HumanPromptStmt h) name = h.getVariableName();
            else if (s instanceof BroadcastStmt b) name = b.getVariableName();
            else if (s instanceof CallStmt c) name = c.getResultVariable();
            else if (s instanceof DecideStmt d) name = d.getVariable();
            else if (s instanceof SetStmt set) name = set.getName();
            else if (s instanceof RunStmt r) name = r.getVariableName();
            else if (s instanceof IntentClassifyStmt i) name = i.getVariableName();
            else if (s instanceof ForEachStmt f) name = f.getItemName();
            if (name != null && !name.isBlank() && !name.startsWith("{")) names.add(name);
        }
        names.add("result");
        return names;
    }

    // ---- conditions on an answer

    private static void condition(ScriptValidator.Checker c, Statement at, String condition, Map<String, List<IntentClassifierDef>> binds) {
        Matcher m = COMPARISON.matcher(condition);
        if (!m.matches()) return;
        String variable = m.group(1);
        List<IntentClassifierDef> defs = binds.get(variable);
        if (defs == null) return;
        String field = m.group(2);
        String op = m.group(3);
        String literal = m.group(4);
        boolean hasField = defs.stream().anyMatch(d -> allFields(d).containsKey(field));
        if (!hasField) {
            Set<String> have = new LinkedHashSet<>();
            defs.forEach(d -> have.addAll(allFields(d).keySet()));
            List<String> near = PromptCatalog.nearest(field, new ArrayList<>(have));
            c.error(at.getLine(), "alt", variable + " has no field " + field + (near.isEmpty() ? "" : "; did you mean " + String.join(" or ", near) + "?") + " It has: " + String.join(", ", have) + ".");
            return;
        }
        if (!op.equals("==") && !op.equals("!=")) return;
        String value = literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"") ? literal.substring(1, literal.length() - 1) : literal;
        Set<String> can = new LinkedHashSet<>();
        boolean labelField = false;
        for (IntentClassifierDef d : defs) {
            IntentClassifierDef.Question q = d.question(field);
            if (q == null) {
                continue;
            }
            labelField = true;
            can.addAll(possible(d, q));
        }
        if (!labelField || can.contains(value)) return;
        List<String> near = PromptCatalog.nearest(value, new ArrayList<>(can));
        c.error(at.getLine(), "alt", variable + "." + field + " can be " + String.join(", ", can) + "; \"" + value + "\" is never produced"
                + (near.isEmpty() ? "" : " (did you mean " + String.join(" or ", near) + "?)"));
    }
}
