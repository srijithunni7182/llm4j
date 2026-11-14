package io.github.llm4j.tools.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.tools.CredentialRejected;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.Limits;
import io.github.llm4j.tools.NetOptions;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Makes one call of one operation: builds the request from the operation and the arguments, sends it
 * through the shared HTTP layer, and turns the answer into the text the tool returns. Every address
 * rule, timeout, size cap and retry rule is the layer's; this class adds the credential handling
 * (asked for on every request, refused once, then a typed failure) and the answer's shape.
 */
final class ApiClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHALLENGE_CHARS = 500;
    private static final int ERROR_EXCERPT_CHARS = 300;

    private final String tool;
    private final HttpUrl base;
    private final NetOptions net;
    private final Credential credential;
    private final Answers.Settings answers;
    private final HttpSupport http;
    private final List<String> fixedSecrets;

    ApiClient(
            String tool,
            HttpUrl base,
            NetOptions net,
            Credential credential,
            Answers.Settings answers,
            HttpSupport http,
            List<String> fixedSecrets) {
        this.tool = tool;
        this.base = base;
        this.net = net;
        this.credential = credential;
        this.answers = answers;
        this.http = http;
        this.fixedSecrets = List.copyOf(fixedSecrets);
    }

    /** The host the calls go to, for an audit line. */
    String host() {
        return base.host();
    }

    /**
     * Calls {@code op}.
     *
     * @param args the arguments, already checked against the operation
     * @param idempotencyKey the journal's key for this call, or null
     * @return the text the tool returns
     * @throws CredentialRejected when the credential was refused even after asking for a fresh one
     * @throws ToolRefusal when the call cannot be made, or an effect was answered with an error
     */
    String call(ApiOperation op, Map<String, Object> args, String idempotencyKey) {
        boolean effect = !op.isRead();
        HttpSupport.Retry retry =
                !effect
                                || net.idempotency()
                                || net.onUnknown() == EffectPolicy.OnUnknown.RETRY
                        ? HttpSupport.Retry.ALL
                        : HttpSupport.Retry.NONE;
        boolean secure = base.isHttps() || isLoopback(base.host());
        Credential.Held held = credential.present() ? credential.hold(secure) : null;
        HttpSupport.Reply reply = send(op, args, idempotencyKey, held, retry);
        if (held != null && reply.status() == 401 && credential.canRefresh()) {
            // the credential may have expired since it was fetched: ask for a fresh one, once
            credential.rejected();
            held = credential.hold(secure);
            reply = send(op, args, idempotencyKey, held, retry);
        }
        List<String> hide = new java.util.ArrayList<>(fixedSecrets);
        if (held != null) hide.addAll(held.toHide());
        Redactor hiding = new Redactor(hide);
        if (held != null && refusedCredential(reply))
            throw new CredentialRejected(
                    "the server refused the credential for "
                            + tool
                            + " (HTTP "
                            + reply.status()
                            + "); get a new or wider one and resume. "
                            + challenge(reply, hiding));
        String text = hiding.scrub(Answers.render(tool, reply, answers));
        if (effect && !reply.success()) throw new ToolRefusal(Limits.excerpt(text, ERROR_EXCERPT_CHARS));
        return text;
    }

    private static boolean refusedCredential(HttpSupport.Reply reply) {
        if (reply.status() == 401) return true;
        String challenge = reply.headers().get("WWW-Authenticate");
        return reply.status() == 403
                && challenge != null
                && challenge.toLowerCase(Locale.ROOT).contains("insufficient_scope");
    }

    private static String challenge(HttpSupport.Reply reply, Redactor hiding) {
        String challenge = reply.headers().get("WWW-Authenticate");
        if (challenge == null) return "The server sent no WWW-Authenticate challenge.";
        return "The server's challenge: "
                + Limits.excerpt(hiding.scrub(challenge), CHALLENGE_CHARS);
    }

    private HttpSupport.Reply send(
            ApiOperation op,
            Map<String, Object> args,
            String idempotencyKey,
            Credential.Held held,
            HttpSupport.Retry retry) {
        HttpUrl.Builder url = url(op, args);
        for (ApiOperation.Parameter p : op.parameters()) {
            if (!p.location().equals("query")) continue;
            Object value = args.get(p.argument());
            if (value instanceof Iterable<?> many)
                many.forEach(item -> url.addQueryParameter(p.name(), String.valueOf(item)));
            else if (value != null) url.addQueryParameter(p.name(), String.valueOf(value));
        }
        if (held != null && held.inQuery()) url.addQueryParameter(held.name(), held.secret());
        Request.Builder request = new Request.Builder().url(url.build());
        request.header("Accept", "application/json, text/plain;q=0.9, */*;q=0.1");
        try {
            for (ApiOperation.Parameter p : op.parameters()) {
                Object value = args.get(p.argument());
                if (p.location().equals("header") && value != null)
                    request.header(p.name(), String.valueOf(value));
            }
            if (held != null && !held.inQuery()) request.header(held.name(), held.secret());
        } catch (IllegalArgumentException e) {
            throw new ToolRefusal("a header value is not allowed (it has a line break or control character)");
        }
        if (!op.isRead() && net.idempotency() && idempotencyKey != null)
            request.header("Idempotency-Key", idempotencyKey);
        request.method(op.method(), body(op, args.get("body")));
        return http.send(request.build(), retry);
    }

    /** The address of the call: the base, then the path with its parameters percent-encoded. */
    private HttpUrl.Builder url(ApiOperation op, Map<String, Object> args) {
        HttpUrl.Builder b = base.newBuilder().encodedPath("/");
        for (String segment : base.pathSegments()) if (!segment.isEmpty()) b.addPathSegment(segment);
        for (String template : op.path().split("/")) {
            if (template.isEmpty()) continue;
            String segment = template;
            for (ApiOperation.Parameter p : op.parameters()) {
                if (!p.location().equals("path")) continue;
                String marker = "{" + p.name() + "}";
                if (!segment.contains(marker)) continue;
                Object value = args.get(p.argument());
                if (value == null) throw new ToolRefusal(p.argument() + " is required");
                segment = segment.replace(marker, String.valueOf(value));
            }
            if (segment.equals(".") || segment.equals("..") || segment.chars().anyMatch(c -> c < 0x20))
                throw new ToolRefusal(
                        "a path argument is not allowed (it would climb out of the address or has a control"
                                + " character)");
            b.addPathSegment(segment);
        }
        return b;
    }

    private static RequestBody body(ApiOperation op, Object given) {
        if (op.body() != null && given != null) {
            if (op.isRead()) throw new ToolRefusal(op.method() + " requests don't take a body");
            if (op.body().contentType().equals("application/x-www-form-urlencoded")) {
                if (!(given instanceof Map<?, ?> fields))
                    throw new ToolRefusal("body must be an object of fields");
                FormBody.Builder form = new FormBody.Builder();
                fields.forEach((k, v) -> form.add(String.valueOf(k), String.valueOf(v)));
                return form.build();
            }
            return RequestBody.create(
                    json(op.body().schema(), given), MediaType.get("application/json; charset=utf-8"));
        }
        // POST, PUT and PATCH need a body object even when empty; the others send none
        boolean needsOne = !op.isRead() && !op.method().equals("DELETE");
        return needsOne ? RequestBody.create(new byte[0], null) : null;
    }

    /** The body as JSON; a text that is already JSON where the schema wants an object or list. */
    private static String json(JsonNode schema, Object given) {
        try {
            String type = schema == null ? "" : schema.path("type").asText("");
            if (given instanceof String text && (type.equals("object") || type.equals("array"))) {
                JSON.readTree(text); // refuses text that is not JSON
                return text;
            }
            return JSON.writeValueAsString(given);
        } catch (JsonProcessingException e) {
            throw new ToolRefusal("body can't be written as JSON");
        }
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }
}
