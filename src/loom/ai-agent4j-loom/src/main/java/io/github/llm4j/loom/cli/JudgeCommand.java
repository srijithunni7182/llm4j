package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.eval.JudgeValidation;
import io.github.llm4j.loom.eval.PositionBias;
import io.github.llm4j.loom.eval.JudgeValidationRecord;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code weave judge validate}: is a judge fit to gate? It reads a reference set (outputs scored
 * independently by two or three people and by the judge), checks the people against each other
 * first and then the judge against their consensus, prints the result and records it so the next
 * {@code weave eval} report can say the judge was validated. It calls no model.
 */
@Command(
        name = "judge",
        mixinStandardHelpOptions = true,
        description = "Checks whether an LLM judge may gate: its scores against people's scores.",
        subcommands = {JudgeCommand.Validate.class})
final class JudgeCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(
            name = "validate",
            mixinStandardHelpOptions = true,
            description =
                    "Reads a reference file (JSON or YAML: a list of {id, human_1, human_2, human_3, judge, output}, scores on the same scale; output is the text that was scored and lets verbosity be checked) of at least 40 outputs. Checks the people against each other first (quadratic-weighted Cohen's kappa; below 0.6 the rubric is under-specified and it stops), then the judge against their consensus (below 0.6 it may not gate). Exit 0 usable, 1 not usable, 2 a problem with the file. Calls no model.")
    static final class Validate implements Callable<Integer> {
        @Option(names = "--reference", required = true, paramLabel = "<file>", description = "The scored reference set.")
        File reference;

        @Option(
                names = "--model",
                required = true,
                paramLabel = "<name>",
                description = "The judge model the scores came from (as in weave eval --judge). The record names it, and a report says validated only for this model.")
        String model;

        @Option(
                names = "--project",
                paramLabel = "<folder>",
                description = "The project: the result is recorded in <folder>/eval/judge-validation.json (default: this folder).")
        File project = new File(".");

        @Option(
                names = "--system-model",
                paramLabel = "<name>",
                description = "The model the agents run on. A judge from the same family may not gate (self-preference).")
        String systemModel;

        @Option(
                names = "--swapped",
                paramLabel = "<file>",
                description =
                        "For a judge that compares two answers: a list (JSON or YAML) of {id, first, swapped}, each pair judged twice, the second time with the answers swapped; first and swapped are the picks (A, B or tie) in the letters of each presentation. Checks that the verdict does not follow the position. A biased judge may not gate.")
        File swapped;

        @Option(names = "--min-pairs", defaultValue = "10", description = "The fewest pairs a position check may rest on (default: ${DEFAULT-VALUE}).")
        int minPairs;

        @Option(names = "--min-outputs", defaultValue = "40", description = "The fewest outputs a validation may rest on (default: ${DEFAULT-VALUE}).")
        int minOutputs;

        @Override
        public Integer call() {
            return validate(this, WeaveEnv.system());
        }

        static int validate(Validate c, WeaveEnv env) {
            List<JudgeValidation.Row> rows;
            try {
                rows = read(c.reference.toPath());
            } catch (IOException | IllegalArgumentException e) {
                env.err().println("Error: " + c.reference + ": " + e.getMessage());
                return 2;
            }
            JudgeValidation.Result result;
            try {
                result = JudgeValidation.validate(rows, c.minOutputs, c.model, c.systemModel);
            } catch (IllegalArgumentException e) {
                env.err().println("Error: " + e.getMessage() + ".");
                return 2;
            }
            if (c.swapped != null) {
                try {
                    result = result.withPosition(PositionBias.check(readPairs(c.swapped.toPath()), c.minPairs));
                } catch (IOException | IllegalArgumentException e) {
                    env.err().println("Error: " + c.swapped + ": " + e.getMessage());
                    return 2;
                }
            }
            env.out().println(result.summary());
            try {
                Path eval = c.project.toPath().resolve("eval");
                JudgeValidationRecord.write(eval, c.model, result);
                env.out().println("Recorded in " + eval.resolve("judge-validation.json") + ".");
            } catch (IOException e) {
                env.err().println("Error: could not record the result: " + e.getMessage());
                return 2;
            }
            return result.usableForGating() ? 0 : 1;
        }

        static List<PositionBias.Pair> readPairs(Path file) throws IOException {
            JsonNode root = mapper(file).readTree(file.toFile());
            if (root == null || !root.isArray())
                throw new IllegalArgumentException("a list of pairs was expected, each with an id, first and swapped");
            List<PositionBias.Pair> pairs = new ArrayList<>();
            int i = 0;
            for (JsonNode n : root) {
                i++;
                String id = n.hasNonNull("id") ? n.get("id").asText() : "#" + i;
                if (!n.hasNonNull("first") || !n.hasNonNull("swapped"))
                    throw new IllegalArgumentException("pair " + id + " needs both first and swapped");
                pairs.add(new PositionBias.Pair(id, n.get("first").asText(), n.get("swapped").asText()));
            }
            return pairs;
        }

        private static ObjectMapper mapper(Path file) {
            String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(".yaml") || name.endsWith(".yml") ? new ObjectMapper(new YAMLFactory()) : new ObjectMapper();
        }

        static List<JudgeValidation.Row> read(Path file) throws IOException {
            JsonNode root = mapper(file).readTree(file.toFile());
            if (root == null || !root.isArray())
                throw new IllegalArgumentException("a list of outputs was expected, each with human scores and a judge score");
            List<JudgeValidation.Row> rows = new ArrayList<>();
            int i = 0;
            for (JsonNode n : root) {
                i++;
                String id = n.hasNonNull("id") ? n.get("id").asText() : "#" + i;
                List<Integer> humans = new ArrayList<>();
                if (n.has("humans") && n.get("humans").isArray()) n.get("humans").forEach(h -> humans.add(h.asInt()));
                for (int k = 1; k <= 3; k++) if (n.hasNonNull("human_" + k)) humans.add(n.get("human_" + k).asInt());
                Integer judge = n.hasNonNull("judge") ? n.get("judge").asInt() : null;
                String output = n.hasNonNull("output") ? n.get("output").asText() : null;
                rows.add(new JudgeValidation.Row(id, humans.stream().mapToInt(Integer::intValue).toArray(), judge, output));
            }
            return rows;
        }
    }
}
