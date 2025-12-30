package io.github.llm4j.multiagent.persistence.repository;

import io.github.llm4j.multiagent.persistence.entity.VectorEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VectorEntryRepository extends JpaRepository<VectorEntryEntity, Long> {
    List<VectorEntryEntity> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
