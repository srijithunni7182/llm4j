package io.github.llm4j.multiagent.persistence.repository;

import io.github.llm4j.multiagent.persistence.entity.KnowledgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeRepository extends JpaRepository<KnowledgeEntity, Long> {
    List<KnowledgeEntity> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
