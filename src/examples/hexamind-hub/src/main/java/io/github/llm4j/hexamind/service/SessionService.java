package io.github.llm4j.hexamind.service;

import io.github.llm4j.hexamind.model.Session;
import io.github.llm4j.hexamind.model.Turn;
import io.github.llm4j.hexamind.model.User;
import io.github.llm4j.hexamind.repository.SessionRepository;
import io.github.llm4j.hexamind.repository.TurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final TurnRepository turnRepository;

    @Transactional
    public Session createSession(User user, String sessionId, String topic) {
        Session session = Session.builder()
                .user(user)
                .sessionId(sessionId)
                .topic(topic)
                .build();
        return sessionRepository.save(session);
    }

    public List<Session> getSessionsByUser(User user) {
        return sessionRepository.findByUserOrderByCreatedAtDesc(user);
    }

    public Optional<Session> getSessionById(Long id) {
        return sessionRepository.findById(id);
    }

    public Optional<Session> getSessionBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    @Transactional
    public Turn saveTurn(String sessionId, String speaker, String content) {
        Session session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        Turn turn = Turn.builder()
                .session(session)
                .speaker(speaker)
                .content(content)
                .build();
        return turnRepository.save(turn);
    }

    @Transactional
    public void updateSessionState(String sessionId, String status, int currentRound, String consensusRecommendation,
            Double agreementScore) {
        Session session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        session.setStatus(status);
        session.setCurrentRound(currentRound);
        if (consensusRecommendation != null) {
            session.setConsensusRecommendation(consensusRecommendation);
        }
        if (agreementScore != null) {
            session.setAgreementScore(agreementScore);
        }
        sessionRepository.save(session);
    }
}
