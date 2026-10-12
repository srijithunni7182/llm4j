package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.inspect.http.InspectServer;
import io.github.llm4j.loom.inspect.map.AutonomyView;
import io.github.llm4j.loom.inspect.map.Detail;
import io.github.llm4j.loom.inspect.map.GraphView;
import io.github.llm4j.loom.inspect.map.TraceMapper;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.emit.Outbound;
import io.github.llm4j.loom.inspect.rules.RulesCheck;
import io.github.llm4j.loom.inspect.rules.RulesFile;
import io.github.llm4j.loom.inspect.sink.EventSink;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.observe.ObservableRun;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One process's observing: a run registry, the page that shows it, and a sink per attached run. Embedded mode only; the same
 * registry code serves Fabric.
 */
public final class InspectSession implements ObserveProvider.Session {

    private static final int QUEUE = 10_000;

    private final ObserveOptions options;
    private final RunRegistry registry = new RunRegistry();
    private final InspectServer server;
    private boolean hinted;
    private Outbound fabric;
    private boolean fabricFailed;
    private final java.io.PrintStream console;
    private final EmbeddedControls controls = new EmbeddedControls(registry);
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final String instance = "i_" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicInteger attached = new AtomicInteger();

    InspectSession(ObserveOptions options, java.io.PrintStream console) {
        this.options = options;
        this.console = console == null ? System.err : console;
        if (options.embedded()) {
            this.server = new InspectServer(registry, InspectServer.config().bind(options.bind(), options.port())
                    .sessions(new io.github.llm4j.loom.inspect.http.AdminSessions(controls::password, java.time.Clock.systemUTC(), false))
                    .controls(controls, new io.github.llm4j.loom.inspect.control.FileAuditSink(java.nio.file.Path.of(System.getProperty("loom.inspect.audit", "loom-inspect-audit.log")), java.time.Clock.systemUTC())));
            this.server.start();
        } else {
            this.server = null;
        }
    }

    /** The link to Fabric, opened on the first run: the client key is in that run's secret store. Null when not reporting, or when it could not start. */
    private synchronized Outbound fabric(ObservableRun run) {
        if ((options.fabric() == null && options.otlp() == null) || fabricFailed) return null;
        if (fabric == null) {
            try {
                String key = null;
                if (options.fabric() != null) {
                    try {
                        key = run.secretStore() == null ? null : run.secretStore().resolve(options.fabric().keySecret());
                    } catch (RuntimeException missing) {
                        // not in the store: said below, with what to do
                    }
                    if (key == null || key.isBlank()) {
                        throw new IllegalStateException("the client key is not in the secret store: set it with 'weave secrets set " + options.fabric().keySecret() + "'");
                    }
                }
                io.github.llm4j.loom.observe.ObservableRun r = run;
                fabric = Outbound.open(options.name(), new Outbound.Destinations(options.fabric(), key, options.otlp(),
                        name -> {
                            try {
                                return r.secretStore() == null ? null : r.secretStore().resolve(name);
                            } catch (RuntimeException e) {
                                return null;
                            }
                        }), run.script(), java.time.Clock.systemUTC(), console);
            } catch (RuntimeException e) {
                fabricFailed = true;
                console.println("loom-inspect: not reporting: " + e.getMessage() + ". The workflow is not affected.");
            }
        }
        return fabric;
    }

    @Override
    public void attach(ObservableRun run) {
        attach(run, null);
    }

    /**
     * The watching rules for a run: the file given with {@code --observe-rules}, else {@code <script>.inspect} beside the script, else
     * none (the defaults). A file with errors is reported once and not used; the run is never affected.
     */
    private java.util.Map<String, InspectRules> rulesFor(ObservableRun run, java.nio.file.Path scriptFile) {
        java.nio.file.Path file = options.rules();
        if (file == null && scriptFile != null) {
            java.nio.file.Path beside = RulesFile.sidecarFor(scriptFile);
            if (java.nio.file.Files.isRegularFile(beside)) file = beside;
        }
        if (file == null) return java.util.Map.of();
        java.nio.file.Path source = file;
        try {
            RulesFile.Parsed parsed = RulesFile.read(source);
            java.util.List<RulesFile.Problem> problems = run.script() == null ? parsed.problems() : RulesCheck.check(run.script(), parsed);
            problems.forEach(p -> console.println("loom-inspect: " + source.getFileName() + ": " + p));
            if (problems.stream().anyMatch(RulesFile.Problem::error)) {
                console.println("loom-inspect: not using " + source.getFileName() + " (watching with the defaults). The workflow is not affected.");
                return java.util.Map.of();
            }
            return parsed.rules();
        } catch (RuntimeException e) {
            console.println("loom-inspect: cannot read " + file + ": " + e.getMessage() + ". Watching with the defaults.");
            return java.util.Map.of();
        }
    }

    @Override
    public void attach(ObservableRun run, java.nio.file.Path scriptFile) {
        Detail detail = Detail.parse(options.detail());
        Outbound link = fabric(run);
        TraceMapper mapper = new TraceMapper(run.runId(), detail);
        // a new instance id for each attach: a run resumed in this process starts its sequence again, and must not look like a repeat
        Resource resource = Resource.of(options.name(), instance + "-" + attached.incrementAndGet());
        AtomicReference<String> graphed = new AtomicReference<>();
        java.util.function.Supplier<java.util.Map<String, Object>> graph = new java.util.function.Supplier<>() {
            private java.util.Map<String, Object> built;

            @Override
            public java.util.Map<String, Object> get() {
                if (built == null) built = run.script() == null ? java.util.Map.of() : GraphView.of(run.script());
                return built;
            }
        };
        java.util.Map<String, InspectRules> rules = rulesFor(run, scriptFile);
        io.github.llm4j.loom.inspect.map.ProgressSampler sampler = new io.github.llm4j.loom.inspect.map.ProgressSampler(rules, run::variables);
        EventSink[] self = new EventSink[1];
        EventSink sink = new EventSink(options.name() + "-" + attached.get(), QUEUE, event -> {
            TraceMapper.Output out = mapper.map(event);
            boolean ending = TraceEvent.RUN_END.equals(event.type());
            // the run id is set after attach, so the graph is given to a run when its first record shows which run this is
            String trace = out.logs().isEmpty() ? null : out.logs().get(0).traceId();
            if (trace != null && !trace.equals(graphed.get())) {
                graphed.set(trace);
                if (server != null) {
                    controls.register(trace, run);
                    if (!controls.enabled() && !hinted) {
                        hinted = true;
                        console.println("loom-inspect: pause, suspend and stop are off: " + controls.disabledReason());
                    }
                    registry.setGraph(options.name(), trace, graph.get());
                    registry.setRules(options.name(), trace, rules);
                }
                java.util.function.Supplier<List<java.util.Map<String, Object>>> ladders = null;
                if (run.script() != null && !run.script().getDecisions().isEmpty()) {
                    ladders = () -> AutonomyView.of(run.script(), run.levelStore().orElse(null));
                    if (server != null) registry.setLadders(options.name(), trace, ladders);
                }
                if (link != null) {
                    link.snapshot(new Snapshot(options.name(), resource.instanceId(), trace, run.runId(), firstWorkflow(run), graph.get(), List.of(), rules), ladders);
                }
            }
            if (link != null) link.offer(out, resource); // to disk first; sending is another thread's business
            if (server != null) { // a process that only reports keeps no copy of its own: a long-lived daemon must not grow
                registry.applyLogs(resource, out.logs());
                registry.applySpans(resource, out.spans());
            }
            if (ending) {
                // this attach is over (finished, or paused to resume under a new attach): free its thread, a daemon fires many runs
                self[0].finishWhenDrained();
                sinks.remove(self[0]);
            }
        });
        self[0] = sink;
        sinks.add(sink);
        // a no_progress variable is read here, on the run's own thread, so it is the value at that moment and not whatever the queue gets to later
        run.addTraceListener(event -> {
            for (TraceEvent s : sampler.samples(event)) sink.onEvent(s);
            sink.onEvent(event);
        });
    }

    private static String firstWorkflow(ObservableRun run) {
        return run.script() == null || run.script().getWorkflows().isEmpty() ? null : run.script().getWorkflows().get(0).getName();
    }

    @Override
    public String describe() {
        if (server == null) return options.fabric() != null ? options.fabric().url().toString() : options.otlp().endpoint().toString();
        String host = options.bind().contains(":") ? "[" + options.bind() + "]" : options.bind();
        return "http://" + host + ":" + server.port();
    }

    /** How many attached runs are still being watched (a run that has ended is released). */
    int liveSinks() {
        return sinks.size();
    }

    /** The runs seen so far. */
    public RunRegistry registry() {
        return registry;
    }

    @Override
    public String summary() {
        String reporting = null;
        if (options.fabric() != null) reporting = "Reporting to " + options.fabric().url() + " as " + options.name() + " (a local outbox keeps events if it is unreachable)";
        if (options.otlp() != null) reporting = (reporting == null ? "" : reporting + "\n🔭 ") + "Exporting OTLP to " + options.otlp().endpoint() + " as " + options.name();
        if (server == null) return reporting;
        return "Watching this run at " + describe() + (reporting == null ? "" : "\n🔭 " + reporting);
    }

    /** The port the page is on (useful when 0 was asked for). */
    public int port() {
        return server.port();
    }

    /** Waits until every event so far has reached the registry. For tests and for the end of a run. */
    public boolean awaitIdle(Duration timeout) {
        boolean all = true;
        for (EventSink s : sinks) all &= s.awaitIdle(timeout);
        return all;
    }

    @Override
    public void close() {
        for (EventSink s : sinks) s.close();
        if (fabric != null) fabric.close(); // after the sinks, so their last events are in the outbox
        if (server != null) server.stop();
    }
}
