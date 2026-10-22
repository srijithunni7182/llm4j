package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * "The prompt is too long" as each provider says it, and, as important, the errors that must not be
 * mistaken for it (R14).
 */
class ContextOverflowClassifierTest {

    /**
     * Bodies in the shapes the providers document. Confirm against live responses before a release
     * (VERIFICATION §4.1).
     */
    private static final List<String> OVERFLOW =
            List.of(
                    // Anthropic
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 213456 tokens > 200000 maximum\"}}",
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"input length and `max_tokens` exceed context limit: 188240 + 21333 > 200000, decrease input length or `max_tokens` and try again\"}}",
                    // OpenAI-style (Sarvam and compatible servers)
                    "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens. However, your messages resulted in 9500 tokens. Please reduce the length of the messages.\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}",
                    "{\"error\":{\"message\":\"Input is too long for requested model.\"}}",
                    // Google
                    "{\"error\":{\"code\":400,\"message\":\"The input token count (1100000) exceeds the maximum number of tokens allowed (1048575).\",\"status\":\"INVALID_ARGUMENT\"}}",
                    "[{\"error\":{\"code\":400,\"message\":\"The input token count (5000) exceeds the maximum number of tokens allowed (4096).\",\"status\":\"INVALID_ARGUMENT\"}}]",
                    // Ollama and llama.cpp servers
                    "{\"error\":\"prompt too long; exceeded max context length by 5000 tokens\"}",
                    "{\"error\":{\"message\":\"the request exceeds the available context size, try increasing it\",\"type\":\"exceed_context_size_error\"}}",
                    // a bare text body
                    "context_length_exceeded");

    private static final List<String> NOT_OVERFLOW =
            List.of(
                    // an output limit, not the prompt
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: 100000 > 8192, which is the maximum allowed number of output tokens for claude-3-haiku\"}}",
                    "{\"error\":{\"code\":400,\"message\":\"Unable to submit request because the maximum output tokens must be at most 8192.\",\"status\":\"INVALID_ARGUMENT\"}}",
                    // other 400s
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages: text content blocks must be non-empty\"}}",
                    "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}",
                    "{\"error\":{\"message\":\"temperature must be between 0 and 2\"}}",
                    "{\"error\":{\"message\":\"model 'llama9' not found\"}}",
                    "{\"error\":{\"message\":\"Invalid tool schema: properties must be an object\"}}",
                    // limits of a different kind
                    "{\"error\":{\"message\":\"Rate limit reached for requests\",\"type\":\"rate_limit_error\"}}",
                    "{\"error\":{\"message\":\"You exceeded your current quota\"}}",
                    "{\"error\":{\"message\":\"Request payload size exceeds the limit: 10485760 bytes.\"}}",
                    "{\"error\":{\"message\":\"too many requests, slow down\"}}",
                    "",
                    "   ");

    @Test
    void recognisesWhatEachProviderSays() {
        for (String body : OVERFLOW)
            assertThat(ContextOverflowClassifier.isOverflow(400, body)).as(body).isTrue();
    }

    @Test
    void doesNotMistakeOtherErrorsForIt() {
        for (String body : NOT_OVERFLOW)
            assertThat(ContextOverflowClassifier.isOverflow(400, body)).as(body).isFalse();
        assertThat(ContextOverflowClassifier.isOverflow(400, null)).isFalse();
    }

    @Test
    void onlyClientErrorStatusesCount() {
        String body = OVERFLOW.get(0);
        assertThat(ContextOverflowClassifier.isOverflow(413, body)).isTrue();
        assertThat(ContextOverflowClassifier.isOverflow(422, body)).isTrue();
        assertThat(ContextOverflowClassifier.isOverflow(null, body))
                .isTrue(); // an error found inside a stream or a 200, with no status of its own
        assertThat(ContextOverflowClassifier.isOverflow(429, body)).isFalse();
        assertThat(ContextOverflowClassifier.isOverflow(401, body)).isFalse();
        assertThat(ContextOverflowClassifier.isOverflow(500, body)).isFalse();
        assertThat(ContextOverflowClassifier.isOverflow(503, body)).isFalse();
    }

    @Test
    void caseDoesNotMatter() {
        assertThat(
                        ContextOverflowClassifier.isOverflow(
                                400, "PROMPT IS TOO LONG: 5 tokens > 4 maximum"))
                .isTrue();
    }

    @Test
    void theExceptionIsAnInvalidRequestKeepingTheStatusAndTheBody() {
        InvalidRequestException e =
                ContextOverflowClassifier.invalidRequest(
                        "HTTP 400: prompt is too long", 400, OVERFLOW.get(0));
        assertThat(e).isInstanceOf(ContextOverflowException.class);
        assertThat(e.getStatusCode()).isEqualTo(400);
        assertThat(e.getResponseBody()).isEqualTo(OVERFLOW.get(0));
        assertThat(e.getMessage()).contains("prompt is too long");
    }

    @Test
    void anOrdinaryBadRequestStaysAnOrdinaryBadRequest() {
        InvalidRequestException e =
                ContextOverflowClassifier.invalidRequest(
                        "HTTP 400: temperature must be between 0 and 2", 400, NOT_OVERFLOW.get(4));
        assertThat(e).isNotInstanceOf(ContextOverflowException.class);
        assertThat(e.getClass()).isEqualTo(InvalidRequestException.class);
    }

    @Test
    void theMessageTextAloneCanDecideWhenThereIsNoBody() {
        InvalidRequestException e =
                ContextOverflowClassifier.invalidRequest(
                        "INVALID_ARGUMENT: The input token count (9) exceeds the maximum number of tokens allowed (4).",
                        null,
                        null);
        assertThat(e).isInstanceOf(ContextOverflowException.class);
    }
}
