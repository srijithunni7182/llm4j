package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.AgentChannelDef;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ExposeDef;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RemoteDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audit rules of the A2A constructs (docs/specs/32-a2a-integration, R27): what an exposed
 * workflow takes in (LA24), what a call to a remote agent hands out (LA25), a channel that answers
 * with no person (LA26) and an exposed workflow nobody capped (LA27). The audit does not track data,
 * so everything an exposed workflow reaches counts as reading what outsiders wrote.
 */
final class A2AAudit {

    private A2AAudit() {}

    /** The agents each exposed workflow reaches, by agent name, with the expose blocks that reach it. */
    static Map<String, List<String>> exposedAgents(LoomScript script) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ExposeDef expose : script.getExposes())
            for (String agent : reach(script, expose.get("workflow")))
                out.computeIfAbsent(agent, k -> new ArrayList<>()).add(expose.getName());
        return out;
    }

    /** The agents a workflow reaches, through its own steps and the workflows it calls. */
    static Set<String> reach(LoomScript script, String workflow) {
        Set<String> agents = new LinkedHashSet<>();
        collect(script, workflow, agents, new LinkedHashSet<>());
        return agents;
    }

    private static void collect(LoomScript script, String workflow, Set<String> agents, Set<String> seen) {
        if (workflow == null || !seen.add(workflow)) return;
        Set<String> known = new LinkedHashSet<>();
        script.getAgents().forEach(a -> known.add(a.getName()));
        for (WorkflowDef w : script.getWorkflows()) {
            if (!w.getName().equals(workflow)) continue;
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        List<String> targets = new ArrayList<>();
                        if (s instanceof DelegateStmt d) {
                            if (d.hasDynamicTarget()) targets.addAll(d.getAllowedTargets());
                            else targets.add(d.getTargetAgent());
                        } else if (s instanceof HandoffStmt h) {
                            targets.add(h.getTargetAgent());
                        } else if (s instanceof BroadcastStmt b) {
                            targets.addAll(b.getTargetAgents());
                        } else if (s instanceof CallStmt c) {
                            collect(script, c.getWorkflowName(), agents, seen);
                        }
                        for (String t : targets) if (known.contains(t)) agents.add(t);
                    });
        }
    }

    static void findings(
            LoomScript script, List<SecurityAudit.AgentProfile> profiles, List<Finding> findings) {
        Map<String, SecurityAudit.AgentProfile> byName = new LinkedHashMap<>();
        profiles.forEach(p -> byName.put(p.agent(), p));
        exposes(script, findings);
        remoteCalls(script, byName, findings);
        channels(script, findings);
    }

    private static void exposes(LoomScript script, List<Finding> findings) {
        for (ExposeDef expose : script.getExposes()) {
            Set<String> reached = reach(script, expose.get("workflow"));
            findings.add(
                    new Finding(
                            "LA24",
                            Severity.LOW,
                            List.of(Owasp.LLM01),
                            expose.getName(),
                            expose.getLine(),
                            "A workflow takes input from outside",
                            "expose "
                                    + expose.getName()
                                    + " lets other agents call workflow "
                                    + expose.get("workflow")
                                    + ". What a caller sends reaches "
                                    + (reached.isEmpty() ? "no agent" : String.join(", ", reached))
                                    + ", so the audit counts "
                                    + (reached.size() == 1 ? "it" : "them")
                                    + " as reading untrusted content, whatever the caller is. The audit does not follow data, so it assumes every agent the workflow reaches sees it.",
                            "Keep the agents it reaches away from private data and from tools that send or act (the lethal trifecta), validate what the workflow does with the input in code, and name the callers you allow at the endpoint your host serves."));
            if (script.getBudget() == null && expose.getBudget() == null) {
                findings.add(
                        new Finding(
                                "LA27",
                                Severity.HIGH,
                                List.of(Owasp.LLM10),
                                expose.getName(),
                                expose.getLine(),
                                "An exposed workflow has no spend cap",
                                "expose "
                                        + expose.getName()
                                        + " has neither a budget of its own nor a run budget in the script, so whoever can call it decides what it costs.",
                                "Add a budget block to the expose (expose "
                                        + expose.getName()
                                        + " { budget { tokens: 50000 calls: 30 } }) or a run budget at the top of the script, and limit calls per caller in the host."));
            }
        }
    }

    private static void remoteCalls(
            LoomScript script, Map<String, SecurityAudit.AgentProfile> profiles, List<Finding> findings) {
        Set<String> remotes = new LinkedHashSet<>();
        script.getRemotes().forEach(r -> remotes.add(r.getName()));
        for (WorkflowDef workflow : script.getWorkflows()) {
            Set<String> reached = reach(script, workflow.getName());
            boolean privateData = reached.stream().anyMatch(a -> profiles.containsKey(a) && !profiles.get(a).privateData().isEmpty());
            boolean masked = reached.stream().anyMatch(a -> profiles.containsKey(a) && isMasking(profiles.get(a).pii()));
            StatementWalker.walk(
                    workflow.getStatements(),
                    s -> {
                        if (!(s instanceof DelegateStmt d) || d.hasDynamicTarget() || !remotes.contains(d.getTargetAgent())) return;
                        boolean worse = privateData && !masked;
                        findings.add(
                                new Finding(
                                        "LA25",
                                        worse ? Severity.MEDIUM : Severity.LOW,
                                        List.of(Owasp.LLM02),
                                        workflow.getName() + " / " + d.getTargetAgent(),
                                        d.getLine(),
                                        "Data leaves to a remote agent",
                                        "The text of this delegate"
                                                + (d.getAttachments().isEmpty() ? "" : " and its attached files")
                                                + " is sent to the remote agent "
                                                + d.getTargetAgent()
                                                + ", which another organisation runs. Nothing in the script masks personal data first"
                                                + (masked ? " is not true here: an agent of this workflow has a pii guard, but the audit cannot see whether it covers this text." : ".")
                                                + (worse ? " The workflow reaches private data." : ""),
                                        "Send only what the remote needs, mask personal data before it is written (guard { pii: mask } on the agent that writes the message, or in your transport or channel), and if you mask elsewhere, accept this finding in writing with that reason."));
                    });
        }
    }

    private static boolean isMasking(String pii) {
        return pii != null && !pii.isBlank() && !pii.equalsIgnoreCase("off") && !pii.equalsIgnoreCase("none");
    }

    private static void channels(LoomScript script, List<Finding> findings) {
        for (AgentChannelDef channel : script.getAgentChannels()) {
            if (!"automatic".equals(channel.get("audience"))) continue;
            boolean remoteUses = false;
            for (RemoteDef remote : script.getRemotes())
                if (channel.getName().equals(remote.get("agent_channel"))) remoteUses = true;
            findings.add(
                    new Finding(
                            "LA26",
                            remoteUses ? Severity.HIGH : Severity.MEDIUM,
                            List.of(Owasp.LLM06),
                            channel.getName(),
                            channel.getLine(),
                            remoteUses
                                    ? "A remote agent's questions and authorization requests are answered with no person"
                                    : "A channel answers with no person",
                            "agent_channel "
                                    + channel.getName()
                                    + " has audience automatic: code answers a party from outside without a person looking"
                                    + (remoteUses
                                            ? ", and a remote agent that uses it can ask for authorization, which code would then arrange on its own."
                                            : "."),
                            "Use audience staff or caller where a person can answer, and where the answer must be automatic make the host's channel refuse authorization requests and answer only what it recognises."));
        }
    }
}
