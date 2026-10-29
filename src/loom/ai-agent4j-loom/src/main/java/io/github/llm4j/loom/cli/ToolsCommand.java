package io.github.llm4j.loom.cli;

import io.github.llm4j.BuildIdentity;
import io.github.llm4j.loom.install.MavenCentralFetcher;
import io.github.llm4j.loom.install.ToolsFetch;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave tools fetch}: gets the Fabric server and the inspect add-on a deployment needs,
 * for the version of this weave, from Maven Central, after asking.
 */
@Command(
        name = "tools",
        mixinStandardHelpOptions = true,
        description =
                "Gets the extra jars a deployment needs (the Fabric server, the inspect add-on) from Maven Central, after asking and checking their checksums.",
        subcommands = {ToolsCommand.Fetch.class})
final class ToolsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(
            name = "fetch",
            mixinStandardHelpOptions = true,
            description =
                    "Downloads fabric, inspect or all for this weave's version into ~/.llm4j/tools/<version>/. Lists what it will download and asks first. Refuses a development build.")
    static final class Fetch implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "fabric|inspect|all", description = "What to fetch.")
        String what;

        @Option(names = "--yes", description = "Do not ask first.")
        boolean yes;

        @Option(
                names = "--dir",
                paramLabel = "<folder>",
                description = "Where to put them (default: ~/.llm4j/tools).")
        Path dir;

        @Override
        public Integer call() {
            return fetch(this, WeaveEnv.system());
        }

        static int fetch(Fetch c, WeaveEnv env) {
            ToolsFetch.Which which;
            switch (c.what == null ? "" : c.what.toLowerCase(java.util.Locale.ROOT)) {
                case "fabric" -> which = ToolsFetch.Which.FABRIC;
                case "inspect" -> which = ToolsFetch.Which.INSPECT;
                case "all" -> which = ToolsFetch.Which.ALL;
                default -> {
                    env.err().println("Error: say what to fetch: fabric, inspect or all.");
                    return 2;
                }
            }
            Path dir =
                    c.dir != null
                            ? c.dir
                            : Path.of(System.getProperty("user.home"), ".llm4j", "tools");
            return ToolsFetch.fetch(
                    which,
                    BuildIdentity.ofClass(WeaveCLI.class),
                    dir,
                    new MavenCentralFetcher(),
                    env.human(),
                    c.yes,
                    env.out(),
                    env.err());
        }
    }
}
