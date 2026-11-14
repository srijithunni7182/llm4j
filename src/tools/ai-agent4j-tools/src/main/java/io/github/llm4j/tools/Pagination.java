package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * Bounded, read-only pagination for the HTTP kinds (phase 36b, D5): {@code link} follows a
 * {@code Link: <…>; rel="next"} header, {@code cursor} sends the next cursor found at a path in the body
 * back in a named parameter, {@code page} increments a page parameter until a page is empty. Every page
 * is sent through the same {@link HttpSupport} (so the same address rules, timeout and retries), the
 * walk stops at {@code max_pages} and at the total size cap and says so, and the whole is one result.
 * A tool that returns "page 1 of an unknown number" invites an agent to loop without bound, so the
 * bound belongs here.
 */
public final class Pagination {

    /** The options a kind lists to take pagination. */
    public static final Set<String> NAMES =
            Set.of("paginate", "max_pages", "cursor_path", "cursor_param", "page_param");

    /** How many pages are read when {@code max_pages} is not given. */
    public static final int DEFAULT_MAX_PAGES = 10;

    private static final int MAX_PAGES_CEILING = 100;
    private static final Pattern NEXT = Pattern.compile("<([^>]*)>\\s*;[^,]*\\brel=\"?([^\",;]*)\"?");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String style;
    private final int maxPages;
    private final String cursorPath;
    private final String cursorParam;
    private final String pageParam;

    private Pagination(String style, int maxPages, String cursorPath, String cursorParam, String pageParam) {
        this.style = style;
        this.maxPages = maxPages;
        this.cursorPath = cursorPath;
        this.cursorParam = cursorParam;
        this.pageParam = pageParam;
    }

    /**
     * Reads the options.
     *
     * @throws OptionException when the style is unknown or its parameters are missing
     */
    public static Pagination parse(Options o) {
        String style = o.get("paginate");
        int max = o.integer("max_pages", DEFAULT_MAX_PAGES, 1, MAX_PAGES_CEILING);
        if (style == null) return new Pagination(null, max, null, null, null);
        if (!Set.of("link", "cursor", "page").contains(style))
            throw new OptionException("paginate: is link, cursor or page, not " + style);
        if (style.equals("cursor") && (!o.has("cursor_path") || !o.has("cursor_param")))
            throw new OptionException("paginate: cursor needs cursor_path: (where the next cursor is in the answer) and cursor_param: (where it is sent)");
        if (style.equals("page") && !o.has("page_param"))
            throw new OptionException("paginate: page needs page_param: (the page or offset parameter)");
        return new Pagination(style, max, o.get("cursor_path"), o.get("cursor_param"), o.get("page_param"));
    }

    /** Whether the tool walks pages. */
    public boolean enabled() {
        return style != null;
    }

    /**
     * Pagination is for reads: following pages of something that changes state could repeat the change.
     *
     * @throws ToolRefusal for any method but GET when pagination is on
     */
    public void requireRead(String method) {
        if (enabled() && !method.equals("GET") && !method.equals("HEAD"))
            throw new ToolRefusal("paginate: is only for reads, not " + method);
    }

    /**
     * Sends the first request and the ones after it, as the style says.
     *
     * @param totalCap the most bytes the combined answer may hold
     * @return one reply: the first page's status and headers, the pages' bodies one after another,
     *     {@code truncated} when the walk stopped at a bound with more to read
     */
    public HttpSupport.Reply collect(HttpSupport http, Request first, HttpSupport.Retry retry, long totalCap) {
        return collect(http.withLimit(totalCap), first, retry);
    }

    /** As above, with the cap the {@link HttpSupport} already has. */
    public HttpSupport.Reply collect(HttpSupport http, Request first, HttpSupport.Retry retry) {
        HttpSupport.Reply reply = http.send(first, retry);
        if (!enabled() || !reply.success()) return reply;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        long cap = http.maxBytes();
        boolean truncated = reply.truncated();
        append(all, reply.body(), cap);
        HttpSupport.Reply current = reply;
        Request currentRequest = first;
        int pages = 1;
        int pageNumber = pageNumber(first);
        while (!truncated) {
            Request next = next(currentRequest, current, pageNumber + 1);
            if (next == null) break;
            if (pages >= maxPages) {
                truncated = true;
                break;
            }
            pageNumber++;
            current = http.send(next, retry);
            currentRequest = next;
            pages++;
            if (!current.success()) break;
            if (style.equals("page") && isEmpty(current)) break;
            truncated = !append(all, current.body(), cap) || current.truncated();
        }
        return new HttpSupport.Reply(reply.status(), reply.reason(), reply.contentType(), all.toByteArray(), truncated, reply.headers());
    }

    private static boolean append(ByteArrayOutputStream out, byte[] body, long cap) {
        if (out.size() > 0) out.write('\n');
        long room = cap - out.size();
        if (body.length > room) {
            out.write(body, 0, (int) Math.max(0, room));
            return false;
        }
        out.write(body, 0, body.length);
        return true;
    }

    private int pageNumber(Request request) {
        if (!style.equals("page")) return 1;
        String v = request.url().queryParameter(pageParam);
        try {
            return v == null ? 1 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private Request next(Request sent, HttpSupport.Reply reply, int nextPage) {
        HttpUrl url = null;
        switch (style) {
            case "link" -> {
                String header = reply.headers().get("Link");
                if (header == null) return null;
                Matcher m = NEXT.matcher(header);
                while (m.find()) if (m.group(2).trim().equals("next")) url = sent.url().resolve(m.group(1));
            }
            case "cursor" -> {
                String cursor = cursorOf(reply);
                if (cursor == null) return null;
                url = sent.url().newBuilder().setQueryParameter(cursorParam, cursor).build();
            }
            default -> url = sent.url().newBuilder().setQueryParameter(pageParam, String.valueOf(nextPage)).build();
        }
        return url == null ? null : sent.newBuilder().url(url).build();
    }

    private String cursorOf(HttpSupport.Reply reply) {
        try {
            JsonNode node = JSON.readTree(new String(reply.body(), StandardCharsets.UTF_8));
            for (String part : cursorPath.split("\\.")) {
                if (node == null) return null;
                node = node.get(part);
            }
            return node == null || node.isNull() || node.asText().isEmpty() ? null : node.asText();
        } catch (IOException notJson) {
            return null;
        }
    }

    private static boolean isEmpty(HttpSupport.Reply reply) {
        String text = new String(reply.body(), StandardCharsets.UTF_8).strip();
        return text.isEmpty() || text.equals("[]") || text.equals("{}");
    }
}
