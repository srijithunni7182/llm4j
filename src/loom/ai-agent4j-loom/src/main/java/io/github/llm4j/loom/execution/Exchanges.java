package io.github.llm4j.loom.execution;

import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelContext;
import io.github.llm4j.a2a.ExchangeGuard;
import io.github.llm4j.a2a.ReceivedFileSink;
import io.github.llm4j.loom.ast.AgentChannelDef;
import io.github.llm4j.model.MediaPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the runtime owns around a consumer's {@link AgentChannel}: one {@link ExchangeGuard} per run
 * and step (so the question limit holds across retries), the timeout, the size of replies and the
 * file sink. A channel the host did not supply fails closed, with a message that says so.
 */
final class Exchanges {

    /** The largest reply the runtime accepts from a channel. */
    static final int MAX_REPLY_BYTES = 64 * 1024;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(1);

    private final HarnessExecutor executor;
    private final Map<String, ExchangeGuard> guards = new ConcurrentHashMap<>();

    Exchanges(HarnessExecutor executor) {
        this.executor = executor;
    }

    /** The implementation the host registered for a declared channel; fails closed when there is none. */
    AgentChannel channel(String name) {
        AgentChannel channel = executor.agentChannel(name);
        if (channel == null)
            throw new StepFailure(
                    "the host has not supplied an implementation for agent_channel "
                            + name
                            + "; register one under that name (setAgentChannel). Nothing was sent.",
                    null);
        return channel;
    }

    ExchangeGuard guard(String stepId, String channelName) {
        AgentChannelDef def = executor.channelDef(channelName);
        return guards.computeIfAbsent(
                executor.runIdOrDefault() + "|" + stepId + "|" + channelName,
                k ->
                        new ExchangeGuard(
                                def == null ? AgentChannelDef.DEFAULT_MAX_QUESTIONS : def.getInt("max_questions", AgentChannelDef.DEFAULT_MAX_QUESTIONS),
                                timeout(def),
                                MAX_REPLY_BYTES,
                                (call, limit) -> executor.callWithTimeout(call, limit.toMillis(), "agent_channel " + channelName)));
    }

    ChannelContext context(String stepId, ChannelContext.Direction direction, String counterpart, int questionsLeft) {
        MediaPolicy policy = executor.mediaPolicy();
        int maxItems = executor.script().getMediaMaxItems() == null ? MediaPolicy.DEFAULT_MAX_ITEMS_PER_MESSAGE : executor.script().getMediaMaxItems();
        String workflow = stepId.contains("/") ? stepId.substring(0, stepId.indexOf('/')) : stepId;
        return new ChannelContext(
                executor.runIdOrDefault(),
                stepId,
                workflow,
                direction,
                counterpart,
                null,
                questionsLeft,
                new ReceivedFileSink(executor.receivedMediaRoot(), safeRunId(), policy, maxItems));
    }

    private String safeRunId() {
        return executor.runIdOrDefault().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static Duration timeout(AgentChannelDef def) {
        if (def == null || def.get("timeout") == null) return DEFAULT_TIMEOUT;
        return Durations.parse(def.get("timeout"), DEFAULT_TIMEOUT);
    }

    /** A digest of untrusted text for the journal and the trace: the text itself is never written there. */
    static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Remote text, shortened for a message: it is untrusted and may be long. */
    static String shorten(String text) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return single.length() <= 200 ? single : single.substring(0, 200) + "...";
    }
}
