package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;

import java.time.Duration;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Sends one message to a webhook. */
final class WebhookTool extends GenericTool {

    private static final int MAX_TEXT = 20_000;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** A webhook declaration, parsed and checked. */
    record Config(HttpUrl url, BodyFormat format, NetOptions net, Map<String, String> headers) {

        static Config parse(Options o) {
            String raw = o.require("url");
            HttpUrl url = HttpUrl.parse(raw);
            if (url == null) throw new OptionException("url: is not a valid URL");
            BodyFormat format = BodyFormat.of(o.choice("format", "slack", "slack", "discord", "teams", "json"));
            NetOptions net = NetOptions.parse(o, 2, false);
            String problem = new NetPolicy(net.rules(), NetPolicy.Resolver.SYSTEM, url.host()).checkUrl(url);
            if (problem != null) throw new OptionException("url: " + problem);
            return new Config(url, format, net, o.headers());
        }
    }

    private final Config config;
    private final HttpSupport http;

    WebhookTool(String name, Config config, HttpSupport http, Redactor redactor, EffectContext context) {
        super(name, "webhook", description(config), redactor, context);
        this.config = config;
        this.http = http;
    }

    private static String description(Config c) {
        return "Posts a message to a " + c.format().label + " webhook. Arguments: text (required, at most "
                + MAX_TEXT + " characters), title (optional). Returns a confirmation or an Error. "
                + "The destination is fixed; you can't change it.";
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return true;
    }

    @Override
    public EffectPolicy policy() {
        return config.net().policy(0);
    }

    @Override
    public String target(Map<String, Object> args) {
        return config.url().host();
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        String text = text(args, "text");
        if (text.length() > MAX_TEXT) throw new ToolRefusal("text is too long (" + text.length() + " characters; the limit is " + MAX_TEXT + ")");
        String title = optionalText(args, "title");
        if (title != null && (title.indexOf('\r') >= 0 || title.indexOf('\n') >= 0)) throw new ToolRefusal("title must be a single line");

        Request.Builder request = new Request.Builder().url(config.url())
                .post(RequestBody.create(config.format().render(title, text), JSON));
        config.headers().forEach(request::header);
        if (config.net().idempotency() && idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);

        HttpSupport.Reply reply = http.send(request.build(), HttpSupport.Retry.UNSENT_AND_STATUS);
        if (reply.success()) return "Sent to " + config.format().label + " webhook (HTTP " + reply.status() + ").";
        if (reply.status() == 429) {
            Duration wait = http.retryAfter(reply).orElse(null);
            throw new ToolRefusal("the webhook is rate limited" + (wait == null ? "" : "; retry after " + wait.toSeconds() + "s"));
        }
        if (reply.status() >= 300 && reply.status() < 400) {
            throw new ToolRefusal("the webhook answered with a redirect (HTTP " + reply.status() + "), which is not followed; the message was not delivered");
        }
        throw new ToolRefusal("the webhook answered HTTP " + reply.status() + " " + reply.reason() + ": "
                + Limits.excerpt(reply.bodyText(), 200));
    }
}
