package io.github.llm4j.loom.channel;

import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.TriggerStore;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Records the answer to a question, once, from whoever is allowed to give it, and leaves a trigger so the run carries on. */
public final class Answers {

    /** What happened, in words a person can be told. */
    public record Outcome(boolean recorded, String message, Pending pending) { }

    private static final int MAX_FREE_TEXT = 2000;

    private final PendingStore store;
    private final TriggerStore triggers;
    private final Audit audit;
    private final Clock clock;

    public Answers(PendingStore store, TriggerStore triggers, Audit audit, Clock clock) {
        this.store = store;
        this.triggers = triggers;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param by who is answering (a channel and a user id, or an operating-system user) */
    public Outcome record(String code, String text, String by) {
        return record(code, text, by, true);
    }

    /** @param resume whether to leave a trigger that resumes the run (not when the run is being answered at its own console) */
    public Outcome record(String code, String text, String by, boolean resume) {
        return store.locked(() -> {
            Optional<Pending> found = store.get(code);
            if (found.isEmpty()) return new Outcome(false, "No question has the code " + shown(code) + ".", null);
            Pending p = found.get();
            switch (p.state()) {
                case ANSWERED -> {
                    return new Outcome(false, p.code() + " was already answered by " + p.answer().by() + " at " + p.answer().at() + ".", p);
                }
                case EXPIRED -> {
                    return new Outcome(false, p.code() + " has expired; the run carried on without an answer.", p);
                }
                default -> { }
            }
            String clean = Text.cut(Text.safe(text == null ? "" : text.strip(), false), MAX_FREE_TEXT);
            String answer = clean;
            if (!p.choices().isEmpty()) {
                answer = match(p.choices(), clean);
                if (answer == null) {
                    return new Outcome(false, "Not understood: " + p.code() + " takes one of " + String.join(", ", p.choices()) + ".", p);
                }
            } else if (clean.isEmpty()) {
                return new Outcome(false, "Not recorded: the answer to " + p.code() + " is empty.", p);
            }
            p.answered(new Pending.Answer(answer, by, clock.instant()));
            store.put(p);
            audit.record("answered", Map.of("code", p.code(), "by", by, "answer", answer, "run", p.run(), "step", p.step()));
            if (resume && triggers != null) triggers.upsert(Trigger.resume(p.runId(), clock.instant(), "answered " + p.code(), 0));
            return new Outcome(true, "Recorded: " + answer + " for " + p.code() + ".", p);
        });
    }

    /** An answer matched to a choice: exactly (any case), or as the one choice it is the start of. */
    public static String match(List<String> choices, String answer) {
        if (answer == null) return null;
        String a = answer.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return null;
        for (String c : choices) if (c.equalsIgnoreCase(a)) return c;
        List<String> starts = choices.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(a)).toList();
        return starts.size() == 1 ? starts.get(0) : null;
    }

    /** Closes a question that expired: the run is resumed and the step is handed an empty answer. */
    public void expire(Pending p) {
        store.locked(() -> {
            Pending fresh = store.get(p.code()).orElse(p);
            if (!fresh.open()) return null;
            fresh.expired();
            store.put(fresh);
            audit.record("expired", Map.of("code", fresh.code(), "run", fresh.run()));
            if (triggers != null) triggers.upsert(Trigger.resume(fresh.runId(), clock.instant(), "expired " + fresh.code(), 0));
            return null;
        });
    }

    private static String shown(String code) {
        String c = code == null ? "" : Text.safe(code, false);
        return "\"" + (c.length() > 20 ? c.substring(0, 20) + "…" : c) + "\"";
    }
}
