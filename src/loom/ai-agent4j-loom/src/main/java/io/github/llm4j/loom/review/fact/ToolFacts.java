package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.security.Capabilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code tool} entities, and the agent facts that come from them (which tools an agent has,
 * whether any of them writes or sends). What a tool reaches is read from the same place the
 * security audit reads it, so the review and the audit cannot disagree about what a tool does.
 */
final class ToolFacts implements FactProvider {

    private static final Set<String> JOURNALED =
            Set.of("webhook", "email", "http", "file", "shell", "sql");
    private static final List<String> REACH =
            List.of("none", "reads", "fetches", "writes", "sends");

    @Override
    public String name() {
        return "tool";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        String file = input.review().file();
        Map<String, ToolDef> declared = new LinkedHashMap<>();
        for (ToolDef t : input.review().script().getTools()) declared.put(t.getName(), t);
        Set<String> names = new LinkedHashSet<>(declared.keySet());
        for (AgentDef a : input.review().script().getAgents()) names.addAll(a.getTools());

        Map<String, Entity> tools = new LinkedHashMap<>();
        for (String name : names) {
            ToolDef def = declared.get(name);
            Capabilities c = Capabilities.resolve(name, declared, input.review().lootReach());
            Entity e =
                    out.add(
                            "tool",
                            "tool:" + name,
                            Location.at(file, def == null ? 0 : def.getLine()));
            boolean fromLoot = input.review().lootReach().containsKey(name);
            String kind =
                    def != null
                            ? String.valueOf(def.getKind()).toLowerCase(Locale.ROOT)
                            : fromLoot || !c.known() ? "class" : "builtin";
            out.set(e, "name", name);
            out.set(e, "line", def == null ? 0L : (long) def.getLine());
            out.set(e, "kind", kind);
            out.set(e, "reach", reach(c));
            out.set(e, "reachKnown", c.known());
            out.set(e, "journaled", JOURNALED.contains(kind));
            out.set(e, "idempotentDeclared", false);
            tools.put(name, e);
        }

        for (AgentDef a : input.review().script().getAgents()) {
            Entity agent = out.find("agent:" + a.getName()).orElseThrow();
            List<Object> own = new ArrayList<>();
            int widest = 0;
            for (String t : a.getTools()) {
                Entity tool = tools.get(t);
                if (tool == null) continue;
                own.add(tool);
                widest = Math.max(widest, REACH.indexOf((String) tool.get("reach")));
                @SuppressWarnings("unchecked")
                List<Object> users = (List<Object>) usedBy(tool);
                users.add(agent);
            }
            out.set(agent, "tools", own);
            out.set(agent, "hasTools", !own.isEmpty());
            out.set(agent, "maxReach", REACH.get(widest));
            out.set(agent, "hasEffects", widest >= REACH.indexOf("writes"));
        }
        for (Entity tool : tools.values()) {
            @SuppressWarnings("unchecked")
            List<Object> users = (List<Object>) usedBy(tool);
            boolean approved = !users.isEmpty();
            for (Object o : users) {
                Entity agent = (Entity) o;
                boolean all = Boolean.TRUE.equals(agent.get("approveAll"));
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) agent.get("approve");
                if (!all && !list.contains(tool.get("name"))) approved = false;
            }
            out.set(tool, "approved", approved);
        }
    }

    /** The mutable list of agents using a tool, created on first use. */
    private static Object usedBy(Entity tool) {
        Object existing = tool.values().get("usedBy");
        if (existing != null) return existing;
        List<Object> list = new ArrayList<>();
        tool.set("usedBy", list);
        return list;
    }

    /**
     * The widest thing a tool can do, in the review's five words. A reach nobody declared is
     * assumed to send.
     */
    static String reach(Capabilities c) {
        if (!c.known() || c.outward()) return "sends";
        if (c.effect()) return "writes";
        if (c.untrusted()) return "fetches";
        if (c.privateData()) return "reads";
        return "none";
    }
}
