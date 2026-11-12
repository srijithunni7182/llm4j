package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.SteerStmt;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audit rule of started workflows (phase 33, D15, LA28): a script that can steer a started
 * workflow is an input channel, as a steerable agent is for a person (LA18). Cancelling is not an
 * input channel and gets no rule. The finding names the steerable agents the script can reach; it
 * is medium when one of them also reaches private data and can send or act.
 */
final class ChildAudit {

    private ChildAudit() {}

    static void findings(
            LoomScript script, List<SecurityAudit.AgentProfile> profiles, List<Finding> findings) {
        Map<String, SecurityAudit.AgentProfile> byName = new LinkedHashMap<>();
        profiles.forEach(p -> byName.put(p.agent(), p));
        for (WorkflowDef w : script.getWorkflows()) {
            Map<String, WorkflowDef> startedAs = new LinkedHashMap<>();
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof StartStmt st)
                            startedAs.put(st.getTarget().name(), find(script, st.getWorkflow()));
                    });
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof SteerStmt steer) {
                            report(w, steer.getLine(), "steer " + steer.getTarget().describe(),
                                    List.of(steer.getAgent()), byName, findings);
                        } else if (s instanceof SuperviseStmt supervise && steers(supervise)) {
                            WorkflowDef child = startedAs.get(supervise.getTarget().name());
                            report(w, supervise.getLine(), "supervise " + supervise.getTarget().describe(),
                                    steerableAgentsOf(script, child), byName, findings);
                        }
                    });
        }
    }

    /** LA29: each judged assertion, what its judge can see and what it can cause. */
    static void judges(LoomScript script, List<Finding> findings) {
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (!(s instanceof SuperviseStmt supervise)) return;
                        for (SuperviseStmt.Judged j : supervise.getJudged()) {
                            List<String> causes = new ArrayList<>();
                            for (SuperviseDef.Action a : j.actions())
                                causes.add(
                                        switch (a.kind()) {
                                            case STEER -> "steer";
                                            case WRAP_UP -> "wrap_up";
                                            case CANCEL -> "cancel use partial";
                                        });
                            findings.add(
                                    new Finding(
                                            "LA29",
                                            j.seeingContent() ? Severity.LOW : Severity.INFO,
                                            List.of(Owasp.LLM01, Owasp.LLM06),
                                            w.getName() + " / supervise " + supervise.getTarget().describe(),
                                            j.line(),
                                            j.seeingContent()
                                                    ? "A judge reads what a child produced"
                                                    : "A model judges a started workflow",
                                            "The judge "
                                                    + j.evaluator()
                                                    + " is asked, at most "
                                                    + j.max()
                                                    + " times, whether \""
                                                    + j.text()
                                                    + "\". It sees "
                                                    + (j.seeingContent()
                                                            ? "content: what the child produced, which may carry instructions written by someone else"
                                                            : "facts only: what was called, how often, in what order, and for how long")
                                                    + ", answers pass or fail, and a fail can only cause: "
                                                    + String.join(", ", causes)
                                                    + " (text fixed in the script). It is a model's opinion; it is not under the earned-autonomy ladder.",
                                            "Prefer a counted rule or an assertion in code where a count can say it; keep seeing content for a judge you trust with that text; give the judge a small budget."));
                        }
                    });
    }

    private static boolean steers(SuperviseStmt s) {
        List<List<SuperviseDef.Action>> all = new ArrayList<>();
        s.getCounted().forEach(r -> all.add(r.actions()));
        s.getCode().forEach(r -> all.add(r.actions()));
        s.getJudged().forEach(r -> all.add(r.actions()));
        return all.stream().flatMap(List::stream).anyMatch(a -> a.kind() == SuperviseDef.Do.STEER);
    }

    private static void report(
            WorkflowDef workflow,
            int line,
            String what,
            List<String> agents,
            Map<String, SecurityAudit.AgentProfile> profiles,
            List<Finding> findings) {
        List<String> exposed = new ArrayList<>();
        for (String a : agents) {
            SecurityAudit.AgentProfile p = profiles.get(a);
            if (p != null && (p.trifecta() || (!p.privateData().isEmpty() && !p.outward().isEmpty())))
                exposed.add(a);
        }
        boolean medium = !exposed.isEmpty();
        findings.add(
                new Finding(
                        "LA28",
                        medium ? Severity.MEDIUM : Severity.INFO,
                        List.of(Owasp.LLM01),
                        workflow.getName() + " / " + what,
                        line,
                        medium
                                ? "A script steers a started workflow whose agent can reach private data and act"
                                : "A script can steer a started workflow",
                        what
                                + " puts words, written in the script, in front of "
                                + (agents.isEmpty() ? "no running agent" : String.join(", ", agents))
                                + " while the child works: an input channel, like a steerable agent (LA18)."
                                + (medium
                                        ? " " + String.join(", ", exposed)
                                                + " also reaches private data and can send or act."
                                        : ""),
                        "Keep the text literal (the language refuses a placeholder in a steer), mark steerable only the agents that should be steered, keep a guard on them, and prefer a counted rule or a cancel to steering an agent that can act."));
    }

    private static List<String> steerableAgentsOf(LoomScript script, WorkflowDef child) {
        if (child == null) return List.of();
        Set<String> reached = new LinkedHashSet<>();
        collect(script, child, reached, new LinkedHashSet<>());
        List<String> out = new ArrayList<>();
        for (AgentDef a : script.getAgents())
            if (a.isSteerable() && reached.contains(a.getName())) out.add(a.getName());
        return out;
    }

    private static void collect(LoomScript script, WorkflowDef w, Set<String> agents, Set<String> seen) {
        if (!seen.add(w.getName())) return;
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    if (s instanceof DelegateStmt d && !d.hasDynamicTarget()) agents.add(d.getTargetAgent());
                    else if (s instanceof DelegateStmt d) agents.addAll(d.getAllowedTargets());
                    else if (s instanceof HandoffStmt h) agents.add(h.getTargetAgent());
                    else if (s instanceof BroadcastStmt b) agents.addAll(b.getTargetAgents());
                    else if (s instanceof CallStmt c) {
                        WorkflowDef next = find(script, c.getWorkflowName());
                        if (next != null) collect(script, next, agents, seen);
                    } else if (s instanceof StartStmt st) {
                        WorkflowDef next = find(script, st.getWorkflow());
                        if (next != null) collect(script, next, agents, seen);
                    }
                });
    }

    private static WorkflowDef find(LoomScript script, String name) {
        for (WorkflowDef w : script.getWorkflows()) if (w.getName().equals(name)) return w;
        return null;
    }
}
