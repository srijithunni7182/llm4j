package io.github.llm4j.context;

import java.util.Arrays;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * The built-in compaction strategies, and a way to find any strategy by name.
 *
 * <pre>{@code
 * agent.compaction(ContextCompactors.dropOldest());
 * agent.compaction(ContextCompactors.chain(ContextCompactors.trimToolResults(2_000), ContextCompactors.dropOldest()));
 * }</pre>
 */
public final class ContextCompactors {

    private ContextCompactors() {}

    /** Does nothing. */
    public static ContextCompactor none() {
        return new NoCompaction();
    }

    /**
     * Removes the oldest whole exchanges, leaving one marker that says how many messages went. No
     * model call.
     */
    public static ContextCompactor dropOldest() {
        return new DropOldest();
    }

    /**
     * Shortens tool results longer than {@code maxChars} (at least 40), oldest first, keeping their
     * head and tail. No model call.
     */
    public static ContextCompactor trimToolResults(int maxChars) {
        return new TrimToolResults(maxChars);
    }

    /**
     * Replaces the oldest part of the conversation with a summary written by a model (at most 600
     * tokens). If the call fails, is refused by a budget, or gives nothing useful, it falls back to
     * {@link #dropOldest()} and the result says so. The model's own tokens and cost are reported in
     * {@link CompactionResult#summarizerUsage()}.
     *
     * @param client the model that writes the summary; a cheaper one than the agent's is fine
     */
    public static ContextCompactor summarize(io.github.llm4j.LLMClient client) {
        return new Summarize(client, Summarize.DEFAULT_MAX_SUMMARY_TOKENS);
    }

    /**
     * Like {@link #summarize(io.github.llm4j.LLMClient)}, with a limit on how long the summary may
     * be.
     */
    public static ContextCompactor summarize(
            io.github.llm4j.LLMClient client, int maxSummaryTokens) {
        return new Summarize(client, maxSummaryTokens);
    }

    /** Applies the strategies in order, stopping once the target is reached. */
    public static ContextCompactor chain(ContextCompactor... strategies) {
        return new Chain(Arrays.asList(strategies));
    }

    /**
     * Finds a strategy by name (built-in, or from a jar that lists a {@link
     * ContextCompactorProvider}).
     */
    public static ContextCompactor byName(String name, Map<String, String> options) {
        return byName(name, options, ProviderContext.none());
    }

    public static ContextCompactor byName(
            String name, Map<String, String> options, ProviderContext context) {
        Map<String, ContextCompactorProvider> found = providers();
        ContextCompactorProvider p = found.get(name);
        if (p == null) throw new IllegalArgumentException(unknown(name, found));
        return p.create(
                options == null ? Map.of() : options,
                context == null ? ProviderContext.none() : context);
    }

    /** The names {@link #byName} knows, in alphabetical order. */
    public static java.util.List<String> names() {
        return java.util.List.copyOf(providers().keySet());
    }

    private static Map<String, ContextCompactorProvider> providers() {
        Map<String, ContextCompactorProvider> found = new TreeMap<>();
        for (ContextCompactorProvider p : ServiceLoader.load(ContextCompactorProvider.class)) {
            ContextCompactorProvider earlier = found.put(p.name(), p);
            if (earlier != null) {
                throw new IllegalStateException(
                        "two providers are named '"
                                + p.name()
                                + "': "
                                + earlier.getClass().getName()
                                + " and "
                                + p.getClass().getName());
            }
        }
        return found;
    }

    private static String unknown(String name, Map<String, ContextCompactorProvider> found) {
        StringBuilder sb =
                new StringBuilder("unknown context compaction strategy '")
                        .append(name)
                        .append('\'');
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String known : found.keySet()) {
            int d = distance(name == null ? "" : name, known);
            if (d < bestDistance) {
                bestDistance = d;
                best = known;
            }
        }
        if (best != null && bestDistance <= Math.max(2, best.length() / 3))
            sb.append("; did you mean '").append(best).append("'?");
        return sb.append(" (known strategies: ")
                .append(String.join(", ", found.keySet()))
                .append(')')
                .toString();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
