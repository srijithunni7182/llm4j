package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An {@code agent_channel} of a test case: its answers come from the case, in order. A text is a
 * reply; an object is a structured reply; {@code {refuse: "…"}} refuses; {@code {ignore: true}}
 * drops a file or link. Every question is recorded as asked, so {@code asked:} can assert on it. When
 * the answers run out the case fails (strict): a channel nobody scripted is never guessed at.
 */
final class ScriptedChannels implements AgentChannel {

    private final String name;
    private final String audience;
    private final Deque<JsonNode> answers = new ArrayDeque<>();
    private final Recorder recorder;

    ScriptedChannels(String name, String audience, JsonNode script, Recorder recorder) {
        this.name = name;
        this.audience = audience;
        this.recorder = recorder;
        if (script != null && script.isArray()) script.forEach(answers::add);
    }

    @Override
    public ChannelResponse handle(io.github.llm4j.a2a.ChannelContext context, ChannelMessage message) {
        String text;
        String kind;
        if (message instanceof ChannelMessage.Question q) {
            text = q.text();
            kind = "channel:" + audience;
        } else if (message instanceof ChannelMessage.AuthorizationNeeded a) {
            text = a.description();
            kind = "authorization";
        } else {
            text = ((ChannelMessage.FileOffered) message).label();
            kind = "file";
        }
        recorder.asked(kind, text);
        JsonNode next = answers.poll();
        if (next == null) {
            String problem = "channel " + name + " has no scripted answer left for \"" + text + "\" (add it to channels: " + name + ")";
            recorder.violation(problem);
            throw new IllegalStateException(problem);
        }
        if (next.isObject() && next.has("refuse")) return new ChannelResponse.Refuse(next.get("refuse").asText());
        if (next.isObject() && next.has("ignore")) return new ChannelResponse.Ignore();
        if (next.isObject()) return new ChannelResponse.Reply("", next);
        return new ChannelResponse.Reply(next.asText(), null);
    }
}
