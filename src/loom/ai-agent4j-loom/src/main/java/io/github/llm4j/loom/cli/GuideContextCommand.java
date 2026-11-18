package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code weave guide-context}: the compact summary of a project that the Guide reads at the start
 * of a turn. Free: it calls no model and reads no key's value.
 */
@Command(
        name = "guide-context",
        description =
                "Prints, as JSON, the compact summary of a project the Guide reads: the journey with what each stage is for, the agents and tools with their effects, the findings, the budget, the skips and the feature moments that apply. Names and numbers only; free.")
final class GuideContextCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<folder or script>", description = "The project (default: here).")
    File where;

    @Override
    public Integer call() {
        return run(where, WeaveEnv.system());
    }

    static int run(File where, WeaveEnv env) {
        StringBuilder why = new StringBuilder();
        Path script = ProjectAdvisor.find((where == null ? new File(".") : where).toPath().toAbsolutePath().normalize(), why);
        if (script == null) {
            env.err().println(why);
            return 2;
        }
        env.out().println(json(GuideContext.of(script, env)));
        return 0;
    }

    static String json(Map<String, Object> m) {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The summary could not be written as JSON", e);
        }
    }
}
