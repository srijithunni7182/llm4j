package io.github.llm4j.loom.channel;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

/** Opens the channel a store is configured for, checking at once that it can be used. */
public final class Channels {

    private static final Logger log = Logger.getLogger(Channels.class.getName());

    private Channels() { }

    /** Everything that works with one store's questions. */
    public record Runtime(ChannelConfig config, Channel channel, PendingStore pending, Answers answers, Dispatch dispatch, Listener listener, Clock clock) {

        /** The person a run in {@code runDir} asks. */
        public HumanInterface humanFor(Path runDir, String runId) {
            return new ChannelHumanInterface(pending, dispatch, runDir.toAbsolutePath().normalize().toString(), runId, clock);
        }

        /** Listens until {@code stop}: long polls, and on a failure waits longer each time (never giving up). */
        public void listen(Duration poll, AtomicBoolean stop, Function<Duration, Duration> backoff) {
            Duration delay = Duration.ZERO;
            while (!stop.get()) {
                try {
                    listener.maintain();
                    listener.pollOnce(poll);
                    delay = Duration.ZERO;
                } catch (IOException | RuntimeException e) {
                    delay = backoff.apply(delay);
                    log.warning("The channel failed (" + e.getMessage() + "); trying again in " + delay.toSeconds() + "s");
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** @return empty for the console; throws {@link IllegalArgumentException} with what is missing when the channel can't be used */
    public static Optional<Runtime> open(Path store, String askVia, Function<String, String> env, Clock clock) {
        Optional<ChannelConfig> cfg = ChannelConfig.load(store, askVia, env);
        if (cfg.isEmpty()) return Optional.empty();
        ChannelConfig config = cfg.get();
        String problem = config.problem(env);
        if (problem != null) throw new IllegalArgumentException("Cannot ask through " + config.channel() + ": " + problem + ".");
        PendingStore pending = new PendingStore(store);
        Channel channel = config.channel().equals("command")
                ? new CommandChannel(config.command())
                : new TelegramChannel(config.apiBase(), env.apply(config.tokenEnv()).strip(), pending.root().resolve("offset.json"));
        Answers answers = new Answers(pending, new FileTriggerStore(store.toAbsolutePath().normalize()), new Audit(pending, clock), clock);
        Dispatch dispatch = new Dispatch(channel, config, pending, clock);
        return Optional.of(new Runtime(config, channel, pending, answers, dispatch, new Listener(channel, config, pending, answers, dispatch, clock), clock));
    }

    /** The answers of a store, with no channel needed ({@code weave answer}, {@code weave questions}). */
    public static Answers answersFor(Path store, Clock clock) {
        PendingStore pending = new PendingStore(store);
        return new Answers(pending, new FileTriggerStore(store.toAbsolutePath().normalize()), new Audit(pending, clock), clock);
    }
}
