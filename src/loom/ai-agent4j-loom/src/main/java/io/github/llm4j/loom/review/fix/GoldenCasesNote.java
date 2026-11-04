package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.eval.GoldenDatasets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * What a fix that creates an extractor says about its golden cases (docs/specs/28-evaluation-first,
 * R4): a new extractor cannot pass {@code weave check} without them. The fix never writes a case
 * itself; it names the file and the command, and if the old agent had cases it says they are in
 * the agent's format and must be rewritten as exact field checks.
 */
final class GoldenCasesNote {

    private GoldenCasesNote() {}

    /** One or two sentences ending in a space, for a fix's explanation. */
    static String forNewExtractor(Path baseDir, String extractorName, String formerAgent) {
        String file = "eval/golden/" + extractorName.toLowerCase(Locale.ROOT) + ".yaml";
        Path dir = baseDir == null ? null : baseDir.resolve("eval").resolve("golden");
        boolean agentCases = dir != null && GoldenDatasets.find(dir, formerAgent).isPresent();
        String cases =
                "Golden cases are now required for the extractor: `weave check` fails until "
                        + file
                        + " has at least one case with `expected_fields` and/or `expected_missing`"
                        + " (`weave eval <script> --init` starts the file). ";
        return agentCases
                ? cases
                        + "The cases written for "
                        + formerAgent
                        + " are for an agent: rewrite those that name JSON fields as extractor cases (input text plus expected_fields). "
                : cases;
    }
}
