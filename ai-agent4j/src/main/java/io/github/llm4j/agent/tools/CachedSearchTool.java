package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A wrapper tool that adds caching capabilities to any search tool.
 * This helps reduce API usage and speed up responses for repeated queries.
 */
public class CachedSearchTool implements Tool {

    private final Tool delegate;
    private static final Map<String, String> cache = new ConcurrentHashMap<>();

    public CachedSearchTool(Tool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        if (query == null || query.trim().isEmpty()) {
            query = (String) args.get("input");
        }

        if (query == null || query.trim().isEmpty()) {
            return delegate.execute(args);
        }

        String cacheKey = query.trim().toLowerCase();
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey) + "\n(Note: This result was retrieved from cache)";
        }

        String result = delegate.execute(args);

        // Only cache successful results (not errors)
        if (result != null && !result.startsWith("Error") && !result.toLowerCase().contains("api error")) {
            cache.put(cacheKey, result);
        }

        return result;
    }

    /**
     * Clears the search cache.
     */
    public static void clearCache() {
        cache.clear();
    }
}
