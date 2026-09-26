package io.github.llm4j.getviral.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.PGVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.getviral.config.GetViralConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The retrieval layer: a curated "viral playbook" (hook formulas, platform playbooks, retention
 * tactics) plus each creator's own past posts, embedded locally and searched by meaning.
 *
 * <p>Built on the ai-agent4j RAG primitives and the addons module:
 * <ul>
 *   <li>Embeddings: addons {@link OnnxEmbeddingProvider} when GETVIRAL_ONNX_MODEL /
 *       GETVIRAL_ONNX_TOKENIZER point at a model, else the bundled MiniLM model.</li>
 *   <li>Store: addons {@link PGVectorStore} when GETVIRAL_PGVECTOR_URL is set, else in-memory.</li>
 * </ul>
 */
public class KnowledgeBase {

    private static final Logger log = Logger.getLogger(KnowledgeBase.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] PLAYBOOK = {
        "hooks.md", "x-threads.md", "instagram-reels.md", "youtube.md", "retention.md", "trust-and-safety.md"
    };

    public record Hit(String text, String source, double score) { }

    private final EmbeddingProvider embeddings;
    private final VectorStore store;
    private final String embeddingLabel;
    private final Path voicesDir;
    private final Map<String, String> texts = new LinkedHashMap<>();

    private static volatile KnowledgeBase shared;

    /** One knowledge base per JVM — embedding the playbook is done once. */
    public static KnowledgeBase shared(GetViralConfig config) {
        KnowledgeBase kb = shared;
        if (kb == null) {
            synchronized (KnowledgeBase.class) {
                if (shared == null) {
                    shared = new KnowledgeBase(config);
                }
                kb = shared;
            }
        }
        return kb;
    }

    KnowledgeBase(GetViralConfig config) {
        EmbeddingProvider provider = null;
        String label = "MiniLM-L6-v2 (bundled, local)";
        if (config.onnxModelPath() != null && config.onnxTokenizerPath() != null) {
            try {
                provider = new OnnxEmbeddingProvider(config.onnxModelPath(), config.onnxTokenizerPath());
                label = "ONNX Runtime via ai-agent4j-addons (" + Path.of(config.onnxModelPath()).getFileName() + ")";
            } catch (Exception e) {
                log.warning("ONNX embeddings unavailable (" + e.getMessage() + ") — using bundled MiniLM.");
            }
        }
        this.embeddings = provider != null ? provider : new MiniLmEmbeddingProvider();
        this.embeddingLabel = label;

        String pgUrl = System.getenv("GETVIRAL_PGVECTOR_URL");
        VectorStore chosen = new InMemoryVectorStore();
        if (pgUrl != null && !pgUrl.isBlank()) {
            try {
                chosen = new PGVectorStore(pgUrl,
                        System.getenv().getOrDefault("GETVIRAL_PGVECTOR_USER", "postgres"),
                        System.getenv().getOrDefault("GETVIRAL_PGVECTOR_PASSWORD", ""),
                        "getviral_knowledge", embeddings.getDimensions());
                chosen.clear();
            } catch (Exception e) {
                log.warning("pgvector unavailable (" + e.getMessage() + ") — using in-memory store.");
                chosen = new InMemoryVectorStore();
            }
        }
        this.store = chosen;
        this.voicesDir = config.dataDir().resolve("voices");
        loadPlaybook();
    }

    public String embeddingLabel() {
        return embeddingLabel;
    }

    public String storeLabel() {
        return store instanceof PGVectorStore ? "PostgreSQL pgvector (ai-agent4j-addons)" : "In-memory vector store";
    }

    private void loadPlaybook() {
        for (String file : PLAYBOOK) {
            try (InputStream in = KnowledgeBase.class.getResourceAsStream("/getviral/playbook/" + file)) {
                if (in == null) continue;
                String markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String section : markdown.split("(?m)^## ")) {
                    String chunk = section.strip();
                    if (chunk.isEmpty() || chunk.startsWith("# ")) continue;
                    index("playbook", null, file.replace(".md", "") + " › " + chunk.lines().findFirst().orElse(""),
                            "## " + chunk);
                }
            } catch (IOException e) {
                log.warning("Could not read playbook " + file + ": " + e.getMessage());
            }
        }
    }

    private synchronized void index(String kind, String creator, String source, String text) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind);
        metadata.put("source", source);
        if (creator != null) metadata.put("creator", creator);
        store.add(id, embeddings.embed(text), metadata);
        texts.put(id, text);
    }

    /** Stores a creator's past post so agents can match their voice (persisted per creator). */
    public synchronized void addVoiceSample(String creator, String post) {
        String clean = post == null ? "" : post.strip();
        if (clean.isEmpty()) return;
        List<String> samples = new ArrayList<>(voiceSamples(creator));
        if (samples.contains(clean)) return;
        samples.add(clean);
        try {
            Files.createDirectories(voicesDir);
            JSON.writerWithDefaultPrettyPrinter().writeValue(voiceFile(creator).toFile(), samples);
        } catch (IOException e) {
            log.warning("Could not persist voice sample: " + e.getMessage());
        }
        index("voice", creator, "@" + creator + " past post", clean);
    }

    public List<String> voiceSamples(String creator) {
        Path file = voiceFile(creator);
        if (!Files.exists(file)) return List.of();
        try {
            return JSON.readValue(file.toFile(), new TypeReference<List<String>>() { });
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Indexes a creator's persisted voice samples once per JVM. */
    public synchronized void ensureCreatorIndexed(String creator) {
        String marker = "indexed:" + creator;
        if (texts.containsKey(marker)) return;
        texts.put(marker, "");
        for (String post : voiceSamples(creator)) {
            index("voice", creator, "@" + creator + " past post", post);
        }
    }

    public List<Hit> search(String query, int topK, Map<String, Object> filters) {
        float[] vector = embeddings.embed(query);
        List<Hit> hits = new ArrayList<>();
        var results = store instanceof InMemoryVectorStore mem
                ? mem.search(vector, topK, filters)
                : store.search(vector, topK * 4).stream()
                        .filter(r -> filters == null || filters.entrySet().stream()
                                .allMatch(f -> f.getValue().equals(r.getMetadata().get(f.getKey()))))
                        .limit(topK)
                        .toList();
        for (var result : results) {
            String text = texts.get(result.getId());
            if (text == null) continue;
            hits.add(new Hit(text, String.valueOf(result.getMetadata().get("source")), result.getSimilarity()));
        }
        return hits;
    }

    private Path voiceFile(String creator) {
        return voicesDir.resolve(creator.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }
}
