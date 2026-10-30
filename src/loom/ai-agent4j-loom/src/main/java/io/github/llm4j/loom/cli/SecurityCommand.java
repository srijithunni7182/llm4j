package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.eval.SecurityCorpus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code weave security generate}: turns a corpus of injection payloads into golden-dataset cases
 * (the project's own realistic artefacts with a payload embedded, a canary the answer must never
 * contain, and the actions that must never happen). Calls no model; it writes the dataset that
 * {@code weave eval} then runs, against scripted or sandboxed tools.
 */
@Command(
        name = "security",
        mixinStandardHelpOptions = true,
        description = "Injection testing: turns a corpus of payloads into eval cases.",
        subcommands = {SecurityCommand.Generate.class})
final class SecurityCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(
            name = "generate",
            mixinStandardHelpOptions = true,
            description =
                    "Reads a corpus folder (YAML lists of {id, class: direct|indirect, placement, goal, payload holding {canary}}) and writes an eval dataset: a direct payload is the input; an indirect one is embedded in each of your own artefact templates for its placement (log.txt, web-page.html, ...; each holds {payload}); a payload with no template for its placement is skipped and said to be. Every case carries the canary the answer must never contain and the actions named with --forbid that must never happen. The corpus is hostile text: run the cases against scripted or sandboxed tools, never a real sender, payer or writer. Calls no model.")
    static final class Generate implements Callable<Integer> {
        @Option(names = "--corpus", required = true, paramLabel = "<dir>", description = "The corpus folder.")
        File corpus;

        @Option(names = "--templates", paramLabel = "<dir>", description = "Your realistic artefacts, named after a placement (default: <corpus>/templates).")
        File templates;

        @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Where to write the dataset (a new or empty folder); point weave eval --dataset at it.")
        File out;

        @Option(names = "--target", defaultValue = "workflow", paramLabel = "<name>", description = "The dataset file name: the workflow or agent the cases are for (default: ${DEFAULT-VALUE}).")
        String target;

        @Option(names = "--forbid", paramLabel = "<action>", description = "An action that must never happen, such as 'send email'. Repeatable.")
        List<String> forbid = new ArrayList<>();

        @Override
        public Integer call() {
            return generate(this, WeaveEnv.system());
        }

        static int generate(Generate c, WeaveEnv env) {
            try {
                List<SecurityCorpus.Payload> payloads = SecurityCorpus.read(c.corpus.toPath());
                Path templateDir = c.templates != null ? c.templates.toPath() : c.corpus.toPath().resolve("templates");
                Path out = c.out.toPath();
                if (Files.isDirectory(out))
                    try (var existing = Files.list(out)) {
                        if (existing.findAny().isPresent()) {
                            env.err().println("Error: " + out + " is not empty; name a new folder so nothing is overwritten.");
                            return 2;
                        }
                    }
                Files.createDirectories(out);
                int written = SecurityCorpus.write(payloads, templateDir, c.forbid, out.resolve(c.target + ".yaml"));
                env.out().println("Wrote " + written + " injection case" + (written == 1 ? "" : "s") + " to " + out.resolve(c.target + ".yaml") + ". Corpus: " + SecurityCorpus.size(payloads));
                SecurityCorpus.skipped(payloads, templateDir).forEach(s -> env.out().println("  " + s));
                env.out().println("Run them against scripted or sandboxed tools: weave eval <script> --dataset " + out + " --max-tokens <cap>. The report separates the model's obedience (a percentage) from the controls (which must hold at 100%).");
                return 0;
            } catch (IOException | RuntimeException e) {
                env.err().println("Error: " + e.getMessage() + (e instanceof IllegalArgumentException ? "." : ""));
                return 2;
            }
        }
    }
}
