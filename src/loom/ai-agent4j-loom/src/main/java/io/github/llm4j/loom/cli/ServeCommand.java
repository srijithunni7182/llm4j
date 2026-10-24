package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.Server;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code weave serve --stdio}: one long-lived process that answers an editor's requests (check,
 * graph, review, explain a rule) from a cache, so feedback while typing does not pay for a process
 * start each time. It runs nothing and writes nothing; see {@link Server} for the protocol.
 */
@Command(
        name = "serve",
        description =
                "Serves check, graph and review to an editor over standard input and output (JSON-RPC with Content-Length framing). Runs nothing, writes nothing.")
final class ServeCommand implements Callable<Integer> {

    @Option(names = "--stdio", description = "Talk over standard input and output (the only mode).")
    boolean stdio;

    @Option(
            names = "--timeout-ms",
            description = "Longest a request may take (default ${DEFAULT-VALUE}).",
            defaultValue = "10000")
    long timeoutMillis;

    @Option(
            names = "--memory-mb",
            description = "Memory the kept answers may use (default ${DEFAULT-VALUE}).",
            defaultValue = "64")
    long memoryMb;

    @Override
    public Integer call() throws Exception {
        if (!stdio) {
            System.err.println("Error: weave serve needs --stdio.");
            return 2;
        }
        // standard output carries the protocol and nothing else: anything else that prints must
        // go to standard error
        InputStream in = System.in;
        PrintStream protocol = System.out;
        System.setOut(System.err);
        return serve(in, protocol, timeoutMillis, memoryMb * 1024 * 1024);
    }

    static int serve(InputStream in, java.io.OutputStream out, long timeoutMillis, long maxBytes)
            throws Exception {
        Server server =
                new Server(
                        new CliEngine(new ResultCache(maxBytes), WeaveCLI.versionNumber()),
                        timeoutMillis);
        return server.serve(in, out);
    }
}
