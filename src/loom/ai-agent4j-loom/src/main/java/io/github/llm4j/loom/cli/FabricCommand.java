package io.github.llm4j.loom.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave fabric up | down | status}: a local Fabric server without remembering its jar, its
 * options or where its password went. {@code weave} only launches the jar as a separate process.
 */
@Command(
        name = "fabric",
        mixinStandardHelpOptions = true,
        description =
                "Starts, stops and inspects a local Fabric server (the page that shows every observed run). For this computer only; deploying is `weave deploy`.",
        subcommands = {FabricCommand.Up.class, FabricCommand.Down.class, FabricCommand.Status.class})
final class FabricCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /** The options every subcommand shares. */
    static class Where {
        @Option(
                names = "--data",
                paramLabel = "<folder>",
                description = "The server's data folder (default: ./fabric-data).")
        Path data = Path.of("fabric-data");
    }

    @Command(
            name = "up",
            mixinStandardHelpOptions = true,
            description =
                    "Starts Fabric in the background. The first time it creates the data folder and an admin password in <data>/admin-password.txt, readable by you only; the password is never printed. A second `up` changes nothing. Listening beyond this computer needs TLS or a proxy: pass --tls-keystore, --tls-cert or --trust-proxy after `--`.")
    static final class Up extends Where implements Callable<Integer> {
        @Option(names = "--port", description = "Port to listen on (default: ${DEFAULT-VALUE}).")
        int port = 9090;

        @Option(
                names = "--bind",
                paramLabel = "<address>",
                description = "Address to listen on (default: ${DEFAULT-VALUE}).")
        String bind = "127.0.0.1";

        @Parameters(
                paramLabel = "-- <serve options>",
                description = "Further options for `fabric serve`, after `--`.")
        List<String> extra = List.of();

        @Override
        public Integer call() {
            return launcher().up(new FabricLauncher.UpOptions(data, port, bind, extra));
        }
    }

    @Command(
            name = "down",
            mixinStandardHelpOptions = true,
            description = "Stops the server of a data folder. What it holds stays on disk.")
    static final class Down extends Where implements Callable<Integer> {
        @Override
        public Integer call() {
            return launcher().down(data);
        }
    }

    @Command(
            name = "status",
            mixinStandardHelpOptions = true,
            description =
                    "Says whether the server runs, where, and for each client that may report when it last did (names and times only, never keys).")
    static final class Status extends Where implements Callable<Integer> {
        @Option(names = "--port", description = "Port it listens on (default: ${DEFAULT-VALUE}).")
        int port = 9090;

        @Override
        public Integer call() {
            return launcher().status(data, port);
        }
    }

    private static FabricLauncher launcher() {
        return new FabricLauncher(new SystemFabricHost(), System.out, System.err);
    }
}
