package io.github.llm4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps every call to a consumer's {@link AgentChannel} and enforces, whatever the channel does:
 * the question limit (across retries, because one guard serves one run and step), the timeout, the
 * size of replies and the validation of a structured reply. A channel that throws or returns
 * nothing fails the exchange; a response that does not fit the message is a failure and is never
 * dropped. A bound that depended on the channel's own code would not be a bound.
 */
public final class ExchangeGuard {

    /** Runs a channel call under a deadline. The runtime owns this, not the channel. */
    @FunctionalInterface
    public interface Waiter {
        /**
         * Runs the call and returns its result.
         *
         * @param call the channel call
         * @param timeout the longest to wait
         * @throws TimeoutException when the deadline passes
         * @throws Exception when the call fails
         */
        ChannelResponse call(Callable<ChannelResponse> call, Duration timeout) throws Exception;

        /**
         * Runs the call on the calling thread with no deadline; for tests and callers that enforce
         * one themselves.
         */
        static Waiter direct() {
            return (call, timeout) -> call.call();
        }

        /** Runs the call on the consumer's executor and waits for at most the timeout. */
        static Waiter onExecutor(Executor executor) {
            Objects.requireNonNull(executor, "executor");
            return (call, timeout) -> {
                CompletableFuture<ChannelResponse> future =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return call.call();
                                    } catch (RuntimeException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                },
                                executor);
                try {
                    return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw e;
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
            };
        }
    }

    /** Checks a structured reply against the shape the question expects. */
    @FunctionalInterface
    public interface ReplyValidator {
        /**
         * Returns why the reply is not acceptable, or empty if it is.
         *
         * @param reply the reply
         */
        Optional<String> reject(ChannelResponse.Reply reply);
    }

    private final int maxQuestions;
    private final Duration timeout;
    private final int maxReplyBytes;
    private final Waiter waiter;
    private int used;

    /**
     * Creates a guard for one run and step.
     *
     * @param maxQuestions questions allowed in all, retries included
     * @param timeout the longest one channel call may take
     * @param maxReplyBytes the largest reply accepted
     * @param waiter runs a call under the deadline
     */
    public ExchangeGuard(int maxQuestions, Duration timeout, int maxReplyBytes, Waiter waiter) {
        if (maxQuestions < 1) {
            throw new IllegalArgumentException("maxQuestions must be 1 or more");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxReplyBytes < 1) {
            throw new IllegalArgumentException("maxReplyBytes must be 1 or more");
        }
        this.maxQuestions = maxQuestions;
        this.maxReplyBytes = maxReplyBytes;
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    /** Questions used so far. */
    public synchronized int questionsUsed() {
        return used;
    }

    /**
     * Hands a message to the channel under the bounds and returns its response.
     *
     * <p>A question or authorization request uses one question; an invalid reply is sent back with
     * the reason and uses another. A file offer uses none.
     *
     * @throws A2AException.ChannelFailure when a bound is exceeded or the channel misbehaves
     */
    public ChannelResponse exchange(
            AgentChannel channel,
            ChannelContext context,
            ChannelMessage message,
            ReplyValidator validator) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(validator, "validator");
        ChannelMessage current = Objects.requireNonNull(message, "message");
        while (true) {
            boolean countsAsQuestion =
                    current instanceof ChannelMessage.Question
                            || current instanceof ChannelMessage.AuthorizationNeeded;
            int left = reserve(countsAsQuestion);
            ChannelResponse response = call(channel, context.withQuestionsLeft(left), current);
            check(current, response);
            if (response instanceof ChannelResponse.Reply reply) {
                requireSize(reply);
                if (current instanceof ChannelMessage.Question q) {
                    Optional<String> why = validator.reject(reply);
                    if (why.isPresent()) {
                        current =
                                new ChannelMessage.Question(
                                        q.text(),
                                        q.data(),
                                        q.expecting(),
                                        q.attempt() + 1,
                                        why.get());
                        continue;
                    }
                }
            }
            return response;
        }
    }

    private synchronized int reserve(boolean countsAsQuestion) {
        int left = maxQuestions - used;
        if (countsAsQuestion) {
            if (left <= 0) {
                throw new A2AException.ChannelFailure(
                        "the question limit of "
                                + maxQuestions
                                + " is spent; raise max_questions or fix the channel");
            }
            used++;
        }
        return left;
    }

    private ChannelResponse call(
            AgentChannel channel, ChannelContext context, ChannelMessage message) {
        ChannelResponse response;
        try {
            response = waiter.call(() -> channel.handle(context, message), timeout);
        } catch (TimeoutException e) {
            throw new A2AException.ChannelFailure(
                    "the channel did not answer within "
                            + timeout
                            + "; answer faster or raise the timeout",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2AException.ChannelFailure("the exchange was interrupted", e);
        } catch (Exception e) {
            throw new A2AException.ChannelFailure(
                    "the channel failed with "
                            + e.getClass().getSimpleName()
                            + "; fix the channel implementation",
                    e);
        }
        if (response == null) {
            throw new A2AException.ChannelFailure(
                    "the channel returned nothing; return a response for every message");
        }
        return response;
    }

    private static void check(ChannelMessage message, ChannelResponse response) {
        boolean fits;
        if (message instanceof ChannelMessage.FileOffered) {
            fits =
                    response instanceof ChannelResponse.Received
                            || response instanceof ChannelResponse.Ignore
                            || response instanceof ChannelResponse.Refuse
                            || response instanceof ChannelResponse.Defer;
        } else {
            fits =
                    response instanceof ChannelResponse.Reply
                            || response instanceof ChannelResponse.Refuse
                            || response instanceof ChannelResponse.Defer;
        }
        if (!fits) {
            throw new A2AException.ChannelFailure(
                    "the channel answered a "
                            + message.getClass().getSimpleName()
                            + " with a "
                            + response.getClass().getSimpleName()
                            + ", which does not fit; no message is dropped silently");
        }
    }

    private void requireSize(ChannelResponse.Reply reply) {
        JsonNode data = reply.data();
        long size = reply.text().getBytes(StandardCharsets.UTF_8).length;
        if (data != null) {
            size += data.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        if (size > maxReplyBytes) {
            throw new A2AException.ChannelFailure(
                    "the reply is " + size + " bytes, over the limit of " + maxReplyBytes);
        }
    }
}
