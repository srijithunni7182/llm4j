package io.github.llm4j.eval.live;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Makes a recorded vendor response safe to commit: the key, account and organisation ids, request
 * and response ids, and the system fingerprint are replaced by fixed text, so the same response
 * always scrubs to the same bytes. Everything else (the shape, the text, the numbers) stays,
 * because that is what a fixture is for.
 */
final class Scrub {

    private static final Pattern PREFIXED_ID =
            Pattern.compile(
                    "\\b(chatcmpl|cmpl|resp|msg|rs|fc|call|req|ws|toolu|srvtoolu|run|gen|item)([-_])[A-Za-z0-9][A-Za-z0-9_-]{7,}");
    private static final Pattern UUID =
            Pattern.compile(
                    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern ORG =
            Pattern.compile("\\b(org|proj|acct|user)[-_][A-Za-z0-9]{8,}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("(\"system_fingerprint\"\\s*:\\s*)\"[^\"]*\"");
    private static final Pattern ACCOUNT_FIELD =
            Pattern.compile(
                    "(\"(?:user_id|account_id|organization|organization_id|org_id|project|project_id|request_id|safety_identifier)\"\\s*:\\s*)\"[^\"]*\"");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern KEY_SHAPED =
            Pattern.compile("\\b(sk|xai|pplx|key)-[A-Za-z0-9_-]{16,}");

    private Scrub() {}

    static String body(String text, Collection<String> secrets) {
        String out = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 6) out = out.replace(secret, "SCRUBBED-KEY");
        }
        out = FINGERPRINT.matcher(out).replaceAll("$1\"fp_scrubbed\"");
        out = ACCOUNT_FIELD.matcher(out).replaceAll("$1\"scrubbed\"");
        out = UUID.matcher(out).replaceAll("00000000-0000-0000-0000-000000000000");
        out = PREFIXED_ID.matcher(out).replaceAll("$1$2scrubbed");
        out = ORG.matcher(out).replaceAll("$1-scrubbed");
        out = BEARER.matcher(out).replaceAll("Bearer SCRUBBED-KEY");
        out = KEY_SHAPED.matcher(out).replaceAll("$1-SCRUBBED-KEY");
        return out;
    }
}
