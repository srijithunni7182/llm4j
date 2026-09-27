package io.github.llm4j.loom.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.rag.document.Document;
import io.github.llm4j.agent.rag.document.DocumentChunk;
import io.github.llm4j.agent.rag.document.FixedSizeChunkingStrategy;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.FileVectorStore;
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.loom.ast.KnowledgeDef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns a knowledge base's source files into a searchable index: reads text files, chunks them, embeds the
 * chunks and stores them. With a file store, a manifest of file hashes makes later loads incremental: only
 * changed files are re-embedded, removed files are dropped, and a change of embedding model or chunking
 * rebuilds everything.
 */
public final class KnowledgeIndexer {

    /** File types read in P1 (UTF-8 text). */
    public static final Set<String> TEXT = Set.of("md", "markdown", "txt", "html", "htm", "json", "csv");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 64;

    private KnowledgeIndexer() { }

    public static Path source(KnowledgeDef def, Path baseDir) {
        Path p = Path.of(def.getSource());
        return p.isAbsolute() ? p : baseDir.resolve(p).normalize();
    }

    /** Indexable files under the source, sorted, and how many others were skipped. */
    static List<Path> files(Path source, int[] skipped) throws IOException {
        if (Files.isRegularFile(source)) {
            if (indexable(source)) return List.of(source);
            skipped[0]++;
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> all = Files.walk(source)) {
            for (Path p : all.filter(Files::isRegularFile).sorted().toList()) {
                if (indexable(p)) out.add(p);
                else skipped[0]++;
            }
        }
        return out;
    }

    static boolean indexable(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && TEXT.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String text(Path p) throws IOException {
        String raw = Files.readString(p, StandardCharsets.UTF_8);
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) {
            raw = raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?s)<[^>]+>", " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").strip();
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    public static KnowledgeIndex index(KnowledgeDef def, EmbeddingProvider embeddings, Path baseDir) throws IOException {
        Path source = source(def, baseDir);
        int[] skipped = {0};
        List<Path> files = files(source, skipped);
        Path root = Files.isDirectory(source) ? source : source.getParent();

        VectorStore store;
        Path manifestFile = null;
        Map<String, Object> manifest = new LinkedHashMap<>();
        if (def.getStore() == null) {
            store = new InMemoryVectorStore();
        } else {
            Path storeFile = Path.of(def.getStore()).isAbsolute() ? Path.of(def.getStore()) : baseDir.resolve(def.getStore());
            Files.createDirectories(storeFile.toAbsolutePath().getParent());
            store = new FileVectorStore(storeFile.toFile());
            manifestFile = storeFile.resolveSibling(storeFile.getFileName() + ".manifest.json");
            if (Files.exists(manifestFile)) manifest = JSON.readValue(manifestFile.toFile(), Map.class);
        }

        boolean sameSettings = def.getEmbeddingProvider().equals(manifest.get("embedding"))
                && Integer.valueOf(def.getChunkSize()).equals(manifest.get("chunkSize"))
                && Integer.valueOf(def.getOverlap()).equals(manifest.get("overlap"));
        Map<String, Map<String, Object>> known = sameSettings && manifest.get("files") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Map<String, Object>>) m) : new LinkedHashMap<>();
        boolean rebuilt = !sameSettings && store.size() > 0;
        if (!sameSettings) store.clear();

        FixedSizeChunkingStrategy chunker = new FixedSizeChunkingStrategy(def.getChunkSize(), def.getOverlap());
        Map<String, Map<String, Object>> current = new LinkedHashMap<>();
        List<String> pendingIds = new ArrayList<>();
        List<String> pendingText = new ArrayList<>();
        List<Map<String, Object>> pendingMeta = new ArrayList<>();
        int chunks = 0;
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String text = text(file);
            String hash = sha256(text);
            Map<String, Object> was = known.remove(rel);
            if (was != null && hash.equals(was.get("sha256"))) {
                current.put(rel, was);
                chunks += ((List<?>) was.get("chunks")).size();
                continue;
            }
            if (was != null) for (Object id : (List<?>) was.get("chunks")) store.delete(String.valueOf(id));
            List<String> ids = new ArrayList<>();
            if (!text.isBlank()) {
                List<DocumentChunk> parts = chunker.chunk(Document.builder().id(rel).content(text).build());
                for (int i = 0; i < parts.size(); i++) {
                    String id = rel + "#" + i;
                    ids.add(id);
                    pendingIds.add(id);
                    pendingText.add(parts.get(i).getContent());
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("source", rel);
                    meta.put("content", parts.get(i).getContent());
                    pendingMeta.add(meta);
                }
            }
            chunks += ids.size();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sha256", hash);
            entry.put("chunks", ids);
            current.put(rel, entry);
        }
        // files that disappeared
        for (Map<String, Object> gone : known.values()) {
            for (Object id : (List<?>) gone.get("chunks")) store.delete(String.valueOf(id));
        }
        for (int from = 0; from < pendingText.size(); from += BATCH) {
            int to = Math.min(pendingText.size(), from + BATCH);
            List<float[]> vectors = embeddings.embedBatch(pendingText.subList(from, to));
            List<VectorStore.VectorEntry> entries = new ArrayList<>();
            for (int i = from; i < to; i++) {
                entries.add(new VectorStore.VectorEntry(pendingIds.get(i), vectors.get(i - from), pendingMeta.get(i)));
            }
            store.addBatch(entries);
        }
        if (manifestFile != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("embedding", def.getEmbeddingProvider());
            out.put("chunkSize", def.getChunkSize());
            out.put("overlap", def.getOverlap());
            out.put("files", current);
            JSON.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), out);
        }
        return new KnowledgeIndex(def, embeddings, store,
                new KnowledgeIndex.Stats(files.size(), skipped[0], chunks, pendingText.size(), rebuilt));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
