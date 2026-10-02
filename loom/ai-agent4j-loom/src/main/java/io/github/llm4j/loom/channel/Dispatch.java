package io.github.llm4j.loom.channel;

import io.github.llm4j.loom.travel.RunTravel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.logging.Logger;

/** Puts a question in front of the people it is for, once, and remembers where it went. */
public final class Dispatch {

    private static final Logger log = Logger.getLogger(Dispatch.class.getName());

    private final Channel channel;
    private final ChannelConfig config;
    private final PendingStore store;
    private final Clock clock;

    public Dispatch(Channel channel, ChannelConfig config, PendingStore store, Clock clock) {
        this.channel = channel;
        this.config = config;
        this.store = store;
        this.clock = clock;
    }

    /** The message as a person reads it: where it comes from, the question as the step built it, and how to answer. */
    static String compose(Pending p) {
        String name = Path.of(p.run()).getFileName() == null ? p.run() : Path.of(p.run()).getFileName().toString();
        StringBuilder b = new StringBuilder();
        b.append('[').append(Text.safe(name, false)).append("] ").append(Text.safe(p.question(), true)).append("\n\n");
        b.append("Reply: ").append(p.code()).append(' ').append(p.choices().isEmpty() ? "<your answer>" : String.join(" | ", p.choices()));
        if (!p.approval()) b.append("\n(or reply directly to this message)");
        return b.toString();
    }

    /** Sends to every chat the question is for. A failure leaves it unsent for the next tick; the run is suspended either way. Returns whether it went. */
    public boolean send(Pending p) {
        List<Long> chats = channel.name().equals("command") && config.chats().isEmpty() ? List.of(0L) : config.chatsFor(p.to());
        boolean any = false;
        Channel.Outgoing out = new Channel.Outgoing(p.code(), compose(p), p.choices(), p.to(), p.approval());
        for (long chat : chats) {
            try {
                Channel.Sent sent = channel.send(String.valueOf(chat), out);
                p.delivered(new Pending.Delivery(sent.chat(), sent.ref(), clock.instant()));
                any = true;
            } catch (IOException e) {
                log.warning("Question " + p.code() + " could not be sent to " + channel.name() + " (" + RunTravel.neutralise(e.getMessage()) + "); it will be tried again at the next tick");
            }
        }
        return any;
    }

    public PendingStore store() {
        return store;
    }
}
