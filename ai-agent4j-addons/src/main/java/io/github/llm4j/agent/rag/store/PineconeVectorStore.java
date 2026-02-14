package io.github.llm4j.agent.rag.store;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Vector store implementation using Pinecone (managed cloud service).
 * Requires 'pinecone-client' dependency.
 */
public class PineconeVectorStore implements VectorStore {

    private final Index index;
    private final String namespace;

    /**
     * Creates a new PineconeVectorStore.
     *
     * @param apiKey    Pinecone API Key
     * @param indexName Name of the index to use
     * @param namespace Optional namespace (can be null or empty for default)
     */
    public PineconeVectorStore(String apiKey, String indexName, String namespace) {
        Pinecone pinecone = new Pinecone.Builder(apiKey).build();
        this.index = pinecone.getIndexConnection(indexName);
        this.namespace = namespace != null ? namespace : "";
    }

    @Override
    public void add(String id, float[] embedding, Map<String, Object> metadata) {
        List<Float> values = toFloatList(embedding);
        Struct metadataStruct = mapToStruct(metadata);

        // upsert(id, values, sparseIndices, sparseValues, metadata, namespace)
        index.upsert(id, values, null, null, metadataStruct, namespace);
    }

    @Override
    public void addBatch(List<VectorEntry> entries) {
        // Falling back to iterative upsert since batch API requires internal
        // VectorWithUnsignedIndices
        // This is safe but slower. Future generic batch optimization can use the bulk
        // method if class is accessible.
        for (VectorEntry entry : entries) {
            add(entry.getId(), entry.getEmbedding(), entry.getMetadata());
        }
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters) {
        // Filters are passed as Struct to 'filter' arg
        Struct filterStruct = filters != null && !filters.isEmpty() ? mapToStruct(filters) : null;
        return executeSearch(queryEmbedding, topK, filterStruct);
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        return executeSearch(queryEmbedding, topK, null);
    }

    private List<SearchResult> executeSearch(float[] queryEmbedding, int topK, Struct filter) {
        List<Float> values = toFloatList(queryEmbedding);

        // query(topK, vector, sparseIndices, sparseValues, id, namespace, filter,
        // includeValues, includeMetadata)
        // Note: 'id' is for query-by-id, we use vector query so it is null.
        var response = index.query(topK, values, null, null, null, namespace, filter, false, true);

        // Response is likely a wrapper that exposes getMatches() directly or returns
        // query response proto.
        // Assuming it matches the response type which typically has getMatchesList()
        // (if proto) or getMatches() (if POJO).
        // If this fails compile, I will check the error for return type of
        // index.query().
        return response.getMatchesList().stream()
                .map(match -> new SearchResult(
                        match.getId(),
                        match.getScore(),
                        structToMap(match.getMetadata())))
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String id) {
        // delete(ids, deleteAll, namespace, filter)
        index.delete(Collections.singletonList(id), false, namespace, null);
        return true;
    }

    @Override
    public int size() {
        return -1; // Not supported efficiently
    }

    @Override
    public void clear() {
        // Delete all in namespace
        index.delete(null, true, namespace, null);
    }

    // --- Helper Methods ---

    private List<Float> toFloatList(float[] embedding) {
        List<Float> values = new ArrayList<>(embedding.length);
        for (float f : embedding) {
            values.add(f);
        }
        return values;
    }

    private Struct mapToStruct(Map<String, Object> map) {
        Struct.Builder builder = Struct.newBuilder();
        if (map != null) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                builder.putFields(entry.getKey(), objectToValue(entry.getValue()));
            }
        }
        return builder.build();
    }

    private Value objectToValue(Object value) {
        Value.Builder builder = Value.newBuilder();
        if (value instanceof String) {
            builder.setStringValue((String) value);
        } else if (value instanceof Number) {
            builder.setNumberValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            builder.setBoolValue((Boolean) value);
        } else {
            builder.setStringValue(String.valueOf(value)); // Fallback
        }
        return builder.build();
    }

    private Map<String, Object> structToMap(Struct struct) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), valueToObject(entry.getValue()));
        }
        return map;
    }

    private Object valueToObject(Value value) {
        switch (value.getKindCase()) {
            case STRING_VALUE:
                return value.getStringValue();
            case NUMBER_VALUE:
                return value.getNumberValue();
            case BOOL_VALUE:
                return value.getBoolValue();
            default:
                return value.toString();
        }
    }
}
