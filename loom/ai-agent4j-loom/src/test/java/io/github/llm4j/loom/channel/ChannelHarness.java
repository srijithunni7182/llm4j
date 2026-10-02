package io.github.llm4j.loom.channel;

import io.github.llm4j.loom.trigger.InMemoryTriggerStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A store, a fake Telegram, a clock and everything wired the way {@link Channels#open} wires it, but with the pieces in reach of a test. */
public final class ChannelHarness implements AutoCloseable {

    public static final long ME = 5550001L;
    public static final Instant T0 = Instant.parse("2026-04-01T08:00:00Z");

    public final FakeTelegram telegram;
    public final io.github.llm4j.loom.autonomy.MutableClock clock = new io.github.llm4j.loom.autonomy.MutableClock(T0);
    public final Path store;
    public final PendingStore pending;
    public final InMemoryTriggerStore triggers = new InMemoryTriggerStore();
    public final Audit audit;
    public final Answers answers;
    public final Channels.Runtime runtime;

    public ChannelHarness(Path store) throws IOException {
        this(store, ChannelConfig.DEFAULT_TOKEN_ENV, Map.of("default", List.of(ME)));
    }

    public ChannelHarness(Path store, String tokenEnv, Map<String, List<Long>> chats) throws IOException {
        this.telegram = new FakeTelegram();
        this.telegram.dateSource = () -> clock.instant();
        this.store = store;
        this.pending = new PendingStore(store);
        this.audit = new Audit(pending, clock);
        this.answers = new Answers(pending, triggers, audit, clock);
        ChannelConfig config = new ChannelConfig("telegram", tokenEnv, chats, null, 0, null, List.of(), telegram.base());
        TelegramChannel channel = new TelegramChannel(telegram.base(), FakeTelegram.TOKEN, pending.root().resolve("offset.json"));
        Dispatch dispatch = new Dispatch(channel, config, pending, clock);
        this.runtime = new Channels.Runtime(config, channel, pending, answers, dispatch, new Listener(channel, config, pending, answers, dispatch, clock), clock);
    }

    /** The same wiring with another configuration (reminders, expiry, routes). */
    public Channels.Runtime runtimeWith(ChannelConfig config) {
        TelegramChannel channel = new TelegramChannel(telegram.base(), FakeTelegram.TOKEN, pending.root().resolve("offset.json"));
        Dispatch dispatch = new Dispatch(channel, config, pending, clock);
        return new Channels.Runtime(config, channel, pending, answers, dispatch, new Listener(channel, config, pending, answers, dispatch, clock), clock);
    }

    public ChannelConfig config(java.time.Duration remindEvery, int atMost, java.time.Duration expire, Map<String, List<Long>> chats) {
        return new ChannelConfig("telegram", ChannelConfig.DEFAULT_TOKEN_ENV, chats, remindEvery, atMost, expire, List.of(), telegram.base());
    }

    /** A person who is asked something from a run in {@code run}. */
    public io.github.llm4j.loom.runtime.HumanInterface asker(String run) {
        return runtime.humanFor(Path.of(run), "run:" + run);
    }

    @Override
    public void close() {
        telegram.close();
    }
}
