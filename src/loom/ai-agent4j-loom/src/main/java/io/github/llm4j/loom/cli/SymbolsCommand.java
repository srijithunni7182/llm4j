package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.symbols.SymbolIndex;
import io.github.llm4j.loom.symbols.SymbolService;
import java.io.File;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave symbols}: what a script and its imports define, where each name is used, and what
 * each variable holds, as JSON. This is the index behind go to definition, rename, completion and
 * hover in an editor. Nothing is run and nothing is written; a script with a syntax error still
 * gets an answer for the parts that read, with the problems listed.
 */
@Command(
        name = "symbols",
        description =
                "Lists the definitions, uses and variable types of a script and its imports as JSON, without running anything.")
final class SymbolsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(names = "--format", description = "json (the only format).", defaultValue = "json")
    String format;

    @Override
    public Integer call() {
        return symbols(this, WeaveEnv.system());
    }

    static int symbols(SymbolsCommand c, WeaveEnv env) {
        if (!c.format.equals("json")) {
            env.err().println("Error: --format takes json.");
            return 2;
        }
        if (!io.github.llm4j.loom.execution.Buffers.exists(c.script.toPath())) {
            env.err().println("Error: no such file: " + c.script);
            return 2;
        }
        SymbolIndex index = new SymbolService().index(c.script.toPath());
        env.out().println(index.toJson().toPrettyString());
        return 0;
    }
}
