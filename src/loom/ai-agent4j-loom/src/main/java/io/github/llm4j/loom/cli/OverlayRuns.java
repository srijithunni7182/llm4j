package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.overlay.RunOverlay;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.ScriptHash;
import java.nio.file.Path;
import java.util.Set;

/** Reads what a run did from its directory, for {@code weave graph --run}. */
final class OverlayRuns {

    private OverlayRuns() {}

    /**
     * The run in {@code dir} as the overlay wants it. Throws {@link IllegalArgumentException} with
     * words for the person when the directory is not a run of this script.
     */
    static RunOverlay.Run read(Path dir, Path script) throws java.io.IOException {
        Path run = dir.toAbsolutePath().normalize();
        RunSpec spec = RunSpec.read(run);
        String recorded = RunSpec.scriptHashOf(run);
        if (recorded != null && !recorded.equals(ScriptHash.of(script)))
            throw new IllegalArgumentException(
                    run.getFileName()
                            + " ran a different version of "
                            + script.getFileName()
                            + ", so its path would not be a path through this graph.");
        FileRunJournal journal = new FileRunJournal(run.resolve(TravelCommands.JOURNAL));
        ObserveReplay.Result result =
                ObserveReplay.run(
                        new ObserveReplay.Options(script, spec.workflow(), spec.inputs(), Set.of()),
                        journal);
        return io.github.llm4j.loom.overlay.RunOverlays.of(run.getFileName().toString(), result);
    }
}
