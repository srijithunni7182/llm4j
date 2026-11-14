package io.github.llm4j.loom.execution;

import io.github.llm4j.mcp.McpHttpPoster;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Sends a remote MCP server's POSTs through the shared HTTP layer (phase 36b, D6): the address rules
 * of {@code NetPolicy} (the host the script names is the author's own, so a local server is reachable;
 * a private or metadata address behind another name is not), a timeout and a size cap on every reply.
 */
final class HttpMcpPoster implements McpHttpPoster {

    private static final long MAX_REPLY_BYTES = 8L * 1024 * 1024;

    private final HttpSupport http;
    private final String host;

    HttpMcpPoster(String url, Duration timeout, io.github.llm4j.agent.tool.EffectContext context) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException(url + " is not a valid address");
        this.host = parsed.host();
        this.http =
                new HttpSupport(
                        new NetPolicy(new NetPolicy.Rules(false, false, Set.of(), false), NetPolicy.Resolver.SYSTEM, host),
                        context,
                        timeout,
                        MAX_REPLY_BYTES,
                        0);
    }

    @Override
    public Reply post(String url, Map<String, String> headers, byte[] body) throws IOException {
        Request.Builder request = new Request.Builder().url(url).post(RequestBody.create(body, MediaType.get("application/json")));
        headers.forEach(request::header);
        HttpSupport.Reply reply;
        try {
            reply = http.send(request.build(), HttpSupport.Retry.NONE);
        } catch (ToolRefusal refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        Map<String, String> out = new LinkedHashMap<>();
        reply.headers().names().forEach(n -> out.put(n.toLowerCase(java.util.Locale.ROOT), reply.headers().get(n)));
        return new Reply(reply.status(), out, reply.body());
    }
}
