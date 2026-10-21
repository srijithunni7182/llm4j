package io.github.llm4j.spring;

import io.github.llm4j.loom.observe.ObservableRun;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for loom-inspect: records what the starter asks of an observe provider. Registered
 * through ServiceLoader in the test resources.
 */
public final class RecordingObserveProvider implements ObserveProvider {

    static final List<ObserveOptions> OPENED = new CopyOnWriteArrayList<>();
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    static void reset() {
        OPENED.clear();
        EVENTS.clear();
    }

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public Session open(ObserveOptions options, PrintStream out) {
        OPENED.add(options);
        EVENTS.add("open");
        return new Session() {
            @Override
            public void attach(ObservableRun run) {
                EVENTS.add("attach");
            }

            @Override
            public void attach(ObservableRun run, Path scriptFile) {
                EVENTS.add("attach " + scriptFile.getFileName());
            }

            @Override
            public String describe() {
                return "http://127.0.0.1:" + options.port();
            }

            @Override
            public void close() {
                EVENTS.add("close");
            }
        };
    }
}
