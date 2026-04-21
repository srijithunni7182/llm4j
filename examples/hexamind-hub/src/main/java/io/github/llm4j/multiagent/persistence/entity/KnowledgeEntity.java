package io.github.llm4j.multiagent.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "knowledge_entities")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long internalId;

    private String sessionId;
    private String entityId;
    private String type;

    @ElementCollection
    @CollectionTable(name = "knowledge_entity_properties", joinColumns = @JoinColumn(name = "entity_internal_id"))
    @MapKeyColumn(name = "property_key")
    @Column(name = "property_value")
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();
}
