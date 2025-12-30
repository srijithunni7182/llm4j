package io.github.llm4j.multiagent.persistence.repository;

import io.github.llm4j.multiagent.persistence.entity.KnowledgeTripleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeTripleRepository extends JpaRepository<KnowledgeTripleEntity, Long> {
    List<KnowledgeTripleEntity> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
