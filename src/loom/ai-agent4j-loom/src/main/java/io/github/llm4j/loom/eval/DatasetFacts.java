package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalDataset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What {@code weave next} needs to know about a project's golden dataset, read without running
 * anything: whether there is one, the first thing wrong with it, and how many cases an assistant
 * drafted that no person has reviewed.
 *
 * @param present whether the folder holds at least one dataset file
 * @param firstProblem the first problem found, or null
 * @param unreviewed cases drafted by an assistant and not yet reviewed by a person
 */
public record DatasetFacts(boolean present, String firstProblem, long unreviewed) {

    /** The facts of the dataset folder; "no dataset" when it does not exist or is empty. */
    public static DatasetFacts of(Path golden) {
        if (!Files.isDirectory(golden)) return new DatasetFacts(false, null, 0);
        EvalDataset dataset = EvalDataset.load(golden);
        if (dataset.files().isEmpty()) return new DatasetFacts(false, null, 0);
        String problem = dataset.problems().isEmpty() ? null : dataset.problems().get(0).toString();
        long unreviewed =
                dataset.files().values().stream()
                        .flatMap(java.util.List::stream)
                        .filter(Authorship::needsReview)
                        .count();
        return new DatasetFacts(true, problem, unreviewed);
    }
}
