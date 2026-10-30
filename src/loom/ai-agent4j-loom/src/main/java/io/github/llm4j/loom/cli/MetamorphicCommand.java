package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.eval.Metamorphic;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code weave metamorphic}: derives cases that test a relation between two runs from a dataset,
 * with no model. The originals and the derived cases are written to another folder, so the
 * dataset you gate on is not changed under you; point {@code weave eval --dataset} at it.
 */
@Command(
        name = "metamorphic",
        mixinStandardHelpOptions = true,
        description =
                "Derives metamorphic cases from a golden dataset with no model: an irrelevant addition and a reordering of the input must not change the conclusion, so each derived case keeps the original's expectations and is tagged relation:<name> and pair:<id>. Writes the originals and the derived cases to --out. Paraphrase, monotonicity and negation need a model or the domain's evidence: write those pairs by hand.")
final class MetamorphicCommand implements Callable<Integer> {

    @Option(names = "--from", required = true, paramLabel = "<dir>", description = "The dataset folder (eval/golden).")
    File from;

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Where to write the dataset with the derived cases added. It must be empty or new.")
    File out;

    @Option(names = "--relations", defaultValue = "irrelevant,order", description = "irrelevant, order (default: ${DEFAULT-VALUE}).")
    String relations;

    @Override
    public Integer call() {
        return run(this, WeaveEnv.system());
    }

    static int run(MetamorphicCommand c, WeaveEnv env) {
        java.util.List<Metamorphic.Relation> wanted;
        try {
            wanted = Metamorphic.Relation.parse(c.relations);
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage() + ".");
            return 2;
        }
        if (!c.from.isDirectory()) {
            env.err().println("Error: " + c.from + " is not a dataset folder.");
            return 2;
        }
        Path target = c.out.toPath();
        try {
            io.github.llm4j.loom.eval.Metamorphic.Written w =
                    io.github.llm4j.loom.eval.Metamorphic.deriveFolder(c.from.toPath(), target, wanted);
            env.out().println("Derived " + w.derived() + " case" + (w.derived() == 1 ? "" : "s") + " into " + target + " (originals kept, each derived case tagged relation:<name>, pair:<id> and derived:metamorphic).");
            w.skipped().forEach(s -> env.out().println("  skipped: " + s));
            env.out().println("Run them with: weave eval <script> --dataset " + target + "  (a pair that passes alone and fails perturbed is named in the Cautions).");
            return 0;
        } catch (IOException | RuntimeException e) {
            env.err().println("Error: " + e.getMessage() + (e instanceof IllegalArgumentException ? "." : ""));
            return 2;
        }
    }
}
