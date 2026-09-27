package io.github.llm4j.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Edge cases for the parsers (coverage for N4). */
class ParserEdgeCasesTest {

    private final MutableClock clock = new MutableClock();

    private static RateLimitParser.Response resp(Map<String, String> headers, String body) {
        Map<String, List<String>> h = new HashMap<>();
        headers.forEach((k, v) -> h.put(k, List.of(v)));
        return new RateLimitParser.Response("p", 429, h, body);
    }

    @Test
    void responseToleratesNulls() {
        Map<String, List<String>> h = new HashMap<>();
        h.put("Empty", List.of());
        List<String> nullValue = new ArrayList<>();
        nullValue.add(null);
        h.put("Null", nullValue);
        h.put("Blank", List.of("  "));
        RateLimitParser.Response r = new RateLimitParser.Response(null, 429, h, null);
        assertThat(r.provider()).isEqualTo("unknown");
        assertThat(r.body()).isEmpty();
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.header("Empty")).isNull();
        assertThat(r.header("Null")).isNull();
        assertThat(r.header("Blank")).isNull();
        assertThat(r.header("Missing")).isNull();
        assertThat(new RateLimitParser.Response("p", 429, null, "").header("x")).isNull();
    }

    @Test
    void openAiRequestsExhaustedAndLatestWithoutExhaustion() {
        OpenAiHeaders p = new OpenAiHeaders();
        RateLimitInfo requests = p.parse(resp(Map.of(
                "x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", "20s",
                "x-ratelimit-remaining-tokens", "5", "x-ratelimit-reset-tokens", "1m"), ""), clock).orElseThrow();
        assertThat(requests.scope()).isEqualTo(RateLimitInfo.Scope.REQUESTS);
        assertThat(requests.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:20Z"));
        RateLimitInfo latest = p.parse(resp(Map.of(
                "x-ratelimit-reset-requests", "20s", "x-ratelimit-reset-tokens", "1m",
                "x-ratelimit-remaining-tokens", "abc"), ""), clock).orElseThrow();
        assertThat(latest.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:01:00Z"));
        RateLimitInfo both = p.parse(resp(Map.of(
                "x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", "2m",
                "x-ratelimit-remaining-tokens", "0", "x-ratelimit-reset-tokens", "1m"), ""), clock).orElseThrow();
        assertThat(both.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:02:00Z"));
        assertThat(p.parse(resp(Map.of("x-ratelimit-reset-tokens", "never"), ""), clock)).isEmpty();
        assertThat(p.parse(resp(Map.of(), ""), clock)).isEmpty();
    }

    @Test
    void anthropicRequestsExhaustedAndBothExhausted() {
        AnthropicHeaders p = new AnthropicHeaders();
        RateLimitInfo requests = p.parse(resp(Map.of(
                "anthropic-ratelimit-requests-remaining", "0",
                "anthropic-ratelimit-requests-reset", "2026-09-27T10:00:10Z"), ""), clock).orElseThrow();
        assertThat(requests.scope()).isEqualTo(RateLimitInfo.Scope.REQUESTS);
        RateLimitInfo both = p.parse(resp(Map.of(
                "anthropic-ratelimit-requests-remaining", "0",
                "anthropic-ratelimit-requests-reset", "2026-09-27T10:00:10Z",
                "anthropic-ratelimit-input-tokens-remaining", "0",
                "anthropic-ratelimit-input-tokens-reset", "2026-09-27T10:00:40Z",
                "anthropic-ratelimit-output-tokens-remaining", "0",
                "anthropic-ratelimit-output-tokens-reset", "2026-09-27T10:00:20Z"), ""), clock).orElseThrow();
        assertThat(both.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:40Z"));
        assertThat(p.parse(resp(Map.of("anthropic-ratelimit-requests-limit", "5"), ""), clock)).isEmpty();
        assertThat(AnthropicHeaders.instant(null)).isNull();
        assertThat(AnthropicHeaders.number(null)).isNull();
    }

    @Test
    void googleBodyShapes() {
        GoogleRpcBody p = new GoogleRpcBody(null);
        assertThat(p.parse(resp(Map.of(), ""), clock)).isEmpty();
        assertThat(p.parse(resp(Map.of(), "{\"error\":{\"message\":\"no details here\"}}"), clock)).isEmpty();
        assertThat(p.parse(resp(Map.of(), "{\"error\":{\"details\":{\"not\":\"array\"}}}"), clock)).isEmpty();
        assertThat(p.parse(resp(Map.of(), "[{\"details\":[]}]"), clock)).isEmpty();
        // the daily violation wins even when listed after a per-minute one
        String mixed = """
                {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaId":"GenerateRequestsPerMinutePerProjectPerModel"},
                  {"quotaId":"GenerateRequestsPerDayPerProjectPerModel"},
                  {"quotaId":"GenerateRequestsPerMinuteAgain"},
                  {"noQuotaId":true}]}]}}
                """;
        RateLimitInfo info = p.parse(resp(Map.of(), mixed), clock).orElseThrow();
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.DAILY_QUOTA);
        assertThat(info.quotaId()).isEqualTo("GenerateRequestsPerDayPerProjectPerModel");
        String dailyWord = "{\"error\":{\"details\":[{\"@type\":\"google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"DailyLimit\"}]}]}}";
        assertThat(p.parse(resp(Map.of(), dailyWord), clock).orElseThrow().scope()).isEqualTo(RateLimitInfo.Scope.DAILY_QUOTA);
        String retryOnly = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"1.5s\"}]}}";
        RateLimitInfo r = p.parse(resp(Map.of(), retryOnly), clock).orElseThrow();
        assertThat(r.scope()).isEqualTo(RateLimitInfo.Scope.UNKNOWN);
        assertThat(r.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:01.500Z"));
    }

    @Test
    void aThrowingParserIsSkipped() {
        RateLimitParsers parsers = new RateLimitParsers(List.of(
                (response, c) -> { throw new IllegalStateException("bug"); },
                new RetryAfterParser()), java.time.Duration.ofSeconds(60), java.time.Duration.ofHours(1));
        RateLimitInfo info = parsers.parse(resp(Map.of("Retry-After", "7"), ""), clock);
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:07Z"));
    }

    @Test
    void describeCoversEveryScope() {
        for (RateLimitInfo.Scope s : RateLimitInfo.Scope.values()) {
            assertThat(RateLimitInfo.at(MutableClock.T0, s, "x", "d").describe()).startsWith("x ");
        }
    }
}
