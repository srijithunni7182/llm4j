package io.github.llm4j.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Verification plan V1.1–V1.10 and N2. */
class RateLimitParsersTest {

    static final String GEMINI_PER_MINUTE = """
            {"error": {"code": 429, "message": "Resource has been exhausted", "status": "RESOURCE_EXHAUSTED",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                 "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                                 "quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
                {"@type": "type.googleapis.com/google.rpc.Help", "links": []},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "34s"}]}}
            """;

    static final String GEMINI_PER_DAY = """
            {"error": {"code": 429, "message": "You exceeded your current quota", "status": "RESOURCE_EXHAUSTED",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                 "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                                 "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                                 "quotaDimensions": {"model": "gemini-2.5-flash", "location": "global"},
                                 "quotaValue": "250"}]},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "20s"}]}}
            """;

    private final MutableClock clock = new MutableClock();

    private static RateLimitParser.Response response(Map<String, String> headers, String body) {
        Map<String, List<String>> h = new HashMap<>();
        headers.forEach((k, v) -> h.put(k, List.of(v)));
        return new RateLimitParser.Response("test", 429, h, body);
    }

    private RateLimitInfo parse(Map<String, String> headers, String body) {
        return RateLimitParsers.standard().parse(response(headers, body), clock);
    }

    @Test
    void v1_1_retryAfterSeconds() {
        RateLimitInfo info = parse(Map.of("Retry-After", "120"), "");
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:02:00Z"));
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.UNKNOWN);
        assertThat(info.estimated()).isFalse();
    }

    @Test
    void v1_2_retryAfterHttpDate() {
        RateLimitInfo info = parse(Map.of("retry-after", "Sun, 27 Sep 2026 10:05:00 GMT"), "");
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:05:00Z"));
    }

    @Test
    void v1_3_anthropicPicksTheExhaustedDimension() {
        RateLimitInfo info = parse(Map.of(
                "anthropic-ratelimit-tokens-limit", "80000",
                "anthropic-ratelimit-tokens-remaining", "0",
                "anthropic-ratelimit-tokens-reset", "2026-09-27T10:00:45Z",
                "anthropic-ratelimit-requests-limit", "50",
                "anthropic-ratelimit-requests-remaining", "12",
                "anthropic-ratelimit-requests-reset", "2026-09-27T10:00:05Z",
                "retry-after", "3"), "");
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:45Z"));
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.TOKENS);
        assertThat(info.limit()).isEqualTo(80000L);
        assertThat(info.remaining()).isZero();
    }

    @Test
    void v1_3b_anthropicWithoutAnExhaustedDimensionTakesTheLatestReset() {
        RateLimitInfo info = parse(Map.of(
                "anthropic-ratelimit-requests-remaining", "3",
                "anthropic-ratelimit-requests-reset", "2026-09-27T10:00:05Z",
                "anthropic-ratelimit-output-tokens-reset", "2026-09-27T10:00:30+00:00"), "");
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:30Z"));
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.TOKENS);
    }

    @Test
    void v1_4_openAiTokens() {
        RateLimitInfo info = parse(Map.of(
                "x-ratelimit-limit-tokens", "30000",
                "x-ratelimit-remaining-tokens", "0",
                "x-ratelimit-reset-tokens", "6m0s",
                "x-ratelimit-remaining-requests", "499",
                "x-ratelimit-reset-requests", "120ms"), "");
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:06:00Z"));
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.TOKENS);
        assertThat(info.limit()).isEqualTo(30000L);
    }

    @Test
    void v1_5_durationGrammar() {
        assertThat(Durations.parse("1h2m3.5s")).isEqualTo(Duration.ofMillis(3_723_500));
        assertThat(Durations.parse("120ms")).isEqualTo(Duration.ofMillis(120));
        assertThat(Durations.parse("17s")).isEqualTo(Duration.ofSeconds(17));
        assertThat(Durations.parse("6m0s")).isEqualTo(Duration.ofMinutes(6));
        assertThat(Durations.parse("0.5s")).isEqualTo(Duration.ofMillis(500));
        assertThat(Durations.parse("soon")).isNull();
        assertThat(Durations.parse("5x")).isNull();
        assertThat(Durations.parse("s5")).isNull();
        assertThat(Durations.parse("")).isNull();
        assertThat(Durations.parse(null)).isNull();
    }

    @Test
    void v1_6_geminiPerMinute() {
        RateLimitInfo info = parse(Map.of(), GEMINI_PER_MINUTE);
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:34Z"));
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.REQUESTS);
        assertThat(info.quotaId()).isEqualTo("GenerateRequestsPerMinutePerProjectPerModel-FreeTier");
    }

    @Test
    void v1_7_geminiDailyQuotaResetsAtPacificMidnight() {
        RateLimitInfo info = parse(Map.of(), GEMINI_PER_DAY);
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.DAILY_QUOTA);
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-28T07:00:00Z")); // 00:00 PDT
        assertThat(info.describe()).contains("daily quota", "GenerateRequestsPerDay");
    }

    @Test
    void v1_7b_dailyQuotaAfterPacificMidnightButBeforeUtcMidnight() {
        clock.set("2026-09-28T03:00:00Z"); // 20:00 PDT on the 27th
        RateLimitInfo info = parse(Map.of(), GEMINI_PER_DAY);
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-28T07:00:00Z"));
    }

    @Test
    void v1_8_dailyResetZoneIsConfigurable() {
        RateLimitInfo info = RateLimitParsers.standard(ZoneOffset.UTC, Duration.ofSeconds(60), Duration.ofHours(1))
                .parse(response(Map.of(), GEMINI_PER_DAY), clock);
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
    }

    @Test
    void v1_9_fallbackDoublesPerConsecutive429AndResetsOnSuccess() {
        RateLimitParsers parsers = RateLimitParsers.standard();
        RateLimitParser.Response bare = response(Map.of(), "");
        List<Long> waits = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RateLimitInfo info = parsers.parse(bare, clock);
            assertThat(info.estimated()).isTrue();
            waits.add(info.waitFrom(clock.instant()).toSeconds());
        }
        parsers.success("test");
        waits.add(parsers.parse(bare, clock).waitFrom(clock.instant()).toSeconds());
        assertThat(waits).containsExactly(60L, 120L, 240L, 60L);
    }

    @Test
    void v1_9b_fallbackIsCappedAtOneHour() {
        RateLimitParsers parsers = RateLimitParsers.standard();
        RateLimitInfo info = null;
        for (int i = 0; i < 12; i++) info = parsers.parse(response(Map.of(), ""), clock);
        assertThat(info.waitFrom(clock.instant())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void v1_10_malformedValuesFallThrough() {
        RateLimitInfo info = parse(Map.of("Retry-After", "soon", "x-ratelimit-reset-tokens", "later",
                "anthropic-ratelimit-tokens-reset", "tomorrow"), "{not json details");
        assertThat(info.estimated()).isTrue();
        // a bad header does not hide a good body
        info = parse(Map.of("Retry-After", "soon"), GEMINI_PER_MINUTE);
        assertThat(info.resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:34Z"));
        // negative seconds are ignored
        assertThat(parse(Map.of("Retry-After", "-5"), "").estimated()).isTrue();
    }

    @Test
    void geminiBodyAsArrayAndWithoutRetryInfo() {
        String array = "[" + GEMINI_PER_MINUTE + "]";
        assertThat(parse(Map.of(), array).resetAt()).isEqualTo(Instant.parse("2026-09-27T10:00:34Z"));
        String tokens = GEMINI_PER_MINUTE.replace("GenerateRequestsPerMinute", "GenerateContentInputTokensPerMinute");
        assertThat(parse(Map.of(), tokens).scope()).isEqualTo(RateLimitInfo.Scope.TOKENS);
        String noDelay = GEMINI_PER_MINUTE.replace("\"retryDelay\": \"34s\"", "\"other\": 1");
        assertThat(parse(Map.of(), noDelay).estimated()).isTrue();
    }

    @Test
    void n2_parsingNeverThrows() {
        Random random = new Random(42);
        String[] names = {"Retry-After", "anthropic-ratelimit-tokens-reset", "anthropic-ratelimit-tokens-remaining",
                "x-ratelimit-reset-tokens", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests"};
        String[] bodies = {"", "{", "null", "[]", "{\"error\":{\"details\":5}}", "{\"error\":{\"details\":[{}]}}",
                "{\"error\":{\"details\":[{\"@type\":\"google.rpc.RetryInfo\",\"retryDelay\":7}]}}", GEMINI_PER_DAY};
        RateLimitParsers parsers = RateLimitParsers.standard();
        for (int i = 0; i < 1000; i++) {
            Map<String, String> headers = new HashMap<>();
            for (String n : names) {
                if (random.nextBoolean()) headers.put(n, randomValue(random));
            }
            RateLimitInfo info = parsers.parse(response(headers, bodies[random.nextInt(bodies.length)]), clock);
            assertThat(info.resetAt()).isNotNull();
        }
    }

    private static String randomValue(Random r) {
        String[] pool = {"", " ", "-1", "0", "5", "1e9", "NaN", "Infinity", "6m0s", "1h", "x", "2026-13-40T99:99:99Z",
                "2026-09-27T10:00:45Z", "Sun, 27 Sep 2026 10:05:00 GMT", "99999999999999999999"};
        return pool[r.nextInt(pool.length)];
    }

    @Test
    void infoHelpers() {
        RateLimitInfo info = RateLimitInfo.at(Instant.parse("2026-09-27T09:00:00Z"), null, null, "d");
        assertThat(info.waitFrom(clock.instant())).isEqualTo(Duration.ZERO);
        assertThat(info.scope()).isEqualTo(RateLimitInfo.Scope.UNKNOWN);
        assertThat(info.withProvider("google").describe()).isEqualTo("google rate limit");
    }
}
