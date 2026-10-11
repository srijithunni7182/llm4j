package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.inspect.http.InspectServer;
import io.github.llm4j.loom.inspect.map.AutonomyView;
import io.github.llm4j.loom.inspect.map.Detail;
import io.github.llm4j.loom.inspect.map.GraphView;
import io.github.llm4j.loom.inspect.map.TraceMapper;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.sink.EventSink;
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
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final String instance = "i_" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicInteger attached = new AtomicInteger();

    InspectSession(ObserveOptions options) {
        this.options = options;
        this.server = new InspectServer(registry, options.bind(), options.port());
        this.server.start();
    }

    @Override
    public void attach(ObservableRun run) {
        Detail detail = Detail.parse(options.detail());
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
                if (run.script() != null && !run.script().getDecisions().isEmpty()) {
                    registry.setLadders(options.name(), trace, () -> AutonomyView.of(run.script(), run.levelStore().orElse(null)));
                }
            }
            registry.applyLogs(resource, out.logs());
            registry.applySpans(resource, out.spans());
        });
        sinks.add(sink);
        run.addTraceListener(sink);
    }

    @Override
    public String describe() {
        String host = options.bind().contains(":") ? "[" + options.bind() + "]" : options.bind();
        return "http://" + host + ":" + server.port();
    }

    /** The runs seen so far. */
    public RunRegistry registry() {
        return registry;
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
        server.stop();
    }
}
