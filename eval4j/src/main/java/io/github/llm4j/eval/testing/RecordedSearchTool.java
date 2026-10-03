package io.github.llm4j.eval.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A search tool that answers from recorded snippets instead of the network: deterministic, free,
 * and the same text a grounding judge is given. A query that matches nothing finds nothing ({@link
 * #NO_RESULTS}), which is what a search for a fabricated term returns.
 *
 * <p>Snippets come from a YAML library of {@code id}, {@code match} (a case-insensitive regex over
 * the query) and {@code snippets}, or are given directly:
 *
 * <pre>{@code
 * Tool search = RecordedSearchTool.fromYaml("WebSearch", Path.of("search-fixtures.yaml"))
 *     .suppressing("QLL-7");            // a term that must find nothing
 * Tool oneCase = RecordedSearchTool.fixed("WebSearch", scenario.retrievalContext());
 * }</pre>
 */
public final class RecordedSearchTool implements Tool {

    public static final String NO_RESULTS = "No results found.";
    private static final int MAX_SNIPPETS = 5;

    private record Entry(Pattern match, List<String> snippets) {}

    private final String name;
    private final List<Entry> entries;
    private final List<String> fixed;
    private final List<Pattern> suppressed = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    private RecordedSearchTool(String name, List<Entry> entries, List<String> fixed) {
        this.name = name;
        this.entries = entries;
        this.fixed = fixed;
    }

    /** A tool that answers every query with the same snippets (none means "no results"). */
    public static RecordedSearchTool fixed(String name, List<String> snippets) {
        return new RecordedSearchTool(
                name, List.of(), snippets == null ? List.of() : List.copyOf(snippets));
    }

    /**
     * A tool that answers from a YAML library, matching each query against the entries' patterns.
     */
    public static RecordedSearchTool fromYaml(String name, Path file) {
        List<Entry> entries = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
            for (JsonNode n : root) {
                List<String> s = new ArrayList<>();
                n.path("snippets").forEach(x -> s.add(x.asText()));
                entries.add(
                        new Entry(
                                Pattern.compile(n.path("match").asText(), Pattern.CASE_INSENSITIVE),
                                s));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
        return new RecordedSearchTool(name, entries, List.of());
    }

    /**
     * Queries mentioning any of {@code terms} (case-insensitive) find nothing, whatever the library
     * holds.
     */
    public RecordedSearchTool suppressing(String... terms) {
        for (String t : terms) {
            suppressed.add(Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE));
        }
        return this;
    }

    /** The snippets a query finds. */
    public List<String> lookup(String query) {
        String q = query == null ? "" : query;
        if (suppressed.stream().anyMatch(p -> p.matcher(q).find())) {
            return List.of();
        }
        if (!fixed.isEmpty()) {
            return fixed;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.match().matcher(q).find()) {
                out.addAll(e.snippets());
            }
        }
        return out.stream().limit(MAX_SNIPPETS).toList();
    }

    public int calls() {
        return calls.get();
    }

    /** Every query received, in order. */
    public List<String> queries() {
        return queries;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "Searches the web and returns result snippets. Use it to verify unfamiliar terms and to find recent information.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        calls.incrementAndGet();
        String q = String.valueOf(args);
        queries.add(q);
        List<String> found = lookup(q);
        if (found.isEmpty()) {
            return NO_RESULTS;
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String s : found) {
            sb.append(i++).append(". ").append(s).append('\n');
        }
        return sb.toString().trim();
    }
}
