package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.security.Capabilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The safe defaults of spec 12 R10 and the structure a benchmark description expects, read from the
 * generated script. The defaults hold for every project; the expectations ({@code expect.json})
 * belong to one description.
 */
public final class Structure {

    private Structure() {}

    /** What every generated project must have, whatever it is for. */
    public static List<String> defaults(Path script) throws Exception {
        return defaults(script, java.util.Set.of());
    }

    /**
     * As {@link #defaults(Path)}, except for tools the plan itself says need no approval (with the
     * reason it gives, which the developer reads before anything is written).
     */
    public static List<String> defaults(Path script, java.util.Set<String> approvalNotNeeded)
            throws Exception {
        LoomScript s = new LoomLoader().load(script.toString());
        List<String> out = new ArrayList<>();
        if (s.getBudget() == null) out.add("no budget");
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        s.getTools().forEach(t -> tools.put(t.getName(), t));
        for (AgentDef a : s.getAgents())
            for (String t : a.getTools()) {
                Capabilities c = Capabilities.resolve(t, tools, Map.of());
                if (c.effect()
                        && !approvalNotNeeded.contains(t)
                        && !a.isApproveAll()
                        && !a.getApprove().contains(t))
                    out.add("agent " + a.getName() + " can call " + t + " without approve:");
            }
        Map<String, AgentDef> agents = new LinkedHashMap<>();
        s.getAgents().forEach(a -> agents.put(a.getName(), a));
        s.getWorkflows()
                .forEach(
                        w ->
                                StatementWalker.walk(
                                        w.getStatements(),
                                        st -> {
                                            if (st instanceof LoopStmt l
                                                    && l.getMaxIterations() <= 0)
                                                out.add("a loop in " + w.getName() + " has no max");
                                            if (st instanceof DelegateStmt d) {
                                                AgentDef a = agents.get(d.getTargetAgent());
                                                if (a != null
                                                        && !a.getTools().isEmpty()
                                                        && d.getTimeoutMillis() <= 0)
                                                    out.add(
                                                            "a step using tools of "
                                                                    + a.getName()
                                                                    + " has no timeout");
                                                if (a != null
                                                        && !a.getTools().isEmpty()
                                                        && d.getRetryCount() <= 0
                                                        && d.getOnFailure().isEmpty())
                                                    out.add(
                                                            "a step using tools of "
                                                                    + a.getName()
                                                                    + " has neither retry nor on_failure");
                                            }
                                        }));
        return out;
    }

    /**
     * What one description expects: {@code agents: [min, max]}, {@code person: true} (a person is
     * asked), {@code classifier: true}, {@code effects: true} (something is sent or written).
     */
    public static List<String> expected(Path script, JsonNode expect) throws Exception {
        LoomScript s = new LoomLoader().load(script.toString());
        List<String> out = new ArrayList<>();
        // a classifier does the work of an agent that only labels: a description that is a
        // classification is rightly built with no agent at all
        int agents = s.getAgents().size() + s.getIntentClassifiers().size();
        if (expect.has("agents")) {
            int min = expect.get("agents").get(0).asInt();
            int max = expect.get("agents").get(1).asInt();
            if (agents < min || agents > max)
                out.add("expected " + min + " to " + max + " agents, found " + agents);
        }
        if (expect.has("classifier")
                && expect.get("classifier").asBoolean() != !s.getIntentClassifiers().isEmpty())
            out.add("classifier expected " + expect.get("classifier").asBoolean());
        boolean[] person = {false};
        s.getWorkflows()
                .forEach(
                        w ->
                                StatementWalker.walk(
                                        w.getStatements(),
                                        st -> {
                                            if (st instanceof HumanPromptStmt) person[0] = true;
                                        }));
        if (expect.has("person") && expect.get("person").asBoolean() != person[0])
            out.add("person expected " + expect.get("person").asBoolean());
        if (expect.has("effects")) {
            Map<String, ToolDef> tools = new LinkedHashMap<>();
            s.getTools().forEach(t -> tools.put(t.getName(), t));
            boolean any =
                    tools.keySet().stream()
                            .anyMatch(t -> Capabilities.resolve(t, tools, Map.of()).effect());
            if (expect.get("effects").asBoolean() != any)
                out.add("effects expected " + expect.get("effects").asBoolean());
        }
        return out;
    }
}
