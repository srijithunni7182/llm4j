package io.github.llm4j.hexamind.eval;

import io.github.llm4j.agent.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@code WebSearch} tool that answers from recorded snippets instead of the network: deterministic,
 * free, and the very text the grounding judge is given. With no fixture it reports no results, which is
 * what a search for a fabricated term returns.
 */
public final class FixtureSearchTool implements Tool {

    private final List<String> snippets;
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();

    public FixtureSearchTool(List<String> snippets) {
        this.snippets = snippets == null ? List.of() : List.copyOf(snippets);
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Searches the web and returns result snippets. Use it to verify unfamiliar terms and to find recent information.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        calls.incrementAndGet();
        Object q = args == null ? null : args.getOrDefault("query", args.getOrDefault("input", args.get("q")));
        queries.add(String.valueOf(q));
        if (snippets.isEmpty()) {
            return "No results found.";
        }
        StringBuilder sb = new StringBuilder("Search results:\n");
        for (int i = 0; i < snippets.size(); i++) {
            sb.append(i + 1).append(". ").append(snippets.get(i)).append('\n');
        }
        return sb.toString();
    }

    public int calls() {
        return calls.get();
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    public List<String> snippets() {
        return snippets;
    }
}
