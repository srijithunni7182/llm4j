package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObservableRun;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The read-only view an observer gets, and the way weave opens an observing session. */
class ObservableRunTest {

    private static final String SOURCE = """
            agent Researcher { model: "test/model" system: "You are Researcher." }
            workflow Main() { delegate "research" to Researcher -> research }
            """;

    private final TestClock clock = new TestClock();

    @Test
    void theViewShowsTheRunsIdJournalScriptAndClock() {
        RunJournal journal = RunJournal.inMemory();
        ObservableRun[] view = new ObservableRun[1];
        LimitScript run = new LimitScript(SOURCE, journal, clock, null, e -> {
            e.setRunId("r-x");
            view[0] = e.observable();
        });
        assertThat(view[0].runId()).isEqualTo("r-x");
        assertThat(view[0].journal()).isSameAs(journal);
        assertThat(view[0].clock()).isSameAs(clock);
        assertThat(view[0].script().getWorkflows()).extracting(w -> w.getName()).containsExactly("Main");
        assertThat(view[0].secretStore()).isNull();
        assertThat(view[0].ledger()).isEmpty();
        assertThat(view[0].levelStore()).isEmpty();
        run.run();
        assertThat(view[0].spend()).isNotNull();
    }

    @Test
    void variablesAreASnapshotThatCannotBeChanged() {
        ObservableRun[] view = new ObservableRun[1];
        LimitScript run = new LimitScript(SOURCE, RunJournal.inMemory(), clock, null, e -> view[0] = e.observable()).run();
        var snapshot = view[0].variables();
        assertThat(snapshot).containsEntry("research", "Researcher#1");
        assertThatThrownBy(() -> snapshot.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        run.executor.getContext().setVariable("later", "z");
        assertThat(snapshot).as("a snapshot does not follow the run").doesNotContainKey("later");
        assertThat(view[0].variables()).containsEntry("later", "z");
    }

    @Test
    void listenersAddedThroughTheViewReceiveTheRunsEvents() {
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        new LimitScript(SOURCE, RunJournal.inMemory(), clock, null, e -> e.observable().addTraceListener(events::add)).run();
        assertThat(events).extracting(TraceEvent::type).contains(TraceEvent.RUN_START, TraceEvent.DELEGATE_START, TraceEvent.DELEGATE_END, TraceEvent.RUN_END);
    }

    // ------------------------------------------------------------------ options and the provider hook

    @Test
    void optionsHaveDefaultsAndAreChecked() {
        ObserveOptions d = ObserveOptions.parse(null, null, null, null, null);
        assertThat(d.port()).isEqualTo(8787);
        assertThat(d.bind()).isEqualTo("127.0.0.1");
        assertThat(d.detail()).isEqualTo("truncated");
        assertThat(d.name()).isEqualTo("local");
        assertThat(d.linger()).isEqualTo(Duration.ZERO);

        ObserveOptions o = ObserveOptions.parse("9090", "::1", "FULL", "billing", "5m");
        assertThat(o.port()).isEqualTo(9090);
        assertThat(o.detail()).isEqualTo("full");
        assertThat(o.linger()).isEqualTo(Duration.ofMinutes(5));
        assertThat(ObserveOptions.parse("0", null, null, null, "30s").port()).isZero();

        assertThatThrownBy(() -> ObserveOptions.parse("eighty", null, null, null, null)).hasMessageContaining("port number");
        assertThatThrownBy(() -> ObserveOptions.parse("70000", null, null, null, null)).hasMessageContaining("0 to 65535");
        assertThatThrownBy(() -> ObserveOptions.parse(null, null, "lots", null, null)).hasMessageContaining("full, truncated or metadata");
        assertThatThrownBy(() -> ObserveOptions.parse(null, null, null, null, "soon")).hasMessageContaining("30s, 5m or 1h");
    }

    @Test
    void withoutAnAddOnOnTheClassPathOpeningASessionSaysWhatToDo() {
        assertThatThrownBy(() -> Observe.open(ObserveOptions.parse(null, null, null, null, null), new PrintStream(new ByteArrayOutputStream()), List.of()))
                .isInstanceOf(Observe.Unavailable.class)
                .hasMessageContaining("loom-inspect").hasMessageContaining("class path").hasMessageContaining("Nothing was run");
    }

    @Test
    void aProviderIsGivenTheOptionsAndAttachesRunsBeforeTheyStart() throws Exception {
        List<String> seen = new ArrayList<>();
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        ObserveProvider provider = new ObserveProvider() {
            @Override public String name() { return "fake"; }
            @Override public Session open(ObserveOptions options, PrintStream out) {
                seen.add("open " + options.port() + " " + options.name());
                return new Session() {
                    @Override public void attach(ObservableRun run) { seen.add("attach"); run.addTraceListener(events::add); }
                    @Override public String describe() { return "http://fake"; }
                    @Override public void close() { seen.add("close"); }
                };
            }
        };
        ObserveProvider.Session session = Observe.open(ObserveOptions.parse("1234", null, null, "me", null), new PrintStream(new ByteArrayOutputStream()), List.of(provider));
        new LimitScript(SOURCE, RunJournal.inMemory(), clock, null, e -> session.attach(e.observable())).run();
        session.close();
        assertThat(seen).containsExactly("open 1234 me", "attach", "close");
        assertThat(session.describe()).isEqualTo("http://fake");
        assertThat(events).as("attached before initialize, so the run was seen from its first event").extracting(TraceEvent::type).first().isEqualTo(TraceEvent.RUN_START);
    }
}
