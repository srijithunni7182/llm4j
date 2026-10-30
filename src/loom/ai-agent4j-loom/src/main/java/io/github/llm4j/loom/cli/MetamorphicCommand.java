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
                "Derives metamorphic cases from a golden dataset; it calls no model. An irrelevant addition, a reordering and a paraphrase must not change the conclusion, so each derived case keeps the original's expectations. Monotonicity (remove the evidence a case marks with the tag decisive:<text>) and negation (flip it, as the tag negate:<from>=><to> says) must change it, so the derived case forbids the original conclusion. Paraphrase takes the reworded inputs from --paraphrases, written by a person or a model, and tags the cases source:synthetic. Each derived case is tagged relation:<name> and pair:<id>. Writes the originals and the derived cases to --out.")
final class MetamorphicCommand implements Callable<Integer> {

    @Option(names = "--from", required = true, paramLabel = "<dir>", description = "The dataset folder (eval/golden).")
    File from;

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Where to write the dataset with the derived cases added. It must be empty or new.")
    File out;

    @Option(names = "--relations", defaultValue = "irrelevant,order", description = "irrelevant, order, paraphrase, monotonicity, negation (default: ${DEFAULT-VALUE}).")
    String relations;

    @Option(names = "--paraphrases", paramLabel = "<file>", description = "For the paraphrase relation: a JSON or YAML map from a case id to its reworded input.")
    File paraphrases;

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
        java.util.Map<String, String> reworded = java.util.Map.of();
        if (wanted.contains(Metamorphic.Relation.PARAPHRASE)) {
            if (c.paraphrases == null) {
                env.err().println("Error: the paraphrase relation needs --paraphrases <file>: a map from a case id to its reworded input, written by a person or a model.");
                return 2;
            }
            try {
                reworded = readParaphrases(c.paraphrases.toPath());
            } catch (IOException | RuntimeException e) {
                env.err().println("Error: " + c.paraphrases + ": " + e.getMessage() + ".");
                return 2;
            }
        }
        if (!c.from.isDirectory()) {
            env.err().println("Error: " + c.from + " is not a dataset folder.");
            return 2;
        }
        Path target = c.out.toPath();
        try {
            io.github.llm4j.loom.eval.Metamorphic.Written w =
                    io.github.llm4j.loom.eval.Metamorphic.deriveFolder(c.from.toPath(), target, wanted, reworded);
            env.out().println("Derived " + w.derived() + " case" + (w.derived() == 1 ? "" : "s") + " into " + target + " (originals kept, each derived case tagged relation:<name>, pair:<id> and derived:metamorphic).");
            w.skipped().forEach(s -> env.out().println("  skipped: " + s));
            env.out().println("Run them with: weave eval <script> --dataset " + target + "  (a pair that passes alone and fails perturbed is named in the Cautions).");
            return 0;
        } catch (IOException | RuntimeException e) {
            env.err().println("Error: " + e.getMessage() + (e instanceof IllegalArgumentException ? "." : ""));
            return 2;
        }
    }

    private static java.util.Map<String, String> readParaphrases(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        var mapper = name.endsWith(".yaml") || name.endsWith(".yml") ? new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()) : new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(file.toFile());
        if (root == null || !root.isObject()) throw new IllegalArgumentException("a map from a case id to its reworded input was expected");
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        root.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
