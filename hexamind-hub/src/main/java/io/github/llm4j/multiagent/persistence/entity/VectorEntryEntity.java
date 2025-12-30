package io.github.llm4j.multiagent.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "vector_entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long internalId;

    private String sessionId;
    private String entryId;

    @Lob
    @Column(name = "embedding")
    private float[] embedding;

    @ElementCollection
    @CollectionTable(name = "vector_entry_metadata", joinColumns = @JoinColumn(name = "vector_internal_id"))
    @MapKeyColumn(name = "meta_key")
    @Column(name = "meta_value")
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
}
