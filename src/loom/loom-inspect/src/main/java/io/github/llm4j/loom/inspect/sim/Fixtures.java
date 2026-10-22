package io.github.llm4j.loom.inspect.sim;

import io.github.llm4j.loom.inspect.http.InspectServer;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.WindowBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Serves the page over runs made by the simulator's {@link Scenarios}, on a free port, with nothing
 * else running: the fixture of the page's browser tests, and a quick way to look at each situation.
 * Each scenario is its own process name on the page (so the tabs read {@code flaky-tool / Main}).
 *
 * <pre>
 * java -cp weave-cli.jar:loom-inspect.jar:loom-inspect-core.jar io.github.llm4j.loom.inspect.sim.Fixtures --scenario flaky-tool,task-broken [--windows "model = 3000"] [--port 8787]
 * </pre>
 *
 * It prints {@code READY <port>} when the page is up, and serves until it is stopped.
 */
public final class Fixtures {

    private Fixtures() {}

    public static void main(String[] argv) throws Exception {
        String scenarios = null;
        String windows = null;
        int port = 0;
        boolean fabric = false;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--scenario" -> scenarios = argv[++i];
                case "--windows" -> windows = argv[++i];
                case "--fabric" -> fabric = true;
                case "--port" -> port = Integer.parseInt(argv[++i]);
                default -> {
                    System.err.println(
                            "usage: Fixtures --scenario a,b [--windows \"model = tokens\"] [--fabric] [--port N]");
                    System.exit(2);
                }
            }
        }
        List<Scenarios.Scenario> chosen =
                scenarios == null
                        ? Scenarios.parse(String.join(",", Scenarios.names()))
                        : Scenarios.parse(scenarios);
        RunRegistry registry = new RunRegistry();
        if (windows != null) {
            WindowBook.Parsed w = WindowBook.parse(windows.replace(',', '\n'));
            if (!w.ok()) {
                System.err.println("--windows: " + w.problems().get(0));
                System.exit(2);
            }
            registry.setWindows(w.book());
        }
        Path dir = Files.createTempDirectory("loom-fixtures");
        for (Scenarios.Scenario s : chosen) {
            Scenarios.Outcome o = Scenarios.runInto(registry, s.name(), s, "run-" + s.name(), dir);
            System.err.println("fixture " + s.name() + ": " + o.message());
        }
        // --fabric answers /api/processes the way a Fabric server does, so the page shows its
        // Fabric face
        InspectServer server =
                fabric
                        ? new InspectServer(
                                registry,
                                InspectServer.config()
                                        .bind("127.0.0.1", port)
                                        .api(
                                                "processes",
                                                () ->
                                                        java.util.Map.of(
                                                                "processes", java.util.List.of())))
                        : new InspectServer(registry, "127.0.0.1", port);
        server.start();
        System.out.println("READY " + server.port());
        System.out.flush();
        Thread.currentThread().join();
    }
}
