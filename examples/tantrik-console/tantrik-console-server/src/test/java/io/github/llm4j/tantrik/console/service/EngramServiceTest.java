package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.engram.core.EngramEngine;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.tantrik.console.model.EngramNodeDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EngramService}.
 *
 * <p>Uses a real {@link EngramEngine} (in-memory, no LLM) so that the full
 * service behaviour — including the secondary index and the underlying
 * {@code VectorStore.removeByContent} call — is exercised without mocking.
 *
 * <p>Requirements: 6.1, 6.3
 */
class EngramServiceTest {

    private static final int TRUNCATION_LENGTH = 200;

    private EngramEngine engramEngine;
    private EngramService engramService;

    @BeforeEach
    void setUp() {
        engramEngine = new EngramEngine();          // in-memory, no persistence
        engramService = new EngramService(engramEngine);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link MemoryObject} with a zero-length embedding (sufficient
     * for index-only tests that never call {@code scoreCandidates}).
     */
    private MemoryObject memoryObject(String content, MemoryTier tier,
                                      double importance, String topicKey) {
        return new MemoryObject(content, new float[0], tier, importance, topicKey);
    }

    // -------------------------------------------------------------------------
    // listNodes — content truncation (Requirement 6.1)
    // -------------------------------------------------------------------------

    @Test
    void listNodes_truncatesContentTo200Chars_whenContentExceedsLimit() {
        // Build a content string that is clearly longer than 200 characters
        String longContent = "A".repeat(300);
        MemoryObject memory = memoryObject(longContent, MemoryTier.WORKING, 0.8, "topic-a");
        engramService.register(memory);

        List<EngramNodeDescriptor> nodes = engramService.listNodes();

        assertThat(nodes).hasSize(1);
        EngramNodeDescriptor descriptor = nodes.get(0);
        assertThat(descriptor.content()).hasSize(TRUNCATION_LENGTH);
        assertThat(descriptor.content()).isEqualTo(longContent.substring(0, TRUNCATION_LENGTH));
    }

    @Test
    void listNodes_doesNotTruncateContent_whenContentIsExactly200Chars() {
        String exactContent = "B".repeat(200);
        engramService.register(memoryObject(exactContent, MemoryTier.EPISODIC, 0.5, "topic-b"));

        List<EngramNodeDescriptor> nodes = engramService.listNodes();

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).content()).isEqualTo(exactContent);
        assertThat(nodes.get(0).content()).hasSize(200);
    }

    @Test
    void listNodes_doesNotTruncateContent_whenContentIsShorterThan200Chars() {
        String shortContent = "Short content";
        engramService.register(memoryObject(shortContent, MemoryTier.SEMANTIC, 0.3, "topic-c"));

        List<EngramNodeDescriptor> nodes = engramService.listNodes();

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).content()).isEqualTo(shortContent);
    }

    @Test
    void listNodes_filtersShadowNodes() {
        MemoryObject visible = memoryObject("visible node", MemoryTier.WORKING, 0.7, "visible");
        MemoryObject shadow = memoryObject("shadow node", MemoryTier.WORKING, 0.7, "shadow");
        shadow.setShadow(true);

        engramService.register(visible);
        engramService.register(shadow);

        List<EngramNodeDescriptor> nodes = engramService.listNodes();

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).content()).isEqualTo("visible node");
    }

    @Test
    void listNodes_returnsEmptyList_whenNoNodesRegistered() {
        assertThat(engramService.listNodes()).isEmpty();
    }

    @Test
    void listNodes_mapsAllDescriptorFields() {
        String content = "Some content";
        engramService.register(memoryObject(content, MemoryTier.SEMANTIC, 0.9, "my-topic"));

        List<EngramNodeDescriptor> nodes = engramService.listNodes();

        assertThat(nodes).hasSize(1);
        EngramNodeDescriptor d = nodes.get(0);
        assertThat(d.id()).isNotBlank();
        assertThat(d.content()).isEqualTo(content);
        assertThat(d.tier()).isEqualTo("SEMANTIC");
        assertThat(d.importance()).isEqualTo(0.9);
        assertThat(d.topicKey()).isEqualTo("my-topic");
    }

    // -------------------------------------------------------------------------
    // getNode — full content, no truncation (Requirement 6.2)
    // -------------------------------------------------------------------------

    @Test
    void getNode_returnsFullContent_withoutTruncation() {
        String longContent = "C".repeat(300);
        MemoryObject memory = memoryObject(longContent, MemoryTier.WORKING, 0.6, "full-topic");
        engramService.register(memory);

        MemoryObject retrieved = engramService.getNode(memory.getId());

        assertThat(retrieved.getContent()).isEqualTo(longContent);
        assertThat(retrieved.getContent()).hasSize(300);
    }

    @Test
    void getNode_throwsNoSuchElementException_whenIdNotFound() {
        assertThatThrownBy(() -> engramService.getNode("non-existent-id"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("non-existent-id");
    }

    // -------------------------------------------------------------------------
    // deleteNode — removes from secondary index (Requirement 6.3)
    // -------------------------------------------------------------------------

    @Test
    void deleteNode_removesNodeFromIndex() {
        MemoryObject memory = memoryObject("delete me", MemoryTier.EPISODIC, 0.4, "delete-topic");
        engramService.register(memory);
        String id = memory.getId();

        // Confirm it's present before deletion
        assertThat(engramService.listNodes()).hasSize(1);

        engramService.deleteNode(id);

        // After deletion, getNode must throw
        assertThatThrownBy(() -> engramService.getNode(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deleteNode_removesOnlyTargetNode_leavingOthersIntact() {
        MemoryObject keep = memoryObject("keep this", MemoryTier.WORKING, 0.5, "keep");
        MemoryObject remove = memoryObject("remove this", MemoryTier.WORKING, 0.5, "remove");
        engramService.register(keep);
        engramService.register(remove);

        engramService.deleteNode(remove.getId());

        List<EngramNodeDescriptor> remaining = engramService.listNodes();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).id()).isEqualTo(keep.getId());
    }

    @Test
    void deleteNode_throwsNoSuchElementException_whenIdNotFound() {
        assertThatThrownBy(() -> engramService.deleteNode("ghost-id"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("ghost-id");
    }

    @Test
    void deleteNode_throwsNoSuchElementException_onDoubleDelete() {
        MemoryObject memory = memoryObject("once only", MemoryTier.SEMANTIC, 0.7, "once");
        engramService.register(memory);
        String id = memory.getId();

        engramService.deleteNode(id);

        assertThatThrownBy(() -> engramService.deleteNode(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -------------------------------------------------------------------------
    // register — validation
    // -------------------------------------------------------------------------

    @Test
    void register_throwsIllegalArgumentException_whenMemoryIsNull() {
        assertThatThrownBy(() -> engramService.register(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory must not be null");
    }
}
