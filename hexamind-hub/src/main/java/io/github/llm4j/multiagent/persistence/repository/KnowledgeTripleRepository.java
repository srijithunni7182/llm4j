package io.github.llm4j.multiagent.persistence.repository;

import io.github.llm4j.multiagent.persistence.entity.KnowledgeTripleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeTripleRepository extends JpaRepository<KnowledgeTripleEntity, Long> {

    @org.springframework.data.jpa.repository.Query("SELECT k.subject.entityId FROM KnowledgeTripleEntity k GROUP BY k.subject.entityId ORDER BY COUNT(k) DESC")
    List<String> findTopConcepts(org.springframework.data.domain.Pageable pageable);

    List<KnowledgeTripleEntity> findBySessionId(String sessionId);

    int countBySessionIdIn(List<String> sessionIds);

    void deleteBySessionId(String sessionId);
}
