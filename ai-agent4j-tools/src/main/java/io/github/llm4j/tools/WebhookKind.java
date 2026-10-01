package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import okhttp3.HttpUrl;

/**
 * {@code tool Slack { use: webhook  url: env.SLACK_WEBHOOK  format: slack }}: posts a message to a chat or
 * ingest webhook. The URL is a credential, so it comes from the environment, and the agent can't change it.
 */
public final class WebhookKind extends GenericKind {

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Set<String> required() {
        return Set.of("url");
    }

    @Override
    public Set<String> optional() {
        Set<String> s = new HashSet<>(NetOptions.NAMES);
        s.add("format");
        return s;
    }

    @Override
    public Set<String> secrets() {
        return Set.of("url");
    }

    @Override
    public Set<String> prefixes() {
        return Set.of(Options.HEADER_PREFIX);
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        WebhookTool.Config.parse(o);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        WebhookTool.Config config = WebhookTool.Config.parse(o);
        HttpUrl url = config.url();
        Redactor redactor = redactor(o, url.encodedPath(), url.encodedQuery() == null ? "" : url.encodedQuery());
        NetPolicy policy = new NetPolicy(config.net().rules(), NetPolicy.Resolver.SYSTEM, url.host());
        HttpSupport http = new HttpSupport(policy, context, config.net().timeout(), 16 * 1024, config.net().retries());
        return new WebhookTool(name, config, http, redactor, context);
    }
}
