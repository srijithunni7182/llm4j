package io.github.llm4j.tantrik.console.model;

/**
 * Describes a single node in the Engram knowledge graph, as returned by
 * {@code GET /api/engram/nodes}.
 *
 * <p>The {@code content} field is truncated to 200 characters by the service
 * layer before this record is constructed; the record itself imposes no
 * truncation.
 *
 * @param id         unique identifier of the MemoryObject
 * @param content    node content, truncated to 200 characters by the service layer
 * @param tier       memory tier — one of {@code WORKING}, {@code EPISODIC}, or {@code SEMANTIC}
 * @param importance relevance score in the range [0.0, 1.0]
 * @param topicKey   topic or category label associated with the node
 */
public record EngramNodeDescriptor(
        String id,
        String content,
        String tier,
        double importance,
        String topicKey
) {}
