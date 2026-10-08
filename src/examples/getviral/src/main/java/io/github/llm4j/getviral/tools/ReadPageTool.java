package io.github.llm4j.getviral.tools;

import io.github.llm4j.getviral.studio.StudioEvents;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a public web page and returns its title, date and main text, so the Researcher can check
 * a source instead of trusting a search snippet.
 *
 * <p>This runs on a shared server, so it only fetches public pages: http(s) on the standard ports,
 * never loopback, private, link-local (cloud metadata) or other internal addresses, re-checked on
 * every redirect, with a size cap and a timeout.
 */
public class ReadPageTool extends PublicApiTool {

    private static final int MAX_BYTES = 2_000_000;
    private static final int MAX_TEXT = 4_000;
    private static final int MAX_REDIRECTS = 4;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|svg|nav|footer|header|aside|form|figure|iframe|template)\\b.*?</\\1>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META = Pattern.compile(
            "(?is)<meta\\s+[^>]*(?:property|name)=[\"']([^\"']+)[\"'][^>]*content=[\"']([^\"']*)[\"']");
    private static final Pattern TIME = Pattern.compile("(?is)<time[^>]*datetime=[\"']([^\"']+)[\"']");
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=([\\w-]+)");

    private final boolean offline;

    public ReadPageTool(boolean offline, StudioEvents events) {
        super(offline, events);
        this.offline = offline;
    }

    @Override
    public String getName() {
        return "read_page";
    }

    @Override
    public String getDescription() {
        return "Reads a public web page (article, post, review) and returns its title, date and main text. "
                + "Use it on the best web_search results before citing them. Args: {\"url\": \"https://...\"}.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String raw = arg(args, "url", "link", "page");
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException e) {
            return "read_page needs a full URL, e.g. {\"url\": \"https://example.com/article\"}.";
        }
        if (offline) {
            return "Offline mode: pages can't be read, so rely on the search snippets and say the source wasn't opened.";
        }
        String cached = CACHE.get(uri.toString());
        if (cached != null) return cached;

        long start = System.nanoTime();
        String host = uri.getHost() == null ? raw : uri.getHost();
        try {
            Page page = fetchPage(uri);
            report(page.uri().getHost(), page.uri().toString(), true, start, "HTTP 200, " + page.html().length() + " chars");
            String result = summarise(page);
            CACHE.put(uri.toString(), result);
            return result;
        } catch (BlockedException e) {
            report(host, uri.toString(), false, start, "blocked");
            return "Can't read " + uri + ": " + e.getMessage();
        } catch (IOException e) {
            report(host, uri.toString(), false, start, e.getClass().getSimpleName());
            return "Couldn't read " + uri + " (" + e.getMessage() + "). Use another source or the search snippet.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while reading " + uri + ".";
        }
    }

    record Page(URI uri, String html) { }

    static final class BlockedException extends IOException {
        BlockedException(String message) {
            super(message);
        }
    }

    private Page fetchPage(URI uri) throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkPublic(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
                    .GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status / 100 == 3) {
                response.body().close();
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("redirect without a location"));
                current = current.resolve(location);
                continue;
            }
            try (InputStream body = response.body()) {
                if (status / 100 != 2) throw new IOException("HTTP " + status);
                String type = response.headers().firstValue("content-type").orElse("text/html").toLowerCase(Locale.ROOT);
                if (!(type.contains("html") || type.startsWith("text/"))) {
                    throw new IOException("not a web page (" + type.split(";")[0] + ")");
                }
                byte[] bytes = body.readNBytes(MAX_BYTES);
                Matcher cs = CHARSET.matcher(type);
                Charset charset = StandardCharsets.UTF_8;
                if (cs.find()) {
                    try {
                        charset = Charset.forName(cs.group(1));
                    } catch (IllegalArgumentException ignored) {
                        // keep UTF-8
                    }
                }
                return new Page(current, new String(bytes, charset));
            }
        }
        throw new IOException("too many redirects");
    }

    /** Only public http(s) hosts on standard ports; every resolved address must be public. */
    static void checkPublic(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new BlockedException("only http(s) pages can be read");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new BlockedException("no host in the URL");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw new BlockedException("only standard web ports are allowed");
        if (uri.getUserInfo() != null) throw new BlockedException("URLs with credentials aren't allowed");
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw new BlockedException("that address isn't on the public internet");
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0 || first >= 240) return false;                          // "this network", reserved
            if (first == 100 && second >= 64 && second <= 127) return false;       // carrier-grade NAT
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false;   // IETF protocol assignments
            if (first == 198 && (second == 18 || second == 19)) return false;      // benchmarking
            return true;
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                               // unique local fc00::/7
            boolean mapped = true;                                                 // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    // ── HTML → readable text ─────────────────────────────────────────────────────────────────

    static String summarise(Page page) {
        String html = page.html();
        String title = firstGroup(TITLE, html);
        String description = "";
        String published = "";
        Matcher meta = META.matcher(html);
        while (meta.find()) {
            String key = meta.group(1).toLowerCase(Locale.ROOT);
            if (title.isBlank() && key.equals("og:title")) title = meta.group(2);
            if (description.isBlank() && (key.equals("description") || key.equals("og:description"))) description = meta.group(2);
            if (published.isBlank() && (key.contains("published_time") || key.equals("date") || key.equals("pubdate"))) published = meta.group(2);
        }
        if (published.isBlank()) published = firstGroup(TIME, html);

        String text = mainText(html);
        StringBuilder out = new StringBuilder("Page: ").append(clip(decode(title), 160)).append('\n')
                .append("URL: ").append(page.uri()).append('\n');
        if (!published.isBlank()) out.append("Published: ").append(clip(published, 40)).append('\n');
        if (!description.isBlank()) out.append("Summary: ").append(clip(decode(description), 300)).append('\n');
        out.append("Text:\n").append(text.isBlank() ? "(no readable article text — the page may need JavaScript)" : text)
                .append("\n[source: live ").append(page.uri().getHost()).append(']');
        return out.toString();
    }

    static String mainText(String html) {
        String body = html;
        for (String tag : new String[] {"article", "main"}) {
            Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*)</" + tag + ">").matcher(html);
            if (m.find() && m.group(1).length() > 500) {
                body = m.group(1);
                break;
            }
        }
        body = DROP.matcher(body).replaceAll(" ");
        body = body.replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)<(br|/p|/h[1-6]|/li|/blockquote|/tr|/div)\\b[^>]*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        StringBuilder text = new StringBuilder();
        for (String line : decode(body).split("\n")) {
            String l = line.replaceAll("\\s+", " ").strip();
            // Keep sentences; drop menu items, buttons and cookie-banner fragments.
            if (l.split(" ").length < 6) continue;
            if (text.length() + l.length() > MAX_TEXT) {
                text.append(l, 0, Math.max(0, MAX_TEXT - text.length())).append('…');
                break;
            }
            text.append(l).append('\n');
        }
        return text.toString().strip();
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    static String decode(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                replacement = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : " ";
            } catch (NumberFormatException e) {
                replacement = " ";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&mdash;", "—")
                .replace("&ndash;", "–").replace("&hellip;", "…").replace("&rsquo;", "’").replace("&lsquo;", "‘")
                .replace("&rdquo;", "”").replace("&ldquo;", "“").replace("&amp;", "&");
    }
}
