package io.github.llm4j.provider.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A wire fixture under {@code src/test/resources/providers}: a header of {@code # key: value}
 * lines, a line with {@code ---}, then the body exactly as the vendor sent it. The header says
 * where it came from ({@code source: recorded} with {@code date} and {@code model}, or {@code
 * source: documentation-derived}, or {@code recorded-through-perplexity}), so a vendor is only
 * called supported when its fixtures were recorded from the real API.
 */
public record Fixtures(String path, Map<String, String> header, String body) {

    public static Fixtures load(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/providers/" + path)) {
            if (in == null) throw new IllegalArgumentException("no fixture " + path);
            return parse(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Fixtures parse(String path, String text) {
        int cut = text.indexOf("\n---\n");
        if (cut < 0)
            throw new IllegalArgumentException(path + ": missing the --- line after the header");
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : text.substring(0, cut).split("\n")) {
            if (!line.startsWith("# "))
                throw new IllegalArgumentException(
                        path + ": header line must start with '# ': " + line);
            int colon = line.indexOf(':');
            if (colon > 0)
                header.put(line.substring(2, colon).trim(), line.substring(colon + 1).trim());
        }
        return new Fixtures(path, header, text.substring(cut + 5));
    }

    public String source() {
        return header.getOrDefault("source", "");
    }

    public boolean recorded() {
        return source().equals("recorded") || source().equals("recorded-through-perplexity");
    }
}
