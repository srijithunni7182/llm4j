package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.engram.core.EngramEngine;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.tantrik.console.model.EngramNodeDescriptor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service layer for Engram knowledge-graph operations.
 *
 * <p>The underlying {@link EngramEngine} delegates persistence to a
 * {@link io.github.llm4j.engram.core.VectorStore} whose only removal API is
 * {@code removeByContent(String)}.  To support efficient lookup and deletion by
 * node {@code id} the service maintains a secondary
 * {@code ConcurrentHashMap<String, MemoryObject>} index that is the
 * authoritative source for list / get / delete operations.
 *
 * <p>New nodes are registered in the index via {@link #register(MemoryObject)}.
 * Callers (e.g. the Loom executor) should call this method whenever a
 * {@link MemoryObject} is added to the engine so that the index stays in sync.
 *
 * <p>Requirements: 6.1, 6.2, 6.3
 */
@Service
public class EngramService {

    /** Maximum length of the {@code content} field returned by {@link #listNodes()}. */
    private static final int CONTENT_TRUNCATION_LENGTH = 200;

    private final EngramEngine engramEngine;

    /**
     * Secondary index keyed by {@link MemoryObject#getId()}.
     * This is the authoritative store for list / get / delete operations because
     * {@link io.github.llm4j.engram.core.VectorStore} does not expose a list-all API.
     */
    private final ConcurrentHashMap<String, MemoryObject> index = new ConcurrentHashMap<>();

    public EngramService(EngramEngine engramEngine) {
        this.engramEngine = engramEngine;
    }

    /**
     * Registers a {@link MemoryObject} in the secondary index.
     *
     * <p>Call this whenever a node is added to the {@link EngramEngine} so that
     * the index remains consistent with the underlying store.
     *
     * @param memory the memory object to register; must not be {@code null}
     */
    public void register(MemoryObject memory) {
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        index.put(memory.getId(), memory);
    }

    /**
     * Returns descriptors for all non-shadow nodes in the index.
     *
     * <p>The {@code content} field of each descriptor is truncated to
     * {@value #CONTENT_TRUNCATION_LENGTH} characters.
     *
     * @return an unordered list of {@link EngramNodeDescriptor}; never {@code null}
     */
    public List<EngramNodeDescriptor> listNodes() {
        return index.values().stream()
                .filter(m -> !m.isShadow())
                .map(this::toDescriptor)
                .collect(Collectors.toList());
    }

    /**
     * Returns the full {@link MemoryObject} for the given {@code id}.
     *
     * <p>No content truncation is applied — the caller receives the complete node.
     *
     * @param id the node identifier; must not be {@code null}
     * @return the matching {@link MemoryObject}
     * @throws NoSuchElementException if no node with the given {@code id} exists in the index
     */
    public MemoryObject getNode(String id) {
        MemoryObject memory = index.get(id);
        if (memory == null) {
            throw new NoSuchElementException("Engram node not found: " + id);
        }
        return memory;
    }

    /**
     * Removes the node with the given {@code id} from both the secondary index
     * and the underlying {@link EngramEngine} store.
     *
     * <p>Removal from the engine store is performed via
     * {@code VectorStore.removeByContent(String)} because that is the only
     * removal API exposed by the store interface.
     *
     * @param id the node identifier; must not be {@code null}
     * @throws NoSuchElementException if no node with the given {@code id} exists in the index
     */
    public void deleteNode(String id) {
        MemoryObject memory = index.remove(id);
        if (memory == null) {
            throw new NoSuchElementException("Engram node not found: " + id);
        }
        // Remove from the underlying VectorStore by content (the only available API)
        engramEngine.getStore().removeByContent(memory.getContent());
    }

    /**
     * Returns the underlying {@link EngramEngine} for use by other services
     * (e.g. {@code TantrikRunService}) that need to call
     * {@link EngramEngine#storeOutcome} or {@link EngramEngine#assembleContext}.
     *
     * @return the shared {@link EngramEngine} bean
     */
    public EngramEngine getEngramEngine() {
        return engramEngine;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private EngramNodeDescriptor toDescriptor(MemoryObject memory) {
        String content = memory.getContent();
        if (content != null && content.length() > CONTENT_TRUNCATION_LENGTH) {
            content = content.substring(0, CONTENT_TRUNCATION_LENGTH);
        }
        String tier = memory.getTier() != null ? memory.getTier().name() : null;
        return new EngramNodeDescriptor(
                memory.getId(),
                content,
                tier,
                memory.getImportance(),
                memory.getTopicKey()
        );
    }
}
