package io.github.llm4j.getviral.app.connect;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface SocialConnectionRepository extends JpaRepository<SocialConnection, String> {

    List<SocialConnection> findByUserId(String userId);

    Optional<SocialConnection> findByUserIdAndPlatform(String userId, String platform);

    @Transactional
    long deleteByUserIdAndPlatform(String userId, String platform);
}
