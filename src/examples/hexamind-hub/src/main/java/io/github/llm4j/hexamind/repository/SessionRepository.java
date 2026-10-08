package io.github.llm4j.hexamind.repository;

import io.github.llm4j.hexamind.model.Session;
import io.github.llm4j.hexamind.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {
    List<Session> findByUserOrderByCreatedAtDesc(User user);

    Optional<Session> findBySessionId(String sessionId);
}
