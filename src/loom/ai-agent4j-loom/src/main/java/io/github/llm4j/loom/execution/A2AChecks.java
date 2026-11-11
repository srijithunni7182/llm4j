package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentChannelDef;
import io.github.llm4j.loom.ast.AskStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ExposeDef;
import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.NamedSettings;
import io.github.llm4j.loom.ast.RemoteDef;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The static checks of {@code remote}, {@code agent_channel}, {@code expose} and {@code ask} (docs/specs/
 * 32-a2a-integration, R26): what can be known before a run is found by {@code weave check}, with the
 * line and what to write. A remote agent itself cannot be validated here (its card is read at run
 * time), and a channel's implementation is the host's, so each says so in a warning.
 */
final class A2AChecks {

    private static final Pattern DURATION = Pattern.compile("\\d+(\\.\\d+)?(ms|s|m|h)");
    private static final Pattern SIZE = Pattern.compile("\\d+(k|kb|m|mb)?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> AUDIENCES = Set.of("staff", "caller", "automatic");

    private static final Set<String> REMOTE_KEYS = Set.of("card", "auth", "agent_channel", "timeout", "require_signed_card");
    private static final Set<String> CHANNEL_KEYS = Set.of("audience", "max_questions", "timeout");
    private static final Set<String> EXPOSE_KEYS = Set.of("workflow", "description", "agent_channel", "text_input", "max_input", "timeout");

    private A2AChecks() {}

    static void run(ScriptValidator.Checker c) {
        LoomScript script = c.script();
        Set<String> channels = new LinkedHashSet<>();
        script.getAgentChannels().forEach(ch -> channels.add(ch.getName()));
        for (AgentChannelDef channel : script.getAgentChannels()) channel(c, channel);
        for (RemoteDef remote : script.getRemotes()) remote(c, remote, channels);
        for (ExposeDef expose : script.getExposes()) expose(c, expose, channels);
        statements(c, channels);
    }

    // ---- declarations ----

    private static void unknownKeys(ScriptValidator.Checker c, NamedSettings def, String construct, Set<String> allowed) {
        for (String key : def.getValues().keySet()) {
            if (allowed.contains(key)) continue;
            String extra = switch (key) {
                case "version" -> " An A2A version is never written in a script: the host's transport negotiates it, so there is no version setting.";
                case "pii" -> " How personal data is masked on the way out is decided in the transport, the channel or the script (guard on the agent that writes the message), so there is no pii setting.";
                default -> "";
            };
            c.error(def.lineOf(key), construct, construct + " has no setting '" + key + "'." + extra + " Settings: " + String.join(", ", allowed) + ".");
        }
    }

    private static void required(ScriptValidator.Checker c, NamedSettings def, String construct, String key, String example) {
        if (!def.has(key)) c.error(def.getLine(), construct, construct + " needs " + key + ", as in " + key + ": " + example);
    }

    private static void duration(ScriptValidator.Checker c, NamedSettings def, String construct, String key) {
        String value = def.get(key);
        if (def.has(key) && (value == null || !DURATION.matcher(value).matches()))
            c.error(def.lineOf(key), construct, key + " is a duration such as 90s, 30m or 2h, not '" + value + "'.");
    }

    private static void channel(ScriptValidator.Checker c, AgentChannelDef def) {
        String construct = "agent_channel " + def.getName();
        unknownKeys(c, def, construct, CHANNEL_KEYS);
        required(c, def, construct, "audience", "staff  (staff, caller or automatic)");
        String audience = def.get("audience");
        if (def.has("audience") && (audience == null || !AUDIENCES.contains(audience)))
            c.error(def.lineOf("audience"), construct, "audience is staff (people in your organisation), caller (the caller of an exposed workflow) or automatic (no person), not '" + audience + "'.");
        if (def.has("max_questions") && def.getInt("max_questions", 0) < 1)
            c.error(def.lineOf("max_questions"), construct, "max_questions is a whole number of 1 or more (the default is " + AgentChannelDef.DEFAULT_MAX_QUESTIONS + ").");
        duration(c, def, construct, "timeout");
        c.warn(def.getLine(), construct, "the host must supply the implementation of agent_channel " + def.getName() + ", registered under that name; until it does, the first use fails closed. weave check cannot see it.");
    }

    private static void remote(ScriptValidator.Checker c, RemoteDef def, Set<String> channels) {
        String construct = "remote " + def.getName();
        unknownKeys(c, def, construct, REMOTE_KEYS);
        required(c, def, construct, "card", "\"https://agent.example.com/.well-known/agent-card.json\"");
        required(c, def, construct, "agent_channel", "SupplierDesk  (a declared agent_channel)");
        String card = def.get("card");
        if (def.has("card") && (card == null || !card.toLowerCase().startsWith("https://")))
            c.error(def.lineOf("card"), construct, "card must be an https address, not '" + card + "'.");
        ToolDef.OptionValue auth = def.getValues().get("auth");
        if (auth != null && !auth.isReference())
            c.error(def.lineOf("auth"), construct, "auth must be a reference, secret.NAME or env.NAME, never the credential itself.");
        String channel = def.get("agent_channel");
        if (def.has("agent_channel") && (channel == null || !channels.contains(channel)))
            c.error(def.lineOf("agent_channel"), construct, "agent_channel " + channel + " is not declared. Declared: " + (channels.isEmpty() ? "none" : String.join(", ", channels)) + ".");
        duration(c, def, construct, "timeout");
        String signed = def.get("require_signed_card");
        if (def.has("require_signed_card") && !"true".equals(signed) && !"false".equals(signed))
            c.error(def.lineOf("require_signed_card"), construct, "require_signed_card is true or false, not '" + signed + "'.");
        c.warn(def.getLine(), construct, "weave does not validate a remote agent, so it is not validated: its card is read at run time, and whether it exists, speaks A2A 1.0 and offers what you expect is checked then. A card that points at another host is refused before anything is sent.");
    }

    private static void expose(ScriptValidator.Checker c, ExposeDef def, Set<String> channels) {
        String construct = "expose " + def.getName();
        unknownKeys(c, def, construct, EXPOSE_KEYS);
        required(c, def, construct, "workflow", "Expense  (a workflow of this script)");
        required(c, def, construct, "description", "\"Reads a receipt and returns the total.\"");
        required(c, def, construct, "agent_channel", "CallerDesk  (a declared agent_channel)");
        LoomScript script = c.script();
        String workflowName = def.get("workflow");
        WorkflowDef workflow = null;
        if (def.has("workflow")) {
            workflow = script.getWorkflows().stream().filter(w -> w.getName().equals(workflowName)).findFirst().orElse(null);
            if (workflow == null) {
                List<String> known = script.getWorkflows().stream().map(WorkflowDef::getName).toList();
                c.error(def.lineOf("workflow"), construct, "there is no workflow named " + workflowName + " (only workflows can be exposed). Workflows: " + (known.isEmpty() ? "none" : String.join(", ", known)) + ".");
            }
        }
        String channel = def.get("agent_channel");
        if (def.has("agent_channel") && (channel == null || !channels.contains(channel)))
            c.error(def.lineOf("agent_channel"), construct, "agent_channel " + channel + " is not declared. Declared: " + (channels.isEmpty() ? "none" : String.join(", ", channels)) + ".");
        String textInput = def.get("text_input");
        if (def.has("text_input") && workflow != null && !workflow.getParameters().contains(textInput))
            c.error(def.lineOf("text_input"), construct, "text_input names " + textInput + ", which is not a parameter of workflow " + workflowName + " (" + String.join(", ", workflow.getParameters()) + ").");
        String maxInput = def.get("max_input");
        if (def.has("max_input") && (maxInput == null || !SIZE.matcher(maxInput).matches()))
            c.error(def.lineOf("max_input"), construct, "max_input is a size such as 64k or 1mb, not '" + maxInput + "'.");
        duration(c, def, construct, "timeout");
    }

    // ---- statements ----

    private static void statements(ScriptValidator.Checker c, Set<String> channels) {
        LoomScript script = c.script();
        Set<String> remotes = new LinkedHashSet<>();
        script.getRemotes().forEach(r -> remotes.add(r.getName()));
        for (WorkflowDef workflow : script.getWorkflows()) {
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(workflow.getStatements(), all::add);
            for (Statement s : all) {
                if (s instanceof AskStmt ask) {
                    if (!channels.contains(ask.getChannel()))
                        c.error(ask.getLine(), "ask " + ask.getChannel(), "agent_channel " + ask.getChannel() + " is not declared. Declared: " + (channels.isEmpty() ? "none" : String.join(", ", channels)) + ".");
                } else if (s instanceof DelegateStmt d) {
                    delegate(c, d, remotes);
                } else if (s instanceof HandoffStmt h && remotes.contains(h.getTargetAgent())) {
                    c.error(h.getLine(), "handoff " + h.getTargetAgent(), "handoff does not take a remote agent: " + h.getTargetAgent() + " is a remote. Use delegate to call it.");
                } else if (s instanceof BroadcastStmt b) {
                    for (String target : b.getTargetAgents())
                        if (remotes.contains(target))
                            c.error(b.getLine(), "broadcast " + target, "broadcast does not take a remote agent: " + target + " is a remote. Call remote agents in a parallel block with one delegate each.");
                } else if (s instanceof ExtractStmt e && remotes.contains(e.getExtractor())) {
                    c.error(e.getLine(), "extract " + e.getExtractor(), "extract does not take a remote agent.");
                }
            }
        }
    }

    private static void delegate(ScriptValidator.Checker c, DelegateStmt d, Set<String> remotes) {
        boolean toRemote = remotes.contains(d.getTargetAgent());
        if (toRemote && d.getBudget() != null && d.getBudget().getTokens() != null)
            c.error(d.getLine(), "delegate " + d.getTargetAgent(), "a token budget on a step that calls a remote agent counts nothing: its cost is outside what Loom sees. Remove the budget; bound the call with timeout and the remote's own timeout.");
        if (d.hasDynamicTarget() && !d.getAllowedTargets().isEmpty()) {
            long remote = d.getAllowedTargets().stream().filter(remotes::contains).count();
            if (remote > 0 && remote < d.getAllowedTargets().size())
                c.error(d.getLine(), "delegate", "the list of agents mixes remote and local agents; name only remote agents or only local ones.");
        }
    }
}
