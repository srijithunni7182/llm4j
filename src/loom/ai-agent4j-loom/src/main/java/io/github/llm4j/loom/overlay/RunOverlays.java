package io.github.llm4j.loom.overlay;

import io.github.llm4j.loom.travel.ObserveReplay;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A replayed run, as the overlay reads it. */
public final class RunOverlays {

    private RunOverlays() {}

    public static RunOverlay.Run of(String id, ObserveReplay.Result result) {
        List<RunOverlay.Choice> choices =
                result.decisions().stream()
                        .map(
                                d ->
                                        new RunOverlay.Choice(
                                                d.step(),
                                                d.kind(),
                                                d.condition(),
                                                d.result(),
                                                d.taken()))
                        .toList();
        Set<String> failed = new LinkedHashSet<>();
        result.journal()
                .forEach(
                        (key, entry) -> {
                            int hash = key.indexOf('#');
                            if (hash < 0 && "failed".equals(entry.kind())) failed.add(key);
                            else if (hash >= 0 && key.endsWith("#failure"))
                                failed.add(key.substring(0, hash));
                        });
        return new RunOverlay.Run(
                id, result.steps(), choices, failed, "completed".equals(result.status()));
    }
}
