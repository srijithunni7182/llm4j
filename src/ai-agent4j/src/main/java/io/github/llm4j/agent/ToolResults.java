package io.github.llm4j.agent;

import io.github.llm4j.telemetry.ErrorCategory;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reading a tool's text result as a failure. Many tools report an error by <em>returning</em> {@code "Error: …"} rather than throwing, so a
 * normal return does not mean success. These rules are heuristics, and a result that matches is reported as a <em>soft</em> failure so that
 * nobody mistakes it for an exception. A tool that knows its own result shape overrides {@link Tool#isFailure(String)}.
 */
public final class ToolResults {

    private static final Pattern FAILURE = Pattern.compile(
            "^\\s*(?:"
                    + "(?:error|failed|failure|exception|tool error)(?:\\s*$|\\s*[:\\-–]|\\s+(?:to|in|on|at|while|when|during|with|from|executing|calling|reading|writing|parsing"
                    + "|connecting|processing|loading|running|fetching|occurred|code|response|status)\\b)"
                    + "|(?:HTTP/\\d(?:\\.\\d)?\\s+)?[45]\\d\\d(?:\\s*$|\\s*[:\\-–]|\\s+(?!(?:requests?|results?|items?|rows?|files?|records?|matches|documents?)\\b)[A-Za-z])"
                    + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS = Pattern.compile("(?<![\\d.])([45]\\d\\d)(?![\\d.])");

    private ToolResults() {}

    /** Whether a tool's text result begins the way an error does. */
    public static boolean looksLikeFailure(String result) {
        return result != null && !result.isBlank() && FAILURE.matcher(result).find();
    }

    /** What kind of failure a result describes, read from its wording; {@link ErrorCategory#OTHER} when it does not say. */
    public static ErrorCategory categoryOf(String result) {
        if (result == null) return ErrorCategory.OTHER;
        String t = result.toLowerCase(Locale.ROOT);
        var m = STATUS.matcher(t);
        int status = m.find() ? Integer.parseInt(m.group(1)) : 0;
        if (t.contains("not found") || status == 404) return ErrorCategory.NOT_FOUND;
        if (t.contains("timed out") || t.contains("timeout") || status == 408) return ErrorCategory.TIMEOUT;
        if (status == 401 || status == 403 || t.contains("unauthor") || t.contains("forbidden") || t.contains("permission denied") || t.contains("access denied")) {
            return ErrorCategory.PERMISSION_DENIED;
        }
        if (status == 429 || t.contains("rate limit") || t.contains("too many requests")) return ErrorCategory.RATE_LIMITED;
        if (status >= 400 && status < 500) return ErrorCategory.CLIENT_ERROR;
        if (status >= 500) return ErrorCategory.SERVER_ERROR;
        if (t.contains("missing") || t.contains("invalid") || t.contains("must be") || t.contains(" provided") || t.contains("required")) {
            return ErrorCategory.INVALID_ARGUMENTS;
        }
        return ErrorCategory.OTHER;
    }
}
