package io.github.llm4j.loom.channel;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads replies from the channel and records them as answers; keeps unsent questions moving, reminds, and expires. */
public final class Listener {

    private static final Logger log = Logger.getLogger(Listener.class.getName());
    private static final Pattern LEADING_CODE = Pattern.compile("^#?([A-Za-z0-9]{" + Codes.LENGTH + "})(?:\\s+(.*))?$", Pattern.DOTALL);

    /** What one poll did. */
    public record Summary(int recorded, int refused, int ignored) { }

    private final Channel channel;
    private final ChannelConfig config;
    private final PendingStore store;
    private final Answers answers;
    private final Dispatch dispatch;
    private final Clock clock;
    private boolean noted;
    private int ignoredTotal;

    public Listener(Channel channel, ChannelConfig config, PendingStore store, Answers answers, Dispatch dispatch, Clock clock) {
        this.channel = channel;
        this.config = config;
        this.store = store;
        this.answers = answers;
        this.dispatch = dispatch;
        this.clock = clock;
    }

    /** How many replies from people who may not answer have been ignored so far. */
    public int ignoredTotal() {
        return ignoredTotal;
    }

    public Summary pollOnce(Duration wait) throws IOException {
        Channel.Batch batch = channel.poll(wait);
        int recorded = 0;
        int refused = 0;
        int ignored = 0;
        for (Channel.Reply reply : batch.replies()) {
            if (!allowed(reply)) {
                ignored++;
                ignoredTotal++;
                if (!noted) {
                    noted = true;
                    log.warning("A reply from a chat or user that may not answer was ignored (this is noted once)");
                }
                continue;
            }
            Handled h = handle(reply);
            if (h.recorded) recorded++;
            else refused++;
            try {
                channel.tell(reply.chat(), h.message);
            } catch (IOException e) {
                log.warning("Could not send a note back to " + channel.name() + ": " + e.getMessage());
            }
        }
        channel.acknowledge(batch.cursor());
        return new Summary(recorded, refused, ignored);
    }

    private boolean allowed(Channel.Reply reply) {
        Set<Long> ok = config.allowedIds();
        try {
            return ok.contains(Long.parseLong(reply.chat())) && ok.contains(Long.parseLong(reply.sender()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private record Handled(boolean recorded, String message) { }

    private Handled handle(Channel.Reply reply) {
        String text = Text.safe(reply.text() == null ? "" : reply.text().strip(), false);
        List<Pending> open = store.open();
        String codeToken = null;
        String rest = text;
        Matcher m = LEADING_CODE.matcher(text);
        if (m.matches() && store.get(m.group(1)).isPresent()) {
            codeToken = m.group(1).toUpperCase(Locale.ROOT);
            rest = m.group(2) == null ? "" : m.group(2).strip();
        }
        Pending target = null;
        if (reply.replyToRef() != null) {
            target = open.stream().filter(p -> p.sent().stream().anyMatch(d -> d.chat().equals(reply.chat()) && d.ref().equals(reply.replyToRef()))).findFirst().orElse(null);
        }
        if (target == null && codeToken != null) target = store.get(codeToken).orElse(null);
        if (target == null && codeToken == null && open.size() == 1) target = open.get(0);
        if (target == null) return new Handled(false, help(open, codeToken));
        boolean codeGiven = codeToken != null && codeToken.equals(target.code());
        if (target.approval() && !codeGiven) {
            return new Handled(false, "This one needs the code in the reply, e.g. \"" + target.code() + " " + (target.choices().isEmpty() ? "yes" : target.choices().get(0)) + "\".");
        }
        String answer = codeGiven ? rest : text;
        Answers.Outcome o = answers.record(target.code(), answer, channel.name() + ":" + reply.sender());
        return new Handled(o.recorded(), o.message());
    }

    private static String help(List<Pending> open, String codeToken) {
        if (open.isEmpty()) return codeToken != null ? "That question is no longer open." : "Nothing is waiting for an answer.";
        StringBuilder b = new StringBuilder("Which question? Reply with its code first, e.g. \"" + open.get(0).code() + " "
                + (open.get(0).choices().isEmpty() ? "your answer" : open.get(0).choices().get(0)) + "\". Open:");
        for (Pending p : open) {
            String line = Text.safe(p.question(), false);
            b.append('\n').append(p.code()).append(": ").append(Text.cut(line, 80));
        }
        return b.toString();
    }

    /** Sends what is unsent, reminds what has waited, expires what has waited too long. */
    public void maintain() {
        Instant now = clock.instant();
        for (Pending p : store.open()) {
            if (config.expire() != null && p.createdAt().plus(config.expire()).isBefore(now)) {
                answers.expire(p);
                tellAll(p, "The question " + p.code() + " expired without an answer; the run carried on.");
                continue;
            }
            if (p.sent().isEmpty()) {
                store.locked(() -> {
                    Pending fresh = store.get(p.code()).orElse(p);
                    if (fresh.open() && fresh.sent().isEmpty() && dispatch.send(fresh)) store.put(fresh);
                    return null;
                });
                continue;
            }
            Instant last = p.lastSentAt();
            if (config.remindEvery() != null && p.reminders() < config.remindAtMost() && last != null && last.plus(config.remindEvery()).isBefore(now)) {
                store.locked(() -> {
                    Pending fresh = store.get(p.code()).orElse(p);
                    if (fresh.open() && dispatch.send(fresh)) {
                        fresh.reminded();
                        store.put(fresh);
                    }
                    return null;
                });
            }
        }
    }

    private void tellAll(Pending p, String text) {
        for (long chat : config.chatsFor(p.to())) {
            try {
                channel.tell(String.valueOf(chat), text);
            } catch (IOException e) {
                log.warning("Could not send a note to " + channel.name() + ": " + e.getMessage());
            }
        }
    }

    Optional<Pending> openByCode(String code) {
        return store.get(code).filter(Pending::open);
    }
}
