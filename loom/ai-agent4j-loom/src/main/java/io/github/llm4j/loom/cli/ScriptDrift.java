package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A fork can run under another script, but the journal it starts from holds the results of the first script's steps. If the new script
 * would have asked for something else before the fork point, the journal would hand it answers to questions it never asked. This finds
 * the first place the two scripts differ in the part that is already done.
 */
public final class ScriptDrift {

    private ScriptDrift() { }

    /** A description of the first difference before the fork point, or null when the finished part is the same. */
    public static String between(Path original, Path candidate, RunJournal journal, String workflow, String at) {
        LoomScript a;
        LoomScript b;
        try {
            a = new LoomLoader().load(original.toString());
            b = new LoomLoader().load(candidate.toString());
        } catch (Exception e) {
            return "Could not read the scripts to compare them: " + e.getMessage();
        }
        List<String> before = signature(a, workflow, at, journal);
        List<String> after = signature(b, workflow, at, journal);
        for (int i = 0; i < Math.max(before.size(), after.size()); i++) {
            String x = i < before.size() ? before.get(i) : "(nothing)";
            String y = i < after.size() ? after.get(i) : "(nothing)";
            if (!x.equals(y)) return "The scripts differ before the fork point, at statement " + (i + 1) + " of the workflow: was " + x + ", now " + y + ".";
        }
        return null;
    }

    /** What each top-level statement before the fork point asks of which agent: kind, agent, text, tools. */
    private static List<String> signature(LoomScript script, String workflow, String at, RunJournal journal) {
        List<String> out = new ArrayList<>();
        var def = script.getWorkflows().stream().filter(w -> w.getName().equals(workflow)).findFirst();
        if (def.isEmpty()) return List.of("(no workflow " + workflow + ")");
        int limit = limit(def.get().getStatements(), at);
        for (int i = 0; i < Math.min(limit, def.get().getStatements().size()); i++) {
            Statement s = def.get().getStatements().get(i);
            StringBuilder b = new StringBuilder(s.getClass().getSimpleName());
            StatementWalker.walk(List.of(s), n -> b.append('|').append(describe(script, n)));
            out.add(b.toString());
        }
        return out;
    }

    /** How many top-level statements come before the fork point (all of them when the point can't be placed that simply). */
    private static int limit(List<Statement> statements, String at) {
        if (at == null) return statements.size();
        if (at.equals("start")) return 0;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof io.github.llm4j.loom.ast.CheckpointStmt c && c.getName().equals(at)) return i + 1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/s(\\d+)").matcher(at);
        return m.find() ? Integer.parseInt(m.group(1)) : statements.size();
    }

    private static String describe(LoomScript script, Statement s) {
        if (s instanceof io.github.llm4j.loom.ast.DelegateStmt d) {
            var agent = script.getAgents().stream().filter(x -> x.getName().equals(d.getTargetAgent())).findFirst();
            return d.getTargetAgent() + ":" + d.getPayload() + ":" + (agent.isPresent() ? agent.get().getTools() + "/" + agent.get().getModel() : "?");
        }
        if (s instanceof io.github.llm4j.loom.ast.RunStmt r) {
            StringBuilder b = new StringBuilder("task " + r.getTaskName() + "(");
            r.getArgs().forEach(a -> b.append(a.name()).append('=').append(a.kind()).append(':').append(a.text()).append(','));
            return b.append(')').toString();
        }
        if (s instanceof io.github.llm4j.loom.ast.HumanPromptStmt h) return h.getMessage();
        if (s instanceof io.github.llm4j.loom.ast.NoteStmt n) return n.getMessage();
        return "";
    }
}
