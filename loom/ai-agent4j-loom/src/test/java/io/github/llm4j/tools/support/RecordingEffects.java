package io.github.llm4j.tools.support;

import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.ratelimit.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** An {@link EffectContext} that records what tools report, waits instantly, and keeps time still. */
public final class RecordingEffects implements EffectContext {

    public record Event(String name, Map<String, Object> data) { }

    public final List<Event> audit = Collections.synchronizedList(new ArrayList<>());
    public final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    public final List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
    public final Set<Path> reserved = new HashSet<>();
    public volatile String step = "main/s0";
    /** When set, the step as effects identify it (a host that can run a step again); otherwise the step itself. */
    public volatile String identity;
    public volatile boolean simulating;
    public final AtomicLong attempt = new AtomicLong(1);
    private final EffectJournal journal;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);

    public RecordingEffects() {
        this(EffectJournal.inMemory());
    }

    public RecordingEffects(EffectJournal journal) {
        this.journal = journal;
    }

    @Override public void audit(String event, Map<String, Object> data) { audit.add(new Event(event, Map.copyOf(data))); }
    @Override public void trace(String text, Map<String, Object> data) { trace.add(text); }
    @Override public EffectJournal journal() { return journal; }
    @Override public String currentStep() { return step; }
    @Override public String identityStep() { return identity != null ? identity : step; }
    @Override public boolean simulate() { return simulating; }
    @Override public long attempt() { return attempt.get(); }
    @Override public Set<Path> reservedPaths() { return reserved; }
    @Override public Sleeper sleeper() { return slept::add; }
    @Override public Clock clock() { return clock; }

    public List<Event> audited(String name) {
        synchronized (audit) {
            return audit.stream().filter(e -> e.name().equals(name)).toList();
        }
    }

    /** Everything recorded, as one string, for "no secret anywhere" assertions. */
    public String everything() {
        return audit + " " + trace + " " + journal.all();
    }
}
