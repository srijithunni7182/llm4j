package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.UseStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.security.Capabilities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The static checks of {@code use} (docs/specs/27-boundary-approvals, R1, R2): the tool is one the
 * script can call, a tool that changes things and may act twice is not retried, and the words
 * {@code approve} and {@code on_declined} are only used together.
 */
final class UseChecks {

    private UseChecks() {}

    static void run(ScriptValidator.Checker c) {
        Map<String, ToolDef> declared = new HashMap<>();
        c.script().getTools().forEach(t -> declared.put(t.getName(), t));
        for (WorkflowDef w : c.script().getWorkflows()) {
            List<io.github.llm4j.loom.ast.Statement> all = new ArrayList<>();
            StatementWalker.walk(w.getStatements(), all::add);
            for (io.github.llm4j.loom.ast.Statement s : all) {
                if (s instanceof UseStmt use) check(c, use, declared);
            }
        }
    }

    private static void check(ScriptValidator.Checker c, UseStmt use, Map<String, ToolDef> declared) {
        String who = "use " + use.getTool();
        if (!c.context().registeredTools.contains(use.getTool()) && !declared.containsKey(use.getTool())) {
            c.error(
                    use.getLine(),
                    who,
                    use.getTool()
                            + " is not a tool: declare it (tool "
                            + use.getTool()
                            + " { use: … }) or name a built-in or a tool your host registers");
            return;
        }
        if (use.getRetryCount() > 0 && changesAndMayRepeat(use.getTool(), declared)) {
            c.error(
                    use.getLine(),
                    who,
                    use.getTool()
                            + " changes things and is not marked safe to repeat, so a retry after an unknown outcome could do it twice; remove retry, or declare the tool idempotent");
        }
    }

    private static boolean changesAndMayRepeat(String tool, Map<String, ToolDef> declared) {
        if (Capabilities.resolve(tool, declared, Map.of()).level() != TaskEffect.CHANGES) return false;
        ToolDef def = declared.get(tool);
        if (def == null) return false; // a host's tool: its own policy decides
        ToolDef.OptionValue idem = def.getOptions().get("idempotency");
        return idem == null || !"true".equals(idem.value());
    }
}
