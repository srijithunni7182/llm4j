package io.github.llm4j.loom.deploy;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ScheduleDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Everything a deployment needs to know about a script, read from the script and nothing else: the
 * environment variables it needs (names only), the hosts it talks to, whether it keeps schedules,
 * and whether it asks a person.
 *
 * @param envNames the variables a key or a tool option comes from, sorted
 * @param egressHosts the hosts of the model providers the script names, sorted
 * @param localModels true when a model runs on the host itself (ollama): no key, no fixed host
 * @param schedules the script's {@code schedule} names
 * @param asksAPerson true when a workflow has a {@code human_prompt}
 * @param workflows the workflow names, in order
 * @param unknownModels model names no provider Loom knows (and no provider the script declares)
 *     serves
 */
public record DeployPlan(
        String script,
        List<String> envNames,
        List<String> egressHosts,
        boolean localModels,
        List<String> schedules,
        boolean asksAPerson,
        List<String> workflows,
        List<String> unknownModels) {

    public static DeployPlan derive(LoomScript script, String scriptFileName) {
        TreeSet<String> env = new TreeSet<>();
        TreeSet<String> hosts = new TreeSet<>();
        boolean[] local = {false};
        List<String> models = new ArrayList<>();
        for (AgentDef a : script.getAgents()) if (a.getModel() != null) models.add(a.getModel());
        for (IntentClassifierDef c : script.getIntentClassifiers())
            if (c.getModel() != null) models.add(c.getModel());
        java.util.Set<String> declared = new java.util.HashSet<>();
        script.getProviders().forEach(pd -> declared.add(pd.getName().toLowerCase(Locale.ROOT)));
        List<String> unknown = new ArrayList<>();
        for (String m : models)
            if (!model(m, env, hosts, local)
                    && !declared.contains(m.toLowerCase(Locale.ROOT).split("/")[0])
                    && !unknown.contains(m)) unknown.add(m);
        for (ToolDef t : script.getTools())
            t.getOptions()
                    .values()
                    .forEach(
                            o -> {
                                if (o.fromEnv()) env.add(o.value());
                            });
        List<String> schedules = new ArrayList<>();
        for (ScheduleDef s : script.getSchedules()) schedules.add(s.getName());
        boolean[] person = {false};
        List<String> workflows = new ArrayList<>();
        for (WorkflowDef w : script.getWorkflows()) {
            workflows.add(w.getName());
            StatementWalker.walk(w.getStatements(), s -> person[0] |= s instanceof HumanPromptStmt);
        }
        return new DeployPlan(
                scriptFileName,
                List.copyOf(env),
                List.copyOf(hosts),
                local[0],
                schedules,
                person[0],
                workflows,
                unknown);
    }

    private static boolean model(
            String name, TreeSet<String> env, TreeSet<String> hosts, boolean[] local) {
        String m = name.toLowerCase(Locale.ROOT);
        if (m.startsWith("ollama/")) {
            local[0] = true;
            return true;
        }
        if (m.startsWith("anthropic/") || m.startsWith("claude-")) {
            env.add("ANTHROPIC_API_KEY");
            hosts.add("api.anthropic.com");
            return true;
        }
        if (m.startsWith("sarvam/")) {
            env.add("SARVAM_API_KEY");
            hosts.add("api.sarvam.ai");
            return true;
        }
        var vendor = io.github.llm4j.provider.openai.Vendor.ofModel(name);
        if (vendor.isPresent()) {
            var v = vendor.get().vendor();
            if (v.keyEnv() != null && !v.keyOptional()) env.add(v.keyEnv());
            hosts.addAll(v.hosts());
            return true;
        }
        if (m.contains("gemini")) {
            env.add("GEMINI_API_KEY");
            hosts.add("generativelanguage.googleapis.com");
            return true;
        }
        if (m.contains("llama") || m.contains("gemma") || m.contains("mistral")) {
            local[0] = true;
            return true;
        }
        return false;
    }
}
