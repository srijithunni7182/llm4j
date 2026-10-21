package io.github.llm4j.loom.observe;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Send the same traces and logs to any OpenTelemetry collector or backend that takes OTLP over HTTP
 * (an OpenTelemetry Collector, Tempo, Honeycomb, Datadog's OTLP intake). Independent of Fabric. A
 * header value may be {@code ${secret:NAME}}, which is read from the process's secret store when it
 * is sent, so a token never appears on a command line.
 *
 * @param endpoint the base URL; {@code /v1/traces} and {@code /v1/logs} are added
 * @param headers {@code Name=value} pairs
 * @param outbox where the local outbox is kept (the same {@code --observe-outbox} as for Fabric),
 *     or null for the default
 */
public record OtlpOptions(URI endpoint, List<String> headers, java.nio.file.Path outbox) {

    private static final Pattern HEADER = Pattern.compile("([A-Za-z0-9!#$%&'*+.^_`|~-]+)=(.*)");

    public OtlpOptions {
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /**
     * Checks the words from the command line. Throws {@link IllegalArgumentException} with a
     * message to show.
     */
    public static OtlpOptions parse(String endpoint, List<String> headers, String name) {
        return parse(endpoint, headers, name, null);
    }

    public static OtlpOptions parse(
            String endpoint, List<String> headers, String name, String outbox) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "--observe-otlp needs --observe-name: it becomes the service name in the collector. There is no default.");
        }
        URI u;
        try {
            u = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "--observe-otlp takes a URL like https://collector.example.com:4318, got '"
                            + endpoint
                            + "'");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean loopback =
                host.equals("localhost")
                        || host.equals("127.0.0.1")
                        || host.equals("[::1]")
                        || host.equals("::1");
        if (host.isEmpty() || !(scheme.equals("https") || (scheme.equals("http") && loopback))) {
            throw new IllegalArgumentException(
                    "--observe-otlp needs an https URL (plain http only for a loopback address), got '"
                            + endpoint
                            + "'");
        }
        if (u.getUserInfo() != null)
            throw new IllegalArgumentException(
                    "--observe-otlp must not carry a user name or password in the URL");
        List<String> ok = new ArrayList<>();
        for (String h : headers == null ? List.<String>of() : headers) {
            var m = HEADER.matcher(h);
            if (!m.matches() || h.contains("\n") || h.contains("\r")) {
                throw new IllegalArgumentException(
                        "--observe-otlp-header takes Name=value (value may be ${secret:NAME}), got '"
                                + h.replaceAll("=.*", "=…")
                                + "'");
            }
            ok.add(h);
        }
        return new OtlpOptions(
                u,
                ok,
                outbox == null || outbox.isBlank() ? null : java.nio.file.Path.of(outbox.trim()));
    }
}
