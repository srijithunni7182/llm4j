package io.github.llm4j.agent.rag.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.llm4j.agent.rag.store.VectorStore.SearchResult;
import io.pinecone.clients.Index;
import io.pinecone.proto.UpsertResponse;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PineconeVectorStoreTest {

    @Mock private Index mockIndex;

    private PineconeVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new PineconeVectorStore(mockIndex, "test-namespace");
    }

    @Test
    void add_shouldUpsertToPinecone() {
        String id = "vec1";
        float[] embedding = new float[] {0.1f, 0.2f, 0.3f};
        Map<String, Object> metadata = Map.of("key", "value");

        // Use any() for UpsertResponse return type
        when(mockIndex.upsert(anyString(), anyList(), any(), any(), any(), anyString()))
                .thenReturn(UpsertResponse.getDefaultInstance());

        vectorStore.add(id, embedding, metadata);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Float>> valuesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Struct> metadataCaptor = ArgumentCaptor.forClass(Struct.class);

        verify(mockIndex)
                .upsert(
                        idCaptor.capture(),
                        valuesCaptor.capture(),
                        any(),
                        any(),
                        metadataCaptor.capture(),
                        eq("test-namespace"));

        assertThat(idCaptor.getValue()).isEqualTo(id);
        assertThat(valuesCaptor.getValue()).containsExactly(0.1f, 0.2f, 0.3f);

        Struct metadataStruct = metadataCaptor.getValue();
        assertThat(metadataStruct.getFieldsMap()).containsKey("key");
        assertThat(metadataStruct.getFieldsMap().get("key").getStringValue()).isEqualTo("value");
    }

    @Test
    void search_shouldQueryAndReturnResults() {
        float[] queryEmbedding = new float[] {0.1f, 0.2f, 0.3f};

        // Mock query response
        ScoredVectorWithUnsignedIndices match = mock(ScoredVectorWithUnsignedIndices.class);
        when(match.getId()).thenReturn("vec1");
        when(match.getScore()).thenReturn(0.95f);
        when(match.getMetadata())
                .thenReturn(
                        Struct.newBuilder()
                                .putFields(
                                        "text",
                                        Value.newBuilder().setStringValue("content").build())
                                .build());

        QueryResponseWithUnsignedIndices response = mock(QueryResponseWithUnsignedIndices.class);
        when(response.getMatchesList()).thenReturn(Collections.singletonList(match));

        when(mockIndex.query(
                        anyInt(),
                        anyList(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        anyBoolean(),
                        anyBoolean()))
                .thenReturn(response);

        List<SearchResult> results = vectorStore.search(queryEmbedding, 5);

        assertThat(results).hasSize(1);
        SearchResult result = results.get(0);
        assertThat(result.getId()).isEqualTo("vec1");
        assertThat(result.getSimilarity()).isEqualTo(0.95f);
        assertThat(result.getMetadata()).containsEntry("text", "content");

        verify(mockIndex)
                .query(
                        eq(5),
                        anyList(),
                        any(),
                        any(),
                        any(),
                        eq("test-namespace"),
                        any(),
                        eq(false),
                        eq(true));
    }

    @Test
    void delete_shouldDeleteFromIndex() {
        vectorStore.delete("vec1");
        // verify delete(ids, deleteAll, namespace, filter)
        verify(mockIndex)
                .delete(
                        eq(Collections.singletonList("vec1")),
                        eq(false),
                        eq("test-namespace"),
                        any());
    }
}
