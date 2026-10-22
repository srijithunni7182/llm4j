package io.github.llm4j.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.llm4j.agent.ToolException;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.ratelimit.RateLimited;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Why a model call or a tool call failed, in one vocabulary that is the same for every provider and
 * every tool, so failures can be grouped and compared. Treat the set as extensible: code that reads
 * a category should tolerate values added later.
 */
public enum ErrorCategory {
    /**
     * The arguments or the request were wrong (bad JSON, a missing field, a rejected request body).
     */
    INVALID_ARGUMENTS,
    /** It took too long. */
    TIMEOUT,
    /** The connection failed or broke. */
    NETWORK,
    /** The provider or service said to slow down. */
    RATE_LIMITED,
    /** The credentials were refused. */
    AUTHENTICATION,
    /** The caller is not allowed to do this. */
    PERMISSION_DENIED,
    /** What was asked for does not exist. */
    NOT_FOUND,
    /** Another 4xx: the request was refused for a reason of its own. */
    CLIENT_ERROR,
    /** The other side failed (5xx). */
    SERVER_ERROR,
    /** A safety system refused to answer. */
    CONTENT_BLOCKED,
    /** The prompt did not fit the model's context window. */
    CONTEXT_OVERFLOW,
    /** A budget refused the call. */
    BUDGET,
    /** A person rejected the action. */
    REJECTED_BY_HUMAN,
    /** The agent refused to repeat an identical action. */
    REPEAT_BLOCKED,
    /** The model named a tool that does not exist. */
    UNKNOWN_TOOL,
    /** The action needs approval and no way to ask was set up. */
    APPROVAL_UNAVAILABLE,
    /** None of the above, or not known. */
    OTHER;

    private static final int MAX_CHAIN = 5;

    /**
     * The category of an exception: its own if it says so, else by type and status, else by its
     * cause, to a depth of 5; {@link #OTHER} if nothing fits.
     */
    public static ErrorCategory of(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_CHAIN && seen.add(current); depth++) {
            ErrorCategory c = direct(current);
            if (c != null) return c;
            current = current.getCause();
        }
        return OTHER;
    }

    /** The category an HTTP status stands for. */
    public static ErrorCategory ofStatus(int status) {
        return switch (status) {
            case 404 -> NOT_FOUND;
            case 408 -> TIMEOUT;
            case 429 -> RATE_LIMITED;
            case 401, 403 -> AUTHENTICATION;
            case 400, 413, 422 -> INVALID_ARGUMENTS;
            default -> status >= 500 && status < 600
                    ? SERVER_ERROR
                    : status >= 400 && status < 500 ? CLIENT_ERROR : OTHER;
        };
    }

    /**
     * What this exception says about itself, or null when it says nothing (so that its cause is
     * asked).
     */
    private static ErrorCategory direct(Throwable t) {
        if (t instanceof ToolException e) return e.category();
        if (t instanceof ContextOverflowException)
            return CONTEXT_OVERFLOW; // before InvalidRequestException, which it extends
        if (t instanceof RateLimitException || t instanceof RateLimited) return RATE_LIMITED;
        if (t instanceof BudgetExceeded) return BUDGET;
        if (t instanceof AuthenticationException) return AUTHENTICATION;
        if (t instanceof ContentBlockedException) return CONTENT_BLOCKED;
        if (t instanceof InvalidRequestException e)
            return e.getStatusCode() == null ? INVALID_ARGUMENTS : ofStatus(e.getStatusCode());
        if (t instanceof ServiceUnavailableException) return SERVER_ERROR;
        if (t instanceof LLMException e)
            return e.getStatusCode() == null
                    ? null
                    : orNull(ofStatus(e.getStatusCode())); // ProviderException is one
        if (t instanceof SocketTimeoutException
                || t instanceof TimeoutException
                || t instanceof java.net.http.HttpTimeoutException) return TIMEOUT;
        if (t instanceof InterruptedIOException
                && t.getMessage() != null
                && t.getMessage().toLowerCase(Locale.ROOT).contains("timed out")) return TIMEOUT;
        if (t instanceof FileNotFoundException || t instanceof NoSuchFileException)
            return NOT_FOUND;
        if (t instanceof AccessDeniedException || t instanceof SecurityException)
            return PERMISSION_DENIED;
        if (t instanceof JsonProcessingException)
            return INVALID_ARGUMENTS; // an IOException too, but the bytes arrived: the text is what
        // is wrong
        if (t instanceof IOException) return NETWORK;
        if (t instanceof IllegalArgumentException || t instanceof NullPointerException)
            return INVALID_ARGUMENTS;
        return null;
    }

    private static ErrorCategory orNull(ErrorCategory c) {
        return c == OTHER ? null : c;
    }
}
