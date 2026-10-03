package io.github.llm4j.hexamind.eval;

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
 * Recorded search for the evaluation: {@code eval/golden/search-fixtures.yaml} maps a query pattern to a few
 * snippets; a query that matches nothing finds nothing, which is what a fabricated term must get. A scenario's
 * own recorded results, when it has them, take priority.
 */
public final class SearchLibrary {

    private record Entry(String id, Pattern match, List<String> snippets) {}

    public static final Path FILE = GoldenDataset.DIR.resolve("search-fixtures.yaml");
    public static final String NONE = "No results found.";
    private static final int MAX_SNIPPETS = 5;

    private final List<Entry> entries = new ArrayList<>();

    public static SearchLibrary load() {
        SearchLibrary lib = new SearchLibrary();
        try {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(FILE.toFile());
            for (JsonNode n : root) {
                List<String> s = new ArrayList<>();
                n.path("snippets").forEach(x -> s.add(x.asText()));
                lib.entries.add(new Entry(n.path("id").asText(), Pattern.compile(n.path("match").asText(), Pattern.CASE_INSENSITIVE), s));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + FILE.toAbsolutePath(), e);
        }
        return lib;
    }

    public List<String> ids() {
        return entries.stream().map(Entry::id).toList();
    }

    /** The ids of the entries a query matches. */
    public List<String> matching(String query) {
        return entries.stream().filter(e -> e.match().matcher(query == null ? "" : query).find()).map(Entry::id).toList();
    }

    /** The snippets a query finds (empty when nothing matches). */
    public List<String> lookup(String query) {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.match().matcher(query == null ? "" : query).find()) {
                out.addAll(e.snippets());
            }
        }
        return out.stream().limit(MAX_SNIPPETS).toList();
    }

    /**
     * A search tool named {@code name} that answers from the library, unless {@code scenarioSnippets} is
     * non-empty (then every query gets those) or the query mentions one of {@code suppressTerms} (then it
     * finds nothing).
     */
    public Tool tool(String name, List<String> scenarioSnippets, List<String> suppressTerms) {
        return new RecordedSearch(name, this, scenarioSnippets, suppressTerms);
    }

    /** The tool: counts calls and remembers queries. */
    public static final class RecordedSearch implements Tool {
        private final String name;
        private final SearchLibrary library;
        private final List<String> scenario;
        private final List<String> suppress;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> queries = new CopyOnWriteArrayList<>();

        RecordedSearch(String name, SearchLibrary library, List<String> scenario, List<String> suppress) {
            this.name = name;
            this.library = library;
            this.scenario = scenario == null ? List.of() : scenario;
            this.suppress = suppress == null ? List.of() : suppress;
        }

        public int calls() {
            return calls.get();
        }

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
            List<String> found;
            if (!scenario.isEmpty()) {
                found = scenario;
            } else if (suppress.stream().anyMatch(t -> Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE).matcher(q).find())) {
                found = List.of();
            } else {
                found = library.lookup(q);
            }
            if (found.isEmpty()) {
                return NONE;
            }
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (String s : found) {
                sb.append(i++).append(". ").append(s).append('\n');
            }
            return sb.toString().trim();
        }
    }
}
