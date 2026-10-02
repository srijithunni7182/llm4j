package io.github.llm4j.loom.channel;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Where questions go and replies come from. The runtime, the pending records and the commands know only this interface; a channel for another
 * service (Slack, WhatsApp, e-mail) implements these four operations and nothing else changes.
 */
public interface Channel {

    /** What is sent: the code the reply must carry, the text as the step built it, the words to reply with. */
    record Outgoing(String code, String text, List<String> choices, String to, boolean approval) { }

    /** Where a question went, so a reply to that message can be matched. */
    record Sent(String chat, String ref, Instant at) { }

    /** A reply: where it came from, who sent it, what it says, and the message it answers (or null). */
    record Reply(String chat, String sender, String text, String replyToRef) { }

    /** Replies read since the last acknowledgement; {@code cursor} says how far, so a restart neither repeats nor skips. */
    record Batch(List<Reply> replies, String cursor) {
        public Batch {
            replies = replies == null ? List.of() : List.copyOf(replies);
        }

        public static Batch empty() {
            return new Batch(List.of(), null);
        }
    }

    String name();

    /** Sends a question to one chat. */
    Sent send(String chat, Outgoing question) throws IOException;

    /** Replies since the last {@link #acknowledge}. {@code wait} is how long to hold the connection open for one (zero from {@code weave tick}). */
    Batch poll(Duration wait) throws IOException;

    /** Called after every reply of a batch has been recorded: only now may the channel forget them. */
    void acknowledge(String cursor) throws IOException;

    /** A short note back to a chat (a confirmation, help). */
    void tell(String chat, String text) throws IOException;
}
