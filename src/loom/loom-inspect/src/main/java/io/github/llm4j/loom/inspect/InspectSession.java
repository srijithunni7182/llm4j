package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.inspect.http.InspectServer;
import io.github.llm4j.loom.inspect.map.AutonomyView;
import io.github.llm4j.loom.inspect.map.Detail;
import io.github.llm4j.loom.inspect.map.GraphView;
import io.github.llm4j.loom.inspect.map.TraceMapper;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.emit.FabricLink;
import io.github.llm4j.loom.inspect.sink.EventSink;
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
    private FabricLink fabric;
    private boolean fabricFailed;
    private final java.io.PrintStream console;
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final String instance = "i_" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicInteger attached = new AtomicInteger();

    InspectSession(ObserveOptions options, java.io.PrintStream console) {
        this.options = options;
        this.console = console == null ? System.err : console;
        if (options.embedded()) {
            this.server = new InspectServer(registry, options.bind(), options.port());
            this.server.start();
        } else {
            this.server = null;
        }
    }

    /** The link to Fabric, opened on the first run: the client key is in that run's secret store. Null when not reporting, or when it could not start. */
    private synchronized FabricLink fabric(ObservableRun run) {
        if (options.fabric() == null || fabricFailed) return null;
        if (fabric == null) {
            try {
                String key = run.secretStore() == null ? null : run.secretStore().resolve(options.fabric().keySecret());
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("the client key is not in the secret store: set it with 'weave secrets set " + options.fabric().keySecret() + "'");
                }
                fabric = FabricLink.open(options.name(), options.fabric(), key, run.script(), java.time.Clock.systemUTC(), console);
            } catch (RuntimeException e) {
                fabricFailed = true;
                console.println("loom-inspect: not reporting to Fabric: " + e.getMessage() + ". The workflow is not affected.");
            }
        }
        return fabric;
    }

    @Override
    public void attach(ObservableRun run) {
        Detail detail = Detail.parse(options.detail());
        FabricLink link = fabric(run);
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
        EventSink sink = new EventSink(options.name() + "-" + attached.get(), QUEUE, event -> {
            TraceMapper.Output out = mapper.map(event);
            // the run id is set after attach, so the graph is given to a run when its first record shows which run this is
            String trace = out.logs().isEmpty() ? null : out.logs().get(0).traceId();
            if (trace != null && !trace.equals(graphed.get())) {
                graphed.set(trace);
                registry.setGraph(options.name(), trace, graph.get());
                java.util.function.Supplier<List<java.util.Map<String, Object>>> ladders = null;
                if (run.script() != null && !run.script().getDecisions().isEmpty()) {
                    ladders = () -> AutonomyView.of(run.script(), run.levelStore().orElse(null));
                    registry.setLadders(options.name(), trace, ladders);
                }
                if (link != null) {
                    link.snapshot(new Snapshot(options.name(), resource.instanceId(), trace, run.runId(), firstWorkflow(run), graph.get(), List.of()), ladders);
                }
            }
            if (link != null) link.offer(out, resource); // to disk first; sending is another thread's business
            registry.applyLogs(resource, out.logs());
            registry.applySpans(resource, out.spans());
        });
        sinks.add(sink);
        run.addTraceListener(sink);
    }

    private static String firstWorkflow(ObservableRun run) {
        return run.script() == null || run.script().getWorkflows().isEmpty() ? null : run.script().getWorkflows().get(0).getName();
    }

    @Override
    public String describe() {
        if (server == null) return options.fabric().url().toString();
        String host = options.bind().contains(":") ? "[" + options.bind() + "]" : options.bind();
        return "http://" + host + ":" + server.port();
    }

    /** The runs seen so far. */
    public RunRegistry registry() {
        return registry;
    }

    @Override
    public String summary() {
        String reporting = options.fabric() == null ? null : "Reporting to " + options.fabric().url() + " as " + options.name() + " (a local outbox keeps events if it is unreachable)";
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
