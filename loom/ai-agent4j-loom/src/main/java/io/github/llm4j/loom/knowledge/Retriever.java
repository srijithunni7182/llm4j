package io.github.llm4j.loom.knowledge;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Finds the passages most relevant to a question across an agent's knowledge bases. */
public final class Retriever {

    /** One passage and where it came from. */
    public record Passage(String kb, String source, String text, float similarity) { }

    private final List<KnowledgeIndex> indexes;

    public Retriever(List<KnowledgeIndex> indexes) {
        this.indexes = List.copyOf(indexes);
    }

    public int topK() {
        return indexes.stream().mapToInt(i -> i.def().getTopK()).max().orElse(4);
    }

    public List<Passage> search(String query, int k) {
        Map<EmbeddingProvider, float[]> vectors = new IdentityHashMap<>(); // embed once per model
        List<Passage> all = new ArrayList<>();
        for (KnowledgeIndex index : indexes) {
            float[] q = vectors.computeIfAbsent(index.embeddings(), e -> e.embed(query));
            for (VectorStore.SearchResult r : index.store().search(q, k)) {
                Map<String, Object> m = r.getMetadata();
                all.add(new Passage(index.def().getName(), String.valueOf(m.get("source")), String.valueOf(m.get("content")),
                        r.getSimilarity()));
            }
        }
        all.sort(Comparator.comparingDouble(Passage::similarity).reversed());
        return all.subList(0, Math.min(k, all.size()));
    }

    public static String format(List<Passage> passages) {
        if (passages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Relevant knowledge (from your knowledge bases):\n");
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            sb.append('[').append(i + 1).append("] (").append(p.source()).append(") ").append(p.text().strip()).append('\n');
        }
        return sb.toString();
    }

    /** {@code search_<kb>}: lets an agent look things up when it decides to. */
    public static Tool searchTool(KnowledgeIndex index) {
        Retriever one = new Retriever(List.of(index));
        String name = "search_" + index.def().getName().toLowerCase(java.util.Locale.ROOT);
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Searches the " + index.def().getName() + " knowledge base. Arguments: {\"query\": \"<what to look up>\"}."
                        + " Returns the most relevant passages with their source files.";
            }

            @Override
            public String execute(Map<String, Object> args) {
                Object q = args.get("query");
                if (q == null || String.valueOf(q).isBlank()) return "Error: 'query' is required";
                String found = format(one.search(String.valueOf(q), index.def().getTopK()));
                return found.isEmpty() ? "No relevant passages found." : found;
            }
        };
    }
}
