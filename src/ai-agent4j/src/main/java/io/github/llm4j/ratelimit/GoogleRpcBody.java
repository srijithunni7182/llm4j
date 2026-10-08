package io.github.llm4j.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * Google's error body ({@code error.details[]}): {@code google.rpc.RetryInfo.retryDelay} and
 * {@code google.rpc.QuotaFailure.violations[].quotaId}. A per-day quota resets at the next midnight in
 * {@code dailyResetZone} (Pacific time by default), whatever the short {@code retryDelay} says.
 */
public final class GoogleRpcBody implements RateLimitParser {

    public static final ZoneId DEFAULT_DAILY_RESET_ZONE = ZoneId.of("America/Los_Angeles");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ZoneId dailyResetZone;

    public GoogleRpcBody() {
        this(DEFAULT_DAILY_RESET_ZONE);
    }

    public GoogleRpcBody(ZoneId dailyResetZone) {
        this.dailyResetZone = dailyResetZone == null ? DEFAULT_DAILY_RESET_ZONE : dailyResetZone;
    }

    @Override
    public Optional<RateLimitInfo> parse(Response response, Clock clock) {
        String body = response.body();
        if (body.isBlank() || !body.contains("details")) return Optional.empty();
        JsonNode details;
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode error = root.isArray() && root.size() > 0 ? root.get(0).path("error") : root.path("error");
            details = error.path("details");
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!details.isArray()) return Optional.empty();
        String retryDelay = null;
        String quotaId = null;
        for (JsonNode d : details) {
            String type = d.path("@type").asText("");
            if (type.endsWith("google.rpc.RetryInfo")) {
                retryDelay = d.path("retryDelay").isTextual() ? d.path("retryDelay").asText() : null;
            } else if (type.endsWith("google.rpc.QuotaFailure")) {
                for (JsonNode v : d.path("violations")) {
                    String id = v.path("quotaId").asText(null);
                    if (id != null && (quotaId == null || isDaily(id))) quotaId = id;
                }
            }
        }
        Instant now = clock.instant();
        if (quotaId != null && isDaily(quotaId)) {
            Instant midnight = LocalDate.ofInstant(now, dailyResetZone).plusDays(1).atStartOfDay(dailyResetZone).toInstant();
            return Optional.of(new RateLimitInfo(midnight, RateLimitInfo.Scope.DAILY_QUOTA, response.provider(),
                    quotaId, null, null, false, "quotaId " + quotaId));
        }
        Duration delay = Durations.parse(retryDelay);
        if (delay == null) return Optional.empty();
        RateLimitInfo.Scope scope = quotaId == null ? RateLimitInfo.Scope.UNKNOWN
                : quotaId.toLowerCase(Locale.ROOT).contains("token") ? RateLimitInfo.Scope.TOKENS
                : RateLimitInfo.Scope.REQUESTS;
        return Optional.of(new RateLimitInfo(now.plus(delay), scope, response.provider(), quotaId, null, null,
                false, "retryDelay " + retryDelay));
    }

    static boolean isDaily(String quotaId) {
        String q = quotaId.toLowerCase(Locale.ROOT);
        return q.contains("perday") || q.contains("daily");
    }
}
