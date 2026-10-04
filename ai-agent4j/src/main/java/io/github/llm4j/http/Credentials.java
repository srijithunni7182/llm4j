package io.github.llm4j.http;

import java.util.Locale;
import okhttp3.Headers;

/** Keeps credentials that travel in request headers out of anything written about the response (error messages, logs). */
final class Credentials {

    private static final String MASK = "***";
    private static final int MIN_LENGTH = 6; // shorter strings would mangle ordinary text

    private Credentials() { }

    /** True for headers that carry a credential, by name. */
    static boolean isSensitive(String header) {
        String h = header.toLowerCase(Locale.ROOT);
        return h.equals("authorization") || h.equals("proxy-authorization") || h.equals("cookie")
                || h.contains("api-key") || h.contains("apikey") || h.contains("api_key")
                || h.contains("token") || h.contains("secret") || h.contains("subscription-key");
    }

    /** {@code text} with every credential the request carried replaced by {@code ***} (a {@code Bearer} token included). */
    static String scrub(String text, Headers requestHeaders) {
        if (text == null || text.isEmpty() || requestHeaders == null) return text;
        String out = text;
        for (int i = 0; i < requestHeaders.size(); i++) {
            if (!isSensitive(requestHeaders.name(i))) continue;
            String value = requestHeaders.value(i);
            out = replace(out, value);
            int space = value.indexOf(' ');
            if (space > 0) out = replace(out, value.substring(space + 1)); // "Bearer <token>" -> the token alone
        }
        return out;
    }

    private static String replace(String text, String secret) {
        if (secret == null || secret.length() < MIN_LENGTH) return text;
        return text.replace(secret, MASK);
    }
}
