package io.github.llm4j.agent.rag.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A persistent vector store that saves data to a JSON file. Extends {@link InMemoryVectorStore} for
 * in-memory search performance while ensuring durability. Valid for small-to-medium datasets.
 */
public class FileVectorStore extends InMemoryVectorStore {

    private final File file;
    private final ObjectMapper objectMapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public FileVectorStore(File file) {
        super();
        this.file = file;
        this.objectMapper = new ObjectMapper();
        load();
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (file.exists() && file.length() > 0) {
                List<SerializationEntry> entries =
                        objectMapper.readValue(
                                file, new TypeReference<List<SerializationEntry>>() {});
                for (SerializationEntry entry : entries) {
                    super.add(entry.id, entry.embedding, entry.metadata);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to load vector store from file: " + file.getAbsolutePath(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        lock.writeLock().lock();
        try {
            // Convert to simple serialization objects
            List<SerializationEntry> entries =
                    super.getAllEntries().stream()
                            .map(
                                    e ->
                                            new SerializationEntry(
                                                    e.getId(), e.getEmbedding(), e.getMetadata()))
                            .toList();

            // Ensure parent directory exists
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    throw new IOException(
                            "Failed to create directory: " + parent.getAbsolutePath());
                }
            }

            objectMapper.writeValue(file, entries);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save vector store to file: " + file.getAbsolutePath(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void add(String id, float[] embedding, Map<String, Object> metadata) {
        lock.writeLock().lock();
        try {
            super.add(id, embedding, metadata);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void addBatch(List<VectorEntry> entries) {
        lock.writeLock().lock();
        try {
            super.addBatch(entries);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(String id) {
        lock.writeLock().lock();
        try {
            boolean deleted = super.delete(id);
            if (deleted) {
                save();
            }
            return deleted;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            super.clear();
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Creating search method override just for read lock safety, though underlying
    // map is concurrent.
    // However, since we are doing file I/O on writes, locking properly is good
    // practice to ensure consistent snapshots if we expanded logic.
    // For now, ConcurrentHashMap in parent handles read concurrency well enough,
    // but let's be safe.
    @Override
    public List<SearchResult> search(
            float[] queryEmbedding, int topK, Map<String, Object> filters) {
        lock.readLock().lock();
        try {
            return super.search(queryEmbedding, topK, filters);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * DTO for JSON serialization to avoid dragging strict VectorEntry logic/deps into JSON if
     * schema changes.
     */
    private static class SerializationEntry {
        public String id;
        public float[] embedding;
        public Map<String, Object> metadata;

        // Default constructor for Jackson
        public SerializationEntry() {}

        public SerializationEntry(String id, float[] embedding, Map<String, Object> metadata) {
            this.id = id;
            this.embedding = embedding;
            this.metadata = metadata;
        }
    }
}
