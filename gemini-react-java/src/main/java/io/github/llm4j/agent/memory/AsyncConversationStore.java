package io.github.llm4j.agent.memory;

import io.github.llm4j.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A wrapper around a {@link ConversationStore} that performs write operations
 * asynchronously.
 * This ensures that adding messages remains fast and non-blocking, delegating
 * I/O to a background thread.
 */
public class AsyncConversationStore implements ConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConversationStore.class);

    private final ConversationStore delegate;
    private final ExecutorService executor;

    /**
     * Creates an AsyncConversationStore with a default single-threaded executor.
     *
     * @param delegate the actual store to perform operations
     */
    public AsyncConversationStore(ConversationStore delegate) {
        this(delegate, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-store-worker");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Creates an AsyncConversationStore with a custom executor.
     *
     * @param delegate the actual store to perform operations
     * @param executor the executor to use for async operations
     */
    public AsyncConversationStore(ConversationStore delegate, ExecutorService executor) {
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public void saveMessage(String sessionId, Message message) {
        executor.submit(() -> {
            try {
                delegate.saveMessage(sessionId, message);
            } catch (Exception e) {
                logger.error("Failed to asynchronously save message for session: " + sessionId, e);
            }
        });
    }

    @Override
    public List<Message> loadHistory(String sessionId, int limit) {
        // Loading must be synchronous to ensure we have the data before ensuring
        return delegate.loadHistory(sessionId, limit);
    }

    @Override
    public void clear(String sessionId) {
        executor.submit(() -> {
            try {
                delegate.clear(sessionId);
            } catch (Exception e) {
                logger.error("Failed to asynchronously clear session: " + sessionId, e);
            }
        });
    }

    @Override
    public void deleteSession(String sessionId) {
        executor.submit(() -> {
            try {
                delegate.deleteSession(sessionId);
            } catch (Exception e) {
                logger.error("Failed to asynchronously delete session: " + sessionId, e);
            }
        });
    }

    @Override
    public void updateSummary(String sessionId, String summary) {
        executor.submit(() -> {
            try {
                delegate.updateSummary(sessionId, summary);
            } catch (Exception e) {
                logger.error("Failed to asynchronously update summary for session: " + sessionId, e);
            }
        });
    }

    @Override
    public ConversationMetadata getMetadata(String sessionId) {
        // Must be sync
        return delegate.getMetadata(sessionId);
    }

    @Override
    public List<ConversationMetadata> listSessions() {
        return delegate.listSessions();
    }

    /**
     * Shuts down the background executor.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
