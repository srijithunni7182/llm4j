package io.github.llm4j.getviral.app.runs;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RunRepository extends JpaRepository<RunRecord, String> {

    Optional<RunRecord> findByIdAndUserId(String id, String userId);

    List<RunRecord> findByUserIdOrderByCreatedAtDesc(String userId, Pageable page);

    long countByUserIdAndStatusIn(String userId, Collection<RunStatus> statuses);

    @Query("select count(r) from RunRecord r where r.userId = ?1 and r.createdAt >= ?2 and r.status not in ?3")
    long countSince(String userId, Instant since, Collection<RunStatus> excluded);

    @Query("select r from RunRecord r where r.userId = ?1 and r.status in ?2 order by r.createdAt desc")
    List<RunRecord> findActive(String userId, Collection<RunStatus> statuses);

    @Modifying
    @Transactional
    @Query("update RunRecord r set r.status = ?2, r.lastEventAt = ?3 where r.id = ?1")
    void updateStatus(String id, RunStatus status, Instant at);

    @Modifying
    @Transactional
    @Query("update RunRecord r set r.lastEventAt = ?2 where r.id = ?1")
    void touch(String id, Instant at);

    @Query("select r from RunRecord r where r.status in ?1 and r.lastEventAt < ?2")
    List<RunRecord> findStale(Collection<RunStatus> statuses, Instant before);
}
