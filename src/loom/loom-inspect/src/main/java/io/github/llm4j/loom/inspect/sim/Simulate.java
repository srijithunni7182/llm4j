package io.github.llm4j.loom.inspect.sim;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.inspect.InspectObserveProvider;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.observe.FabricOptions;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InMemorySecretStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * A simulated workflow for exercising loom-inspect and Fabric with no model, no network and no key. It runs the real executor and the
 * real add-on over a scripted studio workflow ({@code sim.loom}): the models are scripted, the tools are fakes (one always fails), and
 * a run takes a few minutes. Everything the console can show appears on a schedule: parallel agents, tool calls and a failing tool, a
 * sleep, a loop that never converges (so a repeated call, a repeated answer, no progress and invented figures are flagged), a rewind.
 * Pause, suspend and stop from the page work on it like on a real run.
 *
 * <pre>
 * java -cp weave-cli.jar:loom-inspect.jar:loom-inspect-core.jar io.github.llm4j.loom.inspect.sim.Simulate \
 *      --fabric http://127.0.0.1:9090 --name sim-1 --key-env SIM_KEY --runs 0
 * </pre>
 */
public final class Simulate {

    private Simulate() { }

    private static final String HELP = """
            Simulated Loom workflow for trying out loom-inspect and Fabric (no model, no network).

              --fabric URL        report to this Fabric server (needs --name and the client key)
              --name NAME         the name this process has on the server (fabric clients add NAME)
              --key-env VAR       the environment variable holding the client key (default SIM_FABRIC_KEY)
              --ca FILE           a private CA certificate for an https server; --pin sha256:... pins its key
              --embedded [PORT]   also serve the page from this process (default port 8787)
              --runs N            how many runs to make, one after the other; 0 means until stopped (default 1)
              --gap SECONDS       wait between runs (default 10)
              --speed F           1.0 is a run of about four minutes; 0.2 is five times faster, 3 is slower
              --topic TEXT        the video topic (default: cold plunges and recovery)
              --prices FILE       the price table (model = input / output per million tokens); default: the simulated models' own
              --no-prices         report no cost from this process (so a price table kept on the server does the costing)
              --detail LEVEL      full, truncated (default) or metadata
              --outbox DIR        where unsent events wait (default ./loom-sim-outbox)
              --admin-password-env VAR   with --embedded, the page's admin password (from this variable) so its controls are on
              --scenario NAMES    instead of the studio workflow, run these small scenarios (comma separated), each once per run:
                                  context-fills, compaction-thrash, no-window, flaky-tool, task-diverted, task-broken, delegate-broken,
                                  latency-spike, truncated-answers, trifecta, trifecta-accepted, injected-page, access-refused
            """;

    /** The command line's help text. */
    public static String helpText() {
        return HELP;
    }

    public static void main(String[] argv) throws Exception {
        String fabric = null;
        String name = null;
        String keyEnv = "SIM_FABRIC_KEY";
        String ca = null;
        String pin = null;
        boolean embedded = false;
        int port = 8787;
        int runs = 1;
        int gap = 10;
        double speed = 1.0;
        String topic = "cold plunges and recovery";
        String detail = "truncated";
        String outbox = "loom-sim-outbox";
        String adminEnv = null;
        String prices = null;
        boolean noPrices = false;
        String scenario = null;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--fabric" -> fabric = argv[++i];
                case "--name" -> name = argv[++i];
                case "--key-env" -> keyEnv = argv[++i];
                case "--ca" -> ca = argv[++i];
                case "--pin" -> pin = argv[++i];
                case "--embedded" -> {
                    embedded = true;
                    if (i + 1 < argv.length && argv[i + 1].matches("\\d+")) port = Integer.parseInt(argv[++i]);
                }
                case "--runs" -> runs = Integer.parseInt(argv[++i]);
                case "--gap" -> gap = Integer.parseInt(argv[++i]);
                case "--speed" -> speed = Double.parseDouble(argv[++i]);
                case "--topic" -> topic = argv[++i];
                case "--detail" -> detail = argv[++i];
                case "--outbox" -> outbox = argv[++i];
                case "--prices" -> prices = argv[++i];
                case "--no-prices" -> noPrices = true;
                case "--scenario" -> scenario = argv[++i];
                case "--admin-password-env" -> adminEnv = argv[++i];
                case "-h", "--help" -> {
                    System.out.print(HELP);
                    return;
                }
                default -> {
                    System.err.println("Unknown option " + a + "\n\n" + HELP);
                    System.exit(2);
                }
            }
        }
        if (fabric == null && !embedded) {
            System.err.println("Nothing to show it on: give --fabric URL (with --name) or --embedded.\n\n" + HELP);
            System.exit(2);
        }
        System.exit(new Simulation(fabric, name, keyEnv, ca, pin, embedded, port, runs, gap, speed, topic, detail, outbox, adminEnv, System.out).withPrices(prices).withoutPrices(noPrices).withScenarios(scenario).run());
    }

    /** The simulation itself, apart from the command line so a test can run it. */
    public static final class Simulation {
        private final String fabric;
        private final String name;
        private final String keyEnv;
        private final String ca;
        private final String pin;
        private final boolean embedded;
        private final int port;
        private final int runs;
        private final int gap;
        private final double speed;
        private final String topic;
        private final String detail;
        private final String outbox;
        private final String adminEnv;
        private final PrintStream out;
        private volatile boolean stop;
        private io.github.llm4j.budget.PriceTable priceTable;
        private String key;
        private String prices;
        private boolean noPrices;
        private List<Scenarios.Scenario> scenarios = List.of();

        /** Run these scenarios (comma separated names, checked now) in place of the studio workflow. */
        public Simulation withScenarios(String names) {
            this.scenarios = Scenarios.parse(names);
            return this;
        }

        public Simulation(String fabric, String name, String keyEnv, String ca, String pin, boolean embedded, int port, int runs, int gap, double speed,
                          String topic, String detail, String outbox, String adminEnv, PrintStream out) {
            this.fabric = fabric;
            this.name = name == null ? "sim" : name;
            this.keyEnv = keyEnv;
            this.ca = ca;
            this.pin = pin;
            this.embedded = embedded;
            this.port = port;
            this.runs = runs;
            this.gap = gap;
            this.speed = speed;
            this.topic = topic;
            this.detail = detail;
            this.outbox = outbox;
            this.adminEnv = adminEnv;
            this.out = out;
        }

        /** The client key itself, instead of the environment variable (for tests). */
        public Simulation withKey(String key) {
            this.key = key;
            return this;
        }

        /** A price table file instead of the packaged one. */
        public Simulation withPrices(String file) {
            this.prices = file;
            return this;
        }

        /** Report no cost at all, as a process without a price file does. */
        public Simulation withoutPrices(boolean none) {
            this.noPrices = none;
            return this;
        }

        public void stop() {
            stop = true;
        }

        /** @return the exit code: 0, or 2 when it could not start */
        public int run() throws Exception {
            String key = fabric == null ? null : this.key != null ? this.key : System.getenv(keyEnv);
            if (fabric != null && (key == null || key.isBlank())) {
                System.err.println("Put the client key in the environment variable " + keyEnv + " (the key printed by 'fabric clients add " + name + "').");
                return 2;
            }
            FabricOptions f = fabric == null ? null : FabricOptions.parse(fabric, name, ca, false, pin, outbox, null, "3s");
            ObserveOptions options = new ObserveOptions(embedded ? port : 0, null, detail, name, Duration.ZERO, embedded, f);
            Path dir = Files.createTempDirectory("loom-sim");
            Path script = copy("sim.loom", dir);
            copy("sim.inspect", dir);
            if (!noPrices) priceTable = io.github.llm4j.budget.PriceTable.load(prices != null ? Path.of(prices) : copy("sim.prices", dir));
            String text = Files.readString(script);
            InMemorySecretStore secrets = new InMemorySecretStore();
            if (key != null) secrets.put(f.keySecret(), key);
            if (adminEnv != null && System.getenv(adminEnv) != null) secrets.put("LOOM_INSPECT_ADMIN_PASSWORD", System.getenv(adminEnv));
            ObserveProvider.Session session = new InspectObserveProvider().open(options, out);
            out.println("simulating " + name + (fabric != null ? " -> " + fabric : "") + (embedded ? ", page at " + session.describe() : "") + "; about four minutes a run at --speed 1");
            try {
                for (int n = 1; !stop && (runs == 0 || n <= runs); n++) {
                    if (!scenarios.isEmpty()) {
                        for (Scenarios.Scenario sc : scenarios) {
                            if (!stop) Scenarios.runLive(session, sc, name + "-" + sc.name() + "-" + System.currentTimeMillis() % 100000 + "-" + n, dir, out, secrets);
                        }
                    } else {
                        oneRun(session, script, text, secrets, n);
                    }
                    if (!stop && (runs == 0 || n < runs)) pause(gap * 1000L);
                }
            } finally {
                if (!stop && session instanceof io.github.llm4j.loom.inspect.InspectSession live) live.awaitDelivered(Duration.ofSeconds(10)); // the last events reach the server
                session.close();
            }
            return 0;
        }

        private void oneRun(ObserveProvider.Session session, Path script, String text, InMemorySecretStore secrets, int n) {
            HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(text).tokenize()).parseScript(), tools(), factory(new Scripted(speed)));
            e.setSecretStore(secrets);
            if (priceTable != null) e.setPriceTable(priceTable); // so each step carries a cost, as in a real run with a price file
            e.setSleeper(d -> Thread.sleep((long) (d.toMillis() * speed))); // the workflow's sleeps shrink with the run
            String id = name + "-" + System.currentTimeMillis() % 100000 + "-" + n;
            e.setRunId(id);
            session.attach(e.observable(), script);
            e.initialize();
            out.println("run " + n + " (" + id + ") started");
            try {
                e.executeWorkflow("Studio", Map.of("topic", topic));
                out.println("run " + n + " finished");
            } catch (RunStopped s) {
                out.println("run " + n + " stopped: " + s.getMessage());
            } catch (RunSuspended s) {
                out.println("run " + n + " suspended: " + s.getMessage() + " (the simulator does not resume it)");
            } catch (RuntimeException ex) {
                out.println("run " + n + " failed: " + ex.getMessage());
            }
        }

        private void pause(long ms) {
            long end = System.currentTimeMillis() + ms;
            while (!stop && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static Path copy(String resource, Path dir) throws IOException {
            try (InputStream in = Simulate.class.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("missing resource " + resource);
                Path target = dir.resolve(resource);
                Files.copy(in, target);
                return target;
            }
        }
    }

    // ---------------------------------------------------------------------------------- the scripted world

    private static ToolRegistry tools() {
        ToolRegistry t = new ToolRegistry();
        t.register("Wiki", fake("Wiki", args -> pause(150, 500, 1.0) + "Search results: 3 pages found. Top: \"Cold water immersion\" - physiological responses, vasoconstriction, mood."));
        t.register("Example", fake("Example", args -> pause(100, 300, 1.0) + "Error: HTTP 404 Not Found for " + args)); // how a failing fetch reads
        t.register("Notes", fake("Notes", args -> pause(100, 300, 1.0) + "saved report.md (2 KB)"));
        return t;
    }

    private interface Body {
        String run(Map<String, Object> args);
    }

    private static Tool fake(String name, Body body) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "simulated " + name; }
            @Override public String execute(Map<String, Object> args) { return body.run(args); }
        };
    }

    private static String pause(int minMs, int maxMs, double speed) {
        try {
            Thread.sleep((long) (ThreadLocalRandom.current().nextInt(minMs, maxMs + 1) * speed));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "";
    }

    private static LLMClientFactory factory(Scripted world) {
        return model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                pause(400, 1600, world.speed);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                int p = 300 + r.nextInt(500);
                int c = 80 + r.nextInt(300);
                return LLMResponse.builder().content(world.next(model)).model(model).tokenUsage(p, c, p + c).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    /** What each scripted model says, in turn; the sequences are as long as one step needs, so they line up from run to run. */
    static final class Scripted {
        final double speed;
        private final Map<String, int[]> turn = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, List<String>> script = Map.ofEntries(
                Map.entry("sim-showrunner", List.of(done("A 45 second video on the science of cold water, opening on the shock of the first plunge."))),
                Map.entry("sim-researcher", List.of(call("Wiki", "{\"q\":\"cold water immersion\"}"), call("Wiki", "{\"q\":\"recovery after exercise\"}"),
                        done("Three findings: cold exposure raises alertness; evidence for recovery is mixed; contrast therapy is popular. Pages: Cold water immersion, Cryotherapy."))),
                Map.entry("sim-trendscout", List.of(call("Wiki", "{\"q\":\"video hook patterns\"}"), done("Angles: 1 the first plunge, 2 what the research says, 3 myths, 4 a week in the cold."))),
                Map.entry("sim-competitor", List.of(call("Example", "{\"path\":\"/pricing\"}"), call("Example", "{\"path\":\"/blog\"}"),
                        done("Both fetches failed with HTTP 404, so there is nothing to report about the rival."))),
                Map.entry("sim-scriptwriter", List.of(done("Cold plunges are everywhere. 83% of regular plungers report better sleep, according to a 2021 study of 1,204 adults. "
                        + "Recovery time drops by 37% and the market will reach $4.2 billion by 2027. Try it for 11 days and see.", false))),
                Map.entry("sim-editor", List.of(call("Wiki", "{\"q\":\"video hook patterns\"}"), done("{\"score\":5,\"fix\":\"Strengthen the hook.\"}", true))),
                Map.entry("sim-factchecker", List.of(call("Wiki", "{\"q\":\"cold plunge sleep study\"}"), call("Wiki", "{\"q\":\"cold plunge market size\"}"),
                        done("{\"unsupported\":4,\"claims\":[\"83% better sleep\",\"37% faster recovery\",\"$4.2 billion\",\"11 days\"]}", true))),
                Map.entry("sim-seo", List.of(call("Wiki", "{\"q\":\"cold plunge\"}"), done("{\"titles\":[\"The Cold Truth\",\"Plunge Science\",\"Ice and Recovery\"],\"fit\":7}", true))),
                Map.entry("sim-lead", List.of(done("{\"verdict\":\"INCOMPLETE\",\"fix\":\"Strengthen the hook.\"}", true))),
                Map.entry("sim-planner", List.of(done("{\"shots\":[{\"description\":\"the first plunge\",\"thumb_var\":\"thumb1\",\"caption_var\":\"cap1\"},"
                        + "{\"description\":\"the thermometer\",\"thumb_var\":\"thumb2\",\"caption_var\":\"cap2\"},{\"description\":\"the recovery\",\"thumb_var\":\"thumb3\",\"caption_var\":\"cap3\"}]}", true))),
                Map.entry("sim-designer", List.of(done("A close-up of a face breaking the surface of icy water, steam rising."))),
                Map.entry("sim-captioner", List.of(done("The shock lasts a minute. What it does for you lasts longer."))),
                // the first audit fails (so the rewind happens), the second passes
                Map.entry("sim-auditor", List.of(done("{\"score\":3,\"notes\":\"The thumbnails lack a face close-up.\"}", true), done("{\"score\":8,\"notes\":\"ok\"}", true))),
                Map.entry("sim-publisher", List.of(call("Notes", "{\"file\":\"report.md\"}"), done("Saved report.md.")))
        );

        Scripted(double speed) {
            this.speed = speed;
        }

        String next(String model) {
            List<String> seq = script.getOrDefault(model, List.of(done("ok")));
            int[] t = turn.computeIfAbsent(model, k -> new int[1]);
            synchronized (t) {
                return seq.get(t[0]++ % seq.size());
            }
        }

        private static String call(String tool, String args) {
            return "```json\n{\"thought\":\"I will use " + tool + "\",\"action\":\"" + tool + "\",\"action_input\":" + args + "}\n```";
        }

        private static String done(String answer) {
            return done(answer, false);
        }

        /** {@code structured}: the answer is a JSON object, passed as one; otherwise it is text. */
        private static String done(String answer, boolean structured) {
            String value = structured ? answer : "\"" + answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
            return "```json\n{\"thought\":\"done\",\"final_answer\":" + value + "}\n```";
        }
    }
}
