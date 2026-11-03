package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.CheckStmt;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.policy.PolicyCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * The static checks of {@code policy} and {@code evaluator} (docs/specs/26-policy-boundary, R1,
 * R2). Each message names the line and says what to write.
 */
final class PolicyChecks {

    /** Samples needed before an evaluator has a confidence of its own. */
    static final int SAMPLES_FOR_CONFIDENCE = 3;

    private PolicyChecks() {}

    static void run(ScriptValidator.Checker c) {
        Set<String> evaluators = new HashSet<>();
        for (EvaluatorDef def : c.script().getEvaluators()) {
            if (!evaluators.add(def.getName()))
                c.error(def.getLine(), "evaluator " + def.getName(), "declared twice");
            evaluator(c, def);
        }
        checks(c);
        PolicyCatalog catalog = PolicyCatalog.beside(c.context().baseDir);
        Set<String> policies = new HashSet<>();
        for (PolicyDef def : c.script().getPolicies()) {
            String who = "policy " + def.getName();
            if (!policies.add(def.getName())) c.error(def.getLine(), who, "declared twice");
            String problem = catalog.problemWith(def);
            if (problem != null) c.error(def.getLine(), who, problem);
            if (def.getCheckedBy() != null && c.script().findEvaluator(def.getCheckedBy()) == null)
                c.error(
                        def.getLine(),
                        who,
                        "checked by "
                                + def.getCheckedBy()
                                + ", which is not an evaluator; declare it: evaluator "
                                + def.getCheckedBy()
                                + " { model: \"…\" }");
        }
    }

    private static void checks(ScriptValidator.Checker c) {
        for (WorkflowDef w : c.script().getWorkflows()) {
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(w.getStatements(), all::add);
            for (Statement st : all) {
                if (st instanceof CheckStmt check) check(c, check);
            }
        }
    }

    private static void check(ScriptValidator.Checker c, CheckStmt st) {
        String who = "check " + st.getSubject();
        if (c.script().findEvaluator(st.getEvaluator()) == null)
            c.error(
                    st.getLine(),
                    who,
                    "using "
                            + st.getEvaluator()
                            + ", which is not an evaluator; declare it: evaluator "
                            + st.getEvaluator()
                            + " { model: \"…\" }");
        for (String policy : st.getPolicies()) {
            if (c.script().findPolicy(policy) == null)
                c.error(
                        st.getLine(),
                        who,
                        "against "
                                + policy
                                + ", which is not a policy; declare it: policy "
                                + policy
                                + ": \"file-name\"");
        }
        for (CheckStmt.Action action : List.of(st.getOnViolation(), st.getOnUnavailable())) {
            if (action instanceof CheckStmt.Rewrite rewrite
                    && c.script().getAgents().stream()
                            .noneMatch(a -> a.getName().equals(rewrite.agent())))
                c.error(
                        st.getLine(),
                        who,
                        "rewrite with " + rewrite.agent() + ", which is not an agent");
            if (action instanceof CheckStmt.Replace replace && replace.text().contains("{"))
                c.error(
                        st.getLine(),
                        who,
                        "replace with takes a fixed text; {…} is not filled in, because it could carry"
                                + " unchecked model-written text into the answer");
            boolean changesText =
                    action instanceof CheckStmt.Rewrite
                            || action instanceof CheckStmt.Replace
                            || action instanceof CheckStmt.AskPerson;
            if (changesText && st.getSubject().contains("."))
                c.error(
                        st.getLine(),
                        who,
                        "this action can replace the text, so check a whole variable, not the field "
                                + st.getSubject()
                                + "; copy the field into a variable first: set text = "
                                + st.getSubject());
        }
    }

    private static void evaluator(ScriptValidator.Checker c, EvaluatorDef def) {
        String who = "evaluator " + def.getName();
        if (def.getPii() != null && !AgentGuard.PII_VALUES.contains(def.getPii()))
            c.error(def.getLine(), who, "pii: use mask, block or warn; got " + def.getPii());
        if (def.isUnsureBelowWritten() && def.getSamples() < SAMPLES_FOR_CONFIDENCE)
            c.warn(
                    def.getLine(),
                    who,
                    "unsure below needs samples: "
                            + SAMPLES_FOR_CONFIDENCE
                            + " or more, or a calibrated backend; one chat answer has no confidence"
                            + " of its own, so the threshold never applies");
    }
}
