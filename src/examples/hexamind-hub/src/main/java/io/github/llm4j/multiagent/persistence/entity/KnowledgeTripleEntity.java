package io.github.llm4j.multiagent.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "knowledge_triples")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTripleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long internalId;

    private String sessionId;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "subject_id")
    private KnowledgeEntity subject;

    private String predicate;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "object_id")
    private KnowledgeEntity object;
}
