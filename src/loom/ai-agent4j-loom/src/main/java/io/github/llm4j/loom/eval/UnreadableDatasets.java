package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalDataset;
import java.nio.file.Path;
import java.util.List;

/**
 * The files of a golden-dataset folder that eval4j cannot read at all (the eval feature owns
 * eval4j).
 */
public final class UnreadableDatasets {

    private UnreadableDatasets() {}

    /**
     * @param file the file name in the folder; @param message why it cannot be read
     */
    public record Unreadable(String file, String message) {}

    public static List<Unreadable> in(Path goldenFolder) {
        return EvalDataset.load(goldenFolder).problems().stream()
                .filter(p -> p.fatal() && p.file() != null)
                .map(p -> new Unreadable(p.file(), p.message()))
                .toList();
    }
}
