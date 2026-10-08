package io.github.llm4j.loom.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One statement of a workflow (or {@code start} / {@code end}).
 *
 * @param kind one of {@link Kinds}
 * @param agent the agent a step is for, or {@code null}
 * @param bound a loop's declared maximum iterations, or {@code null}
 * @param source where the statement is written, or {@code null} when it has no line
 * @param call for a {@code call} node, the workflow it names
 * @param unresolved a {@code call} whose workflow is defined nowhere in the import closure, or a rewind to a
 *     checkpoint that does not exist
 * @param parent the node whose block this one is inside, or {@code null} at the top level
 * @param branch which block of {@code parent} this node is in ({@code then}, {@code else}, {@code failure}, …)
 * @param attrs the settings written on the statement; only those that are set
 */
public record GraphNode(
        String id,
        String kind,
        String label,
        String agent,
        Integer bound,
        SourceRef source,
        CallLink call,
        boolean unresolved,
        String parent,
        String branch,
        Map<String, Object> attrs) {

    public GraphNode {
        attrs = attrs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }

    public GraphNode withPlacement(String parentId, String branchName) {
        return new GraphNode(id, kind, label, agent, bound, source, call, unresolved, parentId, branchName, attrs);
    }

    public GraphNode withCall(CallLink link, boolean isUnresolved) {
        return new GraphNode(id, kind, label, agent, bound, source, link, isUnresolved, parent, branch, attrs);
    }

    public GraphNode withUnresolved(boolean isUnresolved) {
        return new GraphNode(id, kind, label, agent, bound, source, call, isUnresolved, parent, branch, attrs);
    }

    /** The same node with one more setting shown on it. */
    public GraphNode withAttr(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(attrs);
        next.put(key, value);
        return new GraphNode(id, kind, label, agent, bound, source, call, unresolved, parent, branch, next);
    }
}
