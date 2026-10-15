package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.llm4j.agent.ToolException;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.ratelimit.RateLimited;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** One vocabulary for why a model call or a tool call failed (design §4.1, R10). */
class ErrorCategoryTest {

    private static void is(Throwable t, ErrorCategory expected) {
        assertThat(ErrorCategory.of(t)).as(t.getClass().getSimpleName() + ": " + t.getMessage()).isEqualTo(expected);
        assertThat(ErrorCategory.of(new RuntimeException("wrapped", t))).as("wrapped " + t.getClass().getSimpleName()).isEqualTo(expected);
        assertThat(ErrorCategory.of(new ExecutionException(t))).as("in an ExecutionException " + t.getClass().getSimpleName()).isEqualTo(expected);
    }

    @Test
    void anExplicitCategoryWins() {
        is(new ToolException(ErrorCategory.NOT_FOUND, "no such record"), ErrorCategory.NOT_FOUND);
        // even over what the cause would say
        assertThat(ErrorCategory.of(new ToolException(ErrorCategory.PERMISSION_DENIED, "no", new SocketTimeoutException("slow"))))
                .isEqualTo(ErrorCategory.PERMISSION_DENIED);
    }

    @Test
    void modelSideExceptions() {
        is(new ContextOverflowException("prompt is too long"), ErrorCategory.CONTEXT_OVERFLOW); // not INVALID_ARGUMENTS, though it is an InvalidRequestException
        is(new RateLimitException("slow down"), ErrorCategory.RATE_LIMITED);
        is(new RateLimited(new RateLimitException("x").infoOrEstimate(Instant.now(), Duration.ofSeconds(30)), RateLimited.Reason.PROVIDER_LIMIT), ErrorCategory.RATE_LIMITED);
        is(new AuthenticationException("bad key"), ErrorCategory.AUTHENTICATION);
        is(new ContentBlockedException("p", "refused"), ErrorCategory.CONTENT_BLOCKED);
        is(new ServiceUnavailableException("p", "overloaded"), ErrorCategory.SERVER_ERROR);
        is(Mockito.mock(BudgetExceeded.class), ErrorCategory.BUDGET);
    }

    @Test
    void aBadRequestIsReadFromItsStatus() {
        is(new InvalidRequestException("m", 404, "{}"), ErrorCategory.NOT_FOUND);
        is(new InvalidRequestException("m", 400, "{}"), ErrorCategory.INVALID_ARGUMENTS);
        is(new InvalidRequestException("m", 413, "{}"), ErrorCategory.INVALID_ARGUMENTS);
        is(new InvalidRequestException("m", 422, "{}"), ErrorCategory.INVALID_ARGUMENTS);
        is(new InvalidRequestException("m", 409, "{}"), ErrorCategory.CLIENT_ERROR);
        is(new InvalidRequestException("m"), ErrorCategory.INVALID_ARGUMENTS); // the default status is 400
    }

    @Test
    void aProviderErrorIsReadFromItsStatusWhenItHasOne() {
        is(new ProviderException("p", "boom", 500), ErrorCategory.SERVER_ERROR);
        is(new ProviderException("p", "boom", 502), ErrorCategory.SERVER_ERROR);
        is(new ProviderException("p", "gone", 404), ErrorCategory.NOT_FOUND);
        is(new ProviderException("p", "slow", 408), ErrorCategory.TIMEOUT);
        is(new ProviderException("p", "limit", 429), ErrorCategory.RATE_LIMITED);
        is(new ProviderException("p", "who", 401), ErrorCategory.AUTHENTICATION);
        is(new ProviderException("p", "no", 403), ErrorCategory.AUTHENTICATION);
        is(new ProviderException("p", "other", 418), ErrorCategory.CLIENT_ERROR);
        is(new ProviderException("p", "no status"), ErrorCategory.OTHER);
    }

    @Test
    void aPlainLlmExceptionIsReadFromItsStatusOrItsCause() {
        is(new LLMException("m", 503), ErrorCategory.SERVER_ERROR);
        is(new LLMException("m", 429), ErrorCategory.RATE_LIMITED);
        is(new LLMException("m"), ErrorCategory.OTHER);
        is(new LLMException("HTTP request failed", new SocketTimeoutException("Read timed out")), ErrorCategory.TIMEOUT);
        is(new LLMException("HTTP request failed", new ConnectException("refused")), ErrorCategory.NETWORK);
    }

    @Test
    void timeouts() {
        is(new SocketTimeoutException("Read timed out"), ErrorCategory.TIMEOUT);
        is(new TimeoutException("deadline"), ErrorCategory.TIMEOUT);
        is(new java.net.http.HttpTimeoutException("request timed out"), ErrorCategory.TIMEOUT);
        is(new InterruptedIOException("timed out waiting"), ErrorCategory.TIMEOUT);
    }

    @Test
    void networkTrouble() {
        is(new ConnectException("Connection refused"), ErrorCategory.NETWORK);
        is(new UnknownHostException("no.such.host"), ErrorCategory.NETWORK);
        is(new IOException("stream closed"), ErrorCategory.NETWORK);
        is(new java.net.SocketException("reset"), ErrorCategory.NETWORK);
    }

    @Test
    void badArguments() {
        is(new IllegalArgumentException("q must not be empty"), ErrorCategory.INVALID_ARGUMENTS);
        is(new NumberFormatException("For input string: \"x\""), ErrorCategory.INVALID_ARGUMENTS);
        is(new NullPointerException("args.get(q)"), ErrorCategory.INVALID_ARGUMENTS);
        is(new JsonProcessingException("bad json") {}, ErrorCategory.INVALID_ARGUMENTS);
    }

    @Test
    void filesAndPermissions() {
        is(new FileNotFoundException("a.txt"), ErrorCategory.NOT_FOUND);
        is(new NoSuchFileException("a.txt"), ErrorCategory.NOT_FOUND);
        is(new AccessDeniedException("/etc/shadow"), ErrorCategory.PERMISSION_DENIED);
        is(new SecurityException("not allowed"), ErrorCategory.PERMISSION_DENIED);
    }

    @Test
    void anythingElseIsOther() {
        is(new IllegalStateException("odd"), ErrorCategory.OTHER);
        is(new UnsupportedOperationException(), ErrorCategory.OTHER);
        assertThat(ErrorCategory.of(null)).isEqualTo(ErrorCategory.OTHER);
    }

    @Test
    void theNearestRecognisedCauseWinsAndTheChainIsBounded() {
        Throwable deep = new SocketTimeoutException("slow");
        for (int i = 0; i < 4; i++) deep = new RuntimeException("layer " + i, deep);
        assertThat(ErrorCategory.of(deep)).as("four layers down is still found").isEqualTo(ErrorCategory.TIMEOUT);
        Throwable tooDeep = new SocketTimeoutException("slow");
        for (int i = 0; i < 8; i++) tooDeep = new RuntimeException("layer " + i, tooDeep);
        assertThat(ErrorCategory.of(tooDeep)).as("eight layers is past the limit").isEqualTo(ErrorCategory.OTHER);
        // the outer, more specific one is preferred over the inner one
        assertThat(ErrorCategory.of(new IllegalArgumentException("bad", new SocketTimeoutException("slow")))).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
    }

    @Test
    void aCycleInTheCauseChainDoesNotLoop() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(ErrorCategory.of(a)).isEqualTo(ErrorCategory.OTHER);
    }

    @Test
    void aNullMessageIsFine() {
        assertThat(ErrorCategory.of(new IOException((String) null))).isEqualTo(ErrorCategory.NETWORK);
        assertThat(ErrorCategory.of(new InterruptedIOException())).isEqualTo(ErrorCategory.NETWORK);
    }

    @Test
    void statusCodesMapTheSameWayEverywhere() {
        assertThat(ErrorCategory.ofStatus(404)).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(ErrorCategory.ofStatus(408)).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(ErrorCategory.ofStatus(429)).isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(ErrorCategory.ofStatus(401)).isEqualTo(ErrorCategory.AUTHENTICATION);
        assertThat(ErrorCategory.ofStatus(403)).isEqualTo(ErrorCategory.AUTHENTICATION);
        assertThat(ErrorCategory.ofStatus(400)).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
        assertThat(ErrorCategory.ofStatus(500)).isEqualTo(ErrorCategory.SERVER_ERROR);
        assertThat(ErrorCategory.ofStatus(599)).isEqualTo(ErrorCategory.SERVER_ERROR);
        assertThat(ErrorCategory.ofStatus(418)).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(ErrorCategory.ofStatus(302)).isEqualTo(ErrorCategory.OTHER);
        assertThat(ErrorCategory.ofStatus(0)).isEqualTo(ErrorCategory.OTHER);
    }

    @Test
    void theBoundariesOfTheStatusRanges() {
        assertThat(ErrorCategory.ofStatus(399)).isEqualTo(ErrorCategory.OTHER);
        assertThat(ErrorCategory.ofStatus(499)).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(ErrorCategory.ofStatus(600)).isEqualTo(ErrorCategory.OTHER);
    }

    @Test
    void onlyTheFirstFiveLinksOfACauseChainAreAsked() {
        Throwable deepest = new io.github.llm4j.exception.RateLimitException("slow down");
        Throwable fifth = deepest;
        // links 1..5 are inspected: a known exception at the 5th link is found, at the 6th it is not
        Throwable chain5 = new RuntimeException(new RuntimeException(new RuntimeException(new RuntimeException(fifth))));
        assertThat(ErrorCategory.of(chain5)).isEqualTo(ErrorCategory.RATE_LIMITED);
        Throwable chain6 = new RuntimeException(chain5);
        assertThat(ErrorCategory.of(chain6)).isEqualTo(ErrorCategory.OTHER);
    }
}
