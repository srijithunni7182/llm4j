package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.telemetry.ErrorCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tools that return their errors as text: which results are failures, and what kind (design §4.3, R9). */
class ToolResultsTest {

    private static final List<String> FAILURES = List.of(
            "Error: No search 'query' provided.", "error", "ERROR: boom", "  Error: with leading space", "Failed to read the file",
            "failure: could not connect", "Exception in thread main", "Tool error: bad input", "HTTP/1.1 503 Service Unavailable", "404",
            "500 Internal Server Error", "HTTP/2 429 Too Many Requests", "Error executing tool: null", "error: ");

    private static final List<String> NOT_FAILURES = List.of(
            "No errors found", "The error rate is 3%", "Result: error handling guide", "Errors: 0", "Errorless design", "Failure analysis: a short history",
            "", "   ", "Paris is the capital of France. An error would be to say otherwise.", "200 OK", "Status: 500 requests served",
            "x".repeat(2_000) + " error", "Found 3 results", "Exceptional results today");

    @Test
    void recognisesTheUsualWaysATextResultSaysItFailed() {
        for (String s : FAILURES) assertThat(ToolResults.looksLikeFailure(s)).as(s).isTrue();
    }

    @Test
    void doesNotMistakeOrdinaryTextForAFailure() {
        for (String s : NOT_FAILURES) assertThat(ToolResults.looksLikeFailure(s)).as(s).isFalse();
        assertThat(ToolResults.looksLikeFailure(null)).isFalse();
    }

    @Test
    void readsWhatKindOfFailureFromTheWording() {
        assertThat(ToolResults.categoryOf("Error: Endpoint 'GET /x' not found in API specification")).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(ToolResults.categoryOf("HTTP/1.1 404 Not Found")).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(ToolResults.categoryOf("Error: request timed out after 10 s")).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(ToolResults.categoryOf("Error: timeout")).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(ToolResults.categoryOf("Error: 401 Unauthorized")).isEqualTo(ErrorCategory.PERMISSION_DENIED);
        assertThat(ToolResults.categoryOf("HTTP 403 Forbidden")).isEqualTo(ErrorCategory.PERMISSION_DENIED);
        assertThat(ToolResults.categoryOf("Error: unauthorized for this key")).isEqualTo(ErrorCategory.PERMISSION_DENIED);
        assertThat(ToolResults.categoryOf("HTTP/1.1 429 Too Many Requests")).isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(ToolResults.categoryOf("Error: rate limit reached")).isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(ToolResults.categoryOf("HTTP/1.1 418 I'm a teapot")).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(ToolResults.categoryOf("500 Internal Server Error")).isEqualTo(ErrorCategory.SERVER_ERROR);
        assertThat(ToolResults.categoryOf("HTTP/1.1 503 Service Unavailable")).isEqualTo(ErrorCategory.SERVER_ERROR);
        assertThat(ToolResults.categoryOf("Error: No search 'query' provided.")).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
        assertThat(ToolResults.categoryOf("Error: 'endpoint' and 'method' arguments must be strings")).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
        assertThat(ToolResults.categoryOf("Error: invalid date format")).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
        assertThat(ToolResults.categoryOf("Error: something odd happened")).isEqualTo(ErrorCategory.OTHER);
        assertThat(ToolResults.categoryOf(null)).isEqualTo(ErrorCategory.OTHER);
    }

    @Test
    void aToolCanChangeHowItsResultsAreRead() {
        Tool plain = new Tool() {
            @Override public String getName() { return "t"; }
            @Override public String getDescription() { return "d"; }
            @Override public String execute(Map<String, Object> args) { return "x"; }
        };
        assertThat(plain.isFailure("Error: x")).as("the default is the common wording").isTrue();
        assertThat(plain.isFailure("ERR 42")).isFalse();
        Tool mine = new Tool() {
            @Override public String getName() { return "t"; }
            @Override public String getDescription() { return "d"; }
            @Override public String execute(Map<String, Object> args) { return "x"; }
            @Override public boolean isFailure(String result) { return result.startsWith("ERR "); }
        };
        assertThat(mine.isFailure("ERR 42")).isTrue();
        assertThat(mine.isFailure("Error: not mine")).isFalse();
    }
}
