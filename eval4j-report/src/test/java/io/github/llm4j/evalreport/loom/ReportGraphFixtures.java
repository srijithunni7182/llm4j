package io.github.llm4j.evalreport.loom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Workflow traces that exercise the report's graph card: one scenario per trace, each with a descriptive id. */
final class ReportGraphFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportGraphFixtures() {}

    /** One trace per line, as {@code traces.jsonl}. */
    static String traces() throws Exception {
        List<ObjectNode> all = new ArrayList<>();
        all.add(allTaken());
        all.add(oneMissed());
        all.add(oneUnexpected());
        all.add(loopThrice());
        all.add(noExpectedPath());
        all.add(emptyPath());
        all.add(unplacedEvents());
        all.add(withoutGraph());
        all.add(tooLarge());
        all.add(blocks400());
        all.add(hostile());
        List<String> lines = new ArrayList<>();
        for (ObjectNode t : all) {
            lines.add(MAPPER.writeValueAsString(t));
        }
        return lines.stream().collect(Collectors.joining("\n", "", "\n"));
    }

    private static ObjectNode node(String id, String kind, String label) {
        ObjectNode n = MAPPER.createObjectNode().put("id", id).put("kind", kind).put("label", label);
        return n;
    }

    private static ObjectNode agentNode(String id, String label, String agent) {
        return node(id, "delegate", label).put("agent", agent);
    }

    private static ObjectNode edge(String from, String to, String label) {
        ObjectNode e = MAPPER.createObjectNode().put("from", from).put("to", to);
        if (label != null) {
            e.put("label", label);
        }
        return e;
    }

    private static ArrayNode ids(String... ids) {
        ArrayNode a = MAPPER.createArrayNode();
        for (String id : ids) {
            a.add(id);
        }
        return a;
    }

    private static ObjectNode trace(String id, ObjectNode workflow) {
        ObjectNode t = MAPPER.createObjectNode().put("traceId", id).put("type", "WORKFLOW");
        t.set("workflow", workflow);
        return t;
    }

    private static ObjectNode workflow(String name, ArrayNode nodes, ArrayNode edges, ArrayNode expected, ArrayNode actual) {
        ObjectNode w = MAPPER.createObjectNode().put("name", name);
        ObjectNode g = w.putObject("graph");
        g.set("nodes", nodes);
        g.set("edges", edges);
        w.set("expectedPath", expected);
        w.set("actualPath", actual);
        w.putArray("events");
        w.putArray("spend");
        return w;
    }

    private static ObjectNode event(double t, String type, String agent, String node, String text) {
        ObjectNode e = MAPPER.createObjectNode().put("t", t).put("type", type);
        if (agent != null) {
            e.put("agent", agent);
        }
        if (node != null) {
            e.put("node", node);
        }
        e.put("text", text);
        return e;
    }

    /** start, an alt with two notes, a join, end. */
    private static ObjectNode branching(String id, ArrayNode expected, ArrayNode actual) {
        ArrayNode nodes = MAPPER.createArrayNode();
        nodes.add(node("start", "start", "Start"));
        nodes.add(agentNode("n1", "delegate Researcher", "Researcher").set("attrs", MAPPER.createObjectNode().put("retry", 3).put("timeoutMs", 90000)));
        nodes.add(node("n2", "alt", "approved?"));
        nodes.add(node("n3", "note", "note"));
        nodes.add(node("n4", "note", "note"));
        nodes.add(node("n5", "note", "after"));
        nodes.add(node("end", "end", "End"));
        ArrayNode edges = MAPPER.createArrayNode();
        edges.add(edge("start", "n1", null)).add(edge("n1", "n2", null)).add(edge("n2", "n3", "then")).add(edge("n2", "n4", "else"));
        edges.add(edge("n3", "n5", null)).add(edge("n4", "n5", null)).add(edge("n5", "end", null));
        return trace(id, workflow("Branching", nodes, edges, expected, actual));
    }

    static ObjectNode allTaken() {
        ObjectNode t = branching("t_all_taken", ids("start", "n1", "n2", "n3", "n5", "end"), ids("start", "n1", "n2", "n3", "n5", "end"));
        ObjectNode w = (ObjectNode) t.get("workflow");
        ArrayNode events = (ArrayNode) w.get("events");
        events.add(event(0.0, "delegate_start", "Researcher", "n1", "delegate Researcher"));
        events.add(event(1.5, "observation", "Researcher", "n1", "12 results"));
        events.add(event(4.2, "delegate_end", "Researcher", "n1", "ok"));
        events.add(event(4.3, "decision", null, "n2", "then"));
        ArrayNode spend = (ArrayNode) w.get("spend");
        spend.add(MAPPER.createObjectNode().put("agent", "Researcher").put("model", "gpt-4").put("promptTokens", 800).put("completionTokens", 90).put("calls", 3).put("costUsd", 0.46).put("estimated", false));
        return t;
    }

    static ObjectNode oneMissed() {
        return branching("t_one_missed", ids("start", "n1", "n2", "n3", "n5", "end"), ids("start", "n1", "n5", "end"));
    }

    static ObjectNode oneUnexpected() {
        return branching("t_one_unexpected", ids("start", "n1", "n2", "n3", "n5", "end"), ids("start", "n1", "n2", "n4", "n5", "end"));
    }

    static ObjectNode loopThrice() {
        ArrayNode nodes = MAPPER.createArrayNode();
        nodes.add(node("start", "start", "Start"));
        nodes.add(node("n1", "loop", "loop until approved").put("bound", 3));
        nodes.add(agentNode("n2", "delegate Writer", "Writer").set("attrs", MAPPER.createObjectNode().put("parent", "n1").put("branch", "body")));
        nodes.add(node("end", "end", "End"));
        ArrayNode edges = MAPPER.createArrayNode();
        edges.add(edge("start", "n1", null)).add(edge("n1", "n2", null)).add(edge("n2", "n1", "again")).add(edge("n1", "end", "done"));
        return trace("t_loop_x3", workflow("Loop", nodes, edges, ids("start", "n1", "n2", "n1", "end"), ids("start", "n1", "n2", "n1", "n2", "n1", "end")));
    }

    static ObjectNode noExpectedPath() {
        ObjectNode t = branching("t_no_expected", ids(), ids("start", "n1", "n2", "n3", "n5", "end"));
        return t;
    }

    static ObjectNode emptyPath() {
        return branching("t_empty_path", ids("start", "n1", "end"), ids());
    }

    static ObjectNode unplacedEvents() {
        ObjectNode t = branching("t_unplaced", ids("start", "n1", "end"), ids("start", "n1", "end"));
        ArrayNode events = (ArrayNode) t.get("workflow").get("events");
        events.add(event(0.1, "delegate_start", "Ghost", null, "no step for this"));
        events.add(event(0.2, "delegate_start", "Ghost", null, "nor this"));
        events.add(event(0.3, "delegate_start", "Researcher", "n1", "this one is placed"));
        return t;
    }

    static ObjectNode withoutGraph() {
        ObjectNode w = MAPPER.createObjectNode().put("name", "NoGraph");
        w.set("expectedPath", ids());
        w.set("actualPath", ids("a"));
        w.putArray("events");
        w.putArray("spend");
        return trace("t_no_graph", w);
    }

    static ObjectNode tooLarge() {
        ArrayNode nodes = MAPPER.createArrayNode();
        ArrayNode edges = MAPPER.createArrayNode();
        for (int i = 0; i < 501; i++) {
            nodes.add(node("n" + i, "note", "note"));
            if (i > 0) {
                edges.add(edge("n" + (i - 1), "n" + i, null));
            }
        }
        return trace("t_too_large", workflow("TooLarge", nodes, edges, ids(), ids("n0")));
    }

    /** Eleven rounds of an alt with a loop and notes inside: over 400 nodes, with their parents recorded. */
    static ObjectNode blocks400() {
        ArrayNode nodes = MAPPER.createArrayNode();
        ArrayNode edges = MAPPER.createArrayNode();
        nodes.add(node("start", "start", "Start"));
        List<String[]> exits = new ArrayList<>();
        exits.add(new String[] {"start", null});
        int id = 0;
        for (int round = 0; round < 11; round++) {
            String alt = "n" + (++id);
            nodes.add(node(alt, "alt", "x?"));
            for (String[] e : exits) {
                edges.add(edge(e[0], alt, e[1]));
            }
            String prev = alt;
            String label = "then";
            for (int i = 0; i < 12; i++) {
                String n = "n" + (++id);
                nodes.add(agentNode(n, "delegate A", "A").set("attrs", MAPPER.createObjectNode().put("parent", alt).put("branch", "then")));
                edges.add(edge(prev, n, label));
                prev = n;
                label = null;
            }
            String loop = "n" + (++id);
            nodes.add(node(loop, "loop", "loop until x").put("bound", 3).set("attrs", MAPPER.createObjectNode().put("parent", alt).put("branch", "then")));
            edges.add(edge(prev, loop, null));
            String body = loop;
            for (int i = 0; i < 12; i++) {
                String n = "n" + (++id);
                nodes.add(node(n, "note", "inside").set("attrs", MAPPER.createObjectNode().put("parent", loop).put("branch", "body")));
                edges.add(edge(body, n, null));
                body = n;
            }
            edges.add(edge(body, loop, "again"));
            String other = alt;
            String elseLabel = "else";
            for (int i = 0; i < 12; i++) {
                String n = "n" + (++id);
                nodes.add(node(n, "note", "else").set("attrs", MAPPER.createObjectNode().put("parent", alt).put("branch", "else")));
                edges.add(edge(other, n, elseLabel));
                other = n;
                elseLabel = null;
            }
            exits = new ArrayList<>();
            exits.add(new String[] {loop, "done"});
            exits.add(new String[] {other, null});
        }
        nodes.add(node("end", "end", "End"));
        for (String[] e : exits) {
            edges.add(edge(e[0], "end", e[1]));
        }
        return trace("t_blocks_400", workflow("Blocks", nodes, edges, ids(), ids("start", "n1")));
    }

    /** Labels and agent names that try to break out of the page. */
    static ObjectNode hostile() {
        String evil = "<img src=x onerror=\"window.__pwned=1\"> </script><script>window.__pwned=1</script>";
        ArrayNode nodes = MAPPER.createArrayNode();
        nodes.add(node("start", "start", "Start"));
        nodes.add(agentNode("n1", "delegate " + evil, evil));
        nodes.add(node("n2", "note", "note").set("attrs", MAPPER.createObjectNode().put("text", evil)));
        nodes.add(node("end", "end", "End"));
        ArrayNode edges = MAPPER.createArrayNode();
        edges.add(edge("start", "n1", evil)).add(edge("n1", "n2", null)).add(edge("n2", "end", null));
        return trace("t_hostile", workflow("Hostile", nodes, edges, ids("start", "n1", "n2", "end"), ids("start", "n1", "n2", "end")));
    }
}
