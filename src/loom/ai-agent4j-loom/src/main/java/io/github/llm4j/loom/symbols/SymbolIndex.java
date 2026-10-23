package io.github.llm4j.loom.symbols;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * The semantic index of a script and the scripts it imports: what is defined, where each name is
 * used, and what each variable holds. Every {@code key} names one thing ({@code agent:Replier},
 * {@code binding:Main/intent}); a rename is "every range with this key".
 */
public record SymbolIndex(
        String entry,
        List<Definition> definitions,
        List<Reference> references,
        List<Binding> bindings,
        List<Scope> scopes,
        List<Problem> problems) {

    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Something a name is given to: an agent, a workflow, a tool, a classifier, a persona, a
     * provider, a decision, a checkpoint or a prompt file.
     */
    public record Definition(
            String key,
            String kind,
            String name,
            String file,
            Range range,
            String scope,
            String detail,
            Range extent,
            SymType type) {}

    /**
     * A place a definition is used. {@code resolved} is false when nothing in the closure defines
     * it (a task, or a typo).
     */
    public record Reference(
            String to, String kind, String role, String file, Range range, boolean resolved) {}

    /** A variable: where it is made, by what, and what it holds. */
    public record Binding(
            String key,
            String name,
            String file,
            Range range,
            String scope,
            String role,
            String producedBy,
            Range producedAt,
            SymType type) {}

    /** A workflow: its parameters and the whole of its text. */
    public record Scope(String workflow, String file, List<String> params, Range range) {}

    /** What the index could not read; the rest is still given. */
    public record Problem(String file, int line, String message) {}

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("version", VERSION);
        n.put("entry", entry);
        n.put("partial", !problems.isEmpty());
        ArrayNode defs = n.putArray("definitions");
        for (Definition d : definitions) {
            ObjectNode o = defs.addObject();
            o.put("key", d.key()).put("kind", d.kind()).put("name", d.name());
            o.put("file", d.file());
            o.set("range", d.range().toJson());
            if (d.scope() != null) o.put("scope", d.scope());
            if (d.detail() != null) o.put("detail", d.detail());
            if (d.extent() != null) o.set("extent", d.extent().toJson());
            if (d.type() != null) o.set("type", d.type().toJson());
        }
        ArrayNode refs = n.putArray("references");
        for (Reference r : references) {
            ObjectNode o = refs.addObject();
            o.put("to", r.to()).put("kind", r.kind()).put("role", r.role());
            o.put("file", r.file());
            o.set("range", r.range().toJson());
            o.put("resolved", r.resolved());
        }
        ArrayNode binds = n.putArray("bindings");
        for (Binding b : bindings) {
            ObjectNode o = binds.addObject();
            o.put("key", b.key()).put("name", b.name()).put("file", b.file());
            o.set("range", b.range().toJson());
            o.put("scope", b.scope()).put("role", b.role());
            if (b.producedBy() != null) {
                ObjectNode by = o.putObject("producedBy");
                by.put("kind", b.producedBy());
                by.set("range", b.producedAt().toJson());
            }
            o.set("type", b.type().toJson());
        }
        ArrayNode scopes = n.putArray("scopes");
        for (Scope s : this.scopes) {
            ObjectNode o = scopes.addObject();
            o.put("workflow", s.workflow()).put("file", s.file());
            ArrayNode ps = o.putArray("params");
            s.params().forEach(ps::add);
            o.set("range", s.range().toJson());
        }
        ArrayNode probs = n.putArray("problems");
        for (Problem p : problems) {
            probs.addObject()
                    .put("file", p.file())
                    .put("line", p.line())
                    .put("message", p.message());
        }
        return n;
    }
}
