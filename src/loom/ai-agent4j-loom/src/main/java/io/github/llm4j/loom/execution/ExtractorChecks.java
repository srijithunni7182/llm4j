package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The static checks of {@code extractor} and {@code extract} (docs/specs/24-stateless-nodes, R6).
 * Each message names the line and says what to write.
 */
final class ExtractorChecks {

    private static final Pattern COMPARISON =
            Pattern.compile(
                    "^\\s*([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");

    private ExtractorChecks() {}

    static void run(ScriptValidator.Checker c) {
        Set<String> seen = new HashSet<>();
        for (ExtractorDef def : c.script().getExtractors()) {
            if (!seen.add(def.getName()))
                c.error(def.getLine(), "extractor " + def.getName(), "declared twice");
            declaration(c, def);
        }
        for (WorkflowDef w : c.script().getWorkflows()) workflow(c, w);
    }

    private static void declaration(ScriptValidator.Checker c, ExtractorDef def) {
        String who = "extractor " + def.getName();
        if (def.getInstructions() != null && def.getInstructions().contains("{")) {
            c.error(
                    def.getLine(),
                    who,
                    "an extractor's instructions are fixed; pass the value with"
                            + " `extract "
                            + def.getName()
                            + " from (name = …)` instead of writing {…} in the instructions");
        }
        if (def.getPii() != null && !AgentGuard.PII_VALUES.contains(def.getPii()))
            c.error(def.getLine(), who, "pii: use mask, block or warn; got " + def.getPii());
    }

    private static void workflow(ScriptValidator.Checker c, WorkflowDef w) {
        List<Statement> all = new ArrayList<>();
        StatementWalker.walk(w.getStatements(), all::add);
        Map<String, ExtractorDef> binds = new LinkedHashMap<>();
        for (Statement s : all) {
            if (!(s instanceof ExtractStmt st)) continue;
            ExtractorDef def = c.script().findExtractor(st.getExtractor());
            if (def == null) {
                unknown(c, st);
                continue;
            }
            binds.put(st.getVariableName(), def);
            for (RunStmt.Arg in : st.getInputs()) {
                if (in.kind() != RunStmt.Kind.REFERENCE) continue;
                String head =
                        in.text().contains(".")
                                ? in.text().substring(0, in.text().indexOf('.'))
                                : in.text();
                Set<String> defined = definedNames(w, all);
                if (!defined.contains(head) && !head.startsWith("_"))
                    c.error(
                            st.getLine(),
                            "extract " + st.getExtractor(),
                            (in.name().isEmpty() ? "" : in.name() + ": ")
                                    + head
                                    + " is not defined in workflow "
                                    + w.getName()
                                    + "; defined here: "
                                    + String.join(", ", defined));
            }
        }
        for (Statement s : all) {
            String condition =
                    s instanceof AltStmt a
                            ? a.getCondition()
                            : s instanceof LoopStmt l ? l.getCondition() : null;
            if (condition != null) condition(c, s, condition, binds);
        }
    }

    private static Set<String> definedNames(WorkflowDef w, List<Statement> all) {
        Set<String> names = new java.util.LinkedHashSet<>(w.getParameters());
        for (Statement s : all) {
            String name = WorkflowLint.assignedName(s);
            if (name != null) names.add(name);
            if (s instanceof ExtractStmt x) names.add(x.getVariableName());
            if (s instanceof RunStmt r && r.getVariableName() != null) names.add(r.getVariableName());
            if (s instanceof io.github.llm4j.loom.ast.ForEachStmt f) names.add(f.getItemName());
        }
        names.add("result");
        return names;
    }

    private static void unknown(ScriptValidator.Checker c, ExtractStmt st) {
        List<String> declared =
                c.script().getExtractors().stream().map(ExtractorDef::getName).toList();
        String who = "extract " + st.getExtractor();
        if (declared.isEmpty()) {
            c.error(
                    st.getLine(),
                    who,
                    "there is no extractor named "
                            + st.getExtractor()
                            + ": no extractor is declared");
            return;
        }
        List<String> near = PromptCatalog.nearest(st.getExtractor(), declared);
        c.error(
                st.getLine(),
                who,
                "there is no extractor named "
                        + st.getExtractor()
                        + (near.isEmpty() ? "" : "; did you mean " + String.join(" or ", near) + "?")
                        + " Declared: "
                        + String.join(", ", declared)
                        + ".");
    }

    private static void condition(
            ScriptValidator.Checker c, Statement at, String condition, Map<String, ExtractorDef> binds) {
        Matcher m = COMPARISON.matcher(condition);
        if (!m.matches()) return;
        ExtractorDef def = binds.get(m.group(1));
        if (def == null) return;
        String variable = m.group(1);
        String field = m.group(2);
        Set<String> have = def.getFields().getFields().keySet();
        if (!have.contains(field)) {
            List<String> near = PromptCatalog.nearest(field, new ArrayList<>(have));
            c.error(
                    at.getLine(),
                    "alt",
                    variable
                            + " has no field "
                            + field
                            + (near.isEmpty() ? "" : "; did you mean " + String.join(" or ", near) + "?")
                            + " It has: "
                            + String.join(", ", have)
                            + ".");
            return;
        }
        String literal = m.group(4);
        if ("missing".equals(literal) && !def.isOptional(field)) {
            c.error(
                    at.getLine(),
                    "alt",
                    variable
                            + "."
                            + field
                            + " is required, so it can never be missing; list it in optional: [ … ] of extractor "
                            + def.getName()
                            + " or remove the test");
        }
    }
}
