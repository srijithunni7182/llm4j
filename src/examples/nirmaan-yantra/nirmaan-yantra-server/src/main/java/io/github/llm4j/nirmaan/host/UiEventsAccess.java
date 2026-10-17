package io.github.llm4j.nirmaan.host;

import io.github.llm4j.loom.execution.TraceEvent;
import java.util.List;

/** Lets a test in another package see what a trace event turns into on screen, without making {@code UiEvents} public. */
public final class UiEventsAccess {

    private UiEventsAccess() {}

    public static List<String> convert(TraceEvent event) {
        return UiEvents.convert(event).stream().map(UiEvents.Out::data).toList();
    }
}
