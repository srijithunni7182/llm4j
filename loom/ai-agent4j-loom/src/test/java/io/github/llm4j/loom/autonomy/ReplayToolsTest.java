package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.agent.tool.Effectful;
import io.github.llm4j.agent.tool.Outcome;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the proposing agent's tools do when a case is replayed: reads from the record, everything else stubbed (spec loom-earned-autonomy R7.3). */
class ReplayToolsTest {

    @TempDir
    Path dir;

    static final class Lookup implements Effectful {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() { return "Lookup"; }
        @Override public String getDescription() { return "looks a customer up"; }
        @Override public String execute(Map<String, Object> args) { calls.incrementAndGet(); return "customer since 2019"; }
        @Override public boolean isEffect(Map<String, Object> args) { return false; }
        @Override public EffectPolicy policy() { return EffectPolicy.DEFAULT; }
        @Override public String target(Map<String, Object> args) { return "crm"; }
        @Override public Outcome perform(Map<String, Object> args, String key) { throw new UnsupportedOperationException(); }
    }

    /** A tool kind the runtime has never heard of, that changes something. */
    static final class Mystery implements Effectful {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() { return "Mystery"; }
        @Override public String getDescription() { return "does something"; }
        @Override public String execute(Map<String, Object> args) { calls.incrementAndGet(); return "did it"; }
        @Override public boolean isEffect(Map<String, Object> args) { return true; }
        @Override public EffectPolicy policy() { return EffectPolicy.DEFAULT; }
        @Override public String target(Map<String, Object> args) { return "world"; }
        @Override public Outcome perform(Map<String, Object> args, String key) { calls.incrementAndGet(); return Outcome.ok("did it"); }
    }

    static final class Plain implements Tool {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() { return "Plain"; }
        @Override public String getDescription() { return "plain"; }
        @Override public String execute(Map<String, Object> args) { calls.incrementAndGet(); return "plain did it"; }
    }

    /** Cases decided by an agent that read Lookup(c-1) while proposing; a harness whose candidate has its own tool instances. */
    private ReplayHarness harness(String tools, int cases) throws Exception {
        DecideHarness live = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.withTools(tools));
        live.tools.put("Lookup", new Lookup());
        live.tools.put("Mystery", new Mystery());
        live.tools.put("Plain", new Plain());
        live.toolCalls.add(new String[] {"Lookup", "{\"id\": \"c-1\"}"});
        ReplayHarness h = new ReplayHarness(live, dir);
        h.seedCases(cases);
        return h;
    }

    @Test
    @Tag("EA-V7.5")
    void aReadTheCaseRecordedIsAnsweredFromTheRecordAndNeverRunAgain() throws Exception {
        ReplayHarness h = harness("Lookup", 6);
        Lookup lookup = new Lookup();
        h.candidateTools.put("Lookup", lookup);
        h.candidateToolCalls.add(new String[] {"Lookup", "{\"id\": \"c-1\"}"});

        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        assertThat(report.replayed).isEqualTo(6);
        assertThat(lookup.calls.get()).as("answered from #decide-evidence").isZero();
        assertThat(report.skipped).isEmpty();
    }

    @Test
    @Tag("EA-V7.5")
    void aReadWithOtherArgumentsMakesTheCaseUnreplayableOrRunsLiveAndFlaggedWhenAsked() throws Exception {
        ReplayHarness h = harness("Lookup", 4);
        Lookup lookup = new Lookup();
        h.candidateTools.put("Lookup", lookup);
        h.candidateToolCalls.add(new String[] {"Lookup", "{\"id\": \"a-different-customer\"}"});

        ReplayReport strict = h.engine.run("Refund", h.options(h.incumbent()), null);
        assertThat(strict.replayed).isZero();
        assertThat(strict.skipped).containsEntry("unrecorded_read", 4);
        assertThat(strict.skips.get(0).detail()).contains("Lookup");
        assertThat(lookup.calls.get()).isZero();

        ReplayReport live = h.engine.run("Refund", new ReplayOptions(h.incumbent(), null, null, 500, 1, 1, true, false, false, 0, null, null), null);
        assertThat(live.replayed).isEqualTo(4);
        assertThat(lookup.calls.get()).as("ran live").isEqualTo(4);
        assertThat(live.toolsLive).hasSize(4);
        assertThat(live.markdown()).contains("read live (not recorded; non-deterministic)");
    }

    @Test
    @Tag("EA-V7.5")
    @Tag("EA-V7.6")
    void anEffectAndAnUnknownKindAreSimulatedNeverRunAndTheProposalsAfterThemAreCountedSeparately() throws Exception {
        ReplayHarness h = harness("Lookup, Mystery, Plain", 5);
        Mystery mystery = new Mystery();
        Plain plain = new Plain();
        h.candidateTools.put("Lookup", new Lookup());
        h.candidateTools.put("Mystery", mystery);
        h.candidateTools.put("Plain", plain);
        h.candidateToolCalls.add(new String[] {"Lookup", "{\"id\": \"c-1\"}"});
        h.candidateToolCalls.add(new String[] {"Mystery", "{}"});
        h.candidateToolCalls.add(new String[] {"Plain", "{}"});

        ReplayReport report = h.engine.run("Refund", h.options(h.incumbent()), null);

        assertThat(report.replayed).isEqualTo(5);
        assertThat(mystery.calls.get()).isZero();
        assertThat(plain.calls.get()).isZero();
        assertThat(report.afterSimulationCount).isEqualTo(5);
        assertThat(report.toolsSimulated).contains("Mystery", "Plain");
        assertThat(report.markdown()).contains("simulated (not performed): Mystery, Plain").contains("5 proposals were made after a simulated call");
    }

    @Test
    @Tag("EA-V7.5")
    void aToolDeclaredReplayAllowRunsAndIsListedAtTheTopOfTheReport() throws Exception {
        List<String> posts = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            posts.add(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String live = Scripts2.withTools("Lookup").replace("workflow Triage", "tool Notify { use: webhook url: env.NOTIFY_URL allow_http: true allow_private: true }\nworkflow Triage");
            DecideHarness harness = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), live);
            harness.tools.put("Lookup", new Lookup());
            harness.env.put("NOTIFY_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            harness.toolCalls.add(new String[] {"Lookup", "{\"id\": \"c-1\"}"});
            ReplayHarness h = new ReplayHarness(harness, dir);
            h.seedCases(3);
            h.candidateTools.put("Lookup", new Lookup());
            h.candidateToolCalls.add(new String[] {"Lookup", "{\"id\": \"c-1\"}"});
            h.candidateToolCalls.add(new String[] {"Notify", "{\"text\": \"hello\"}"});
            String candidate = live.replace("tools: [Lookup]", "tools: [Lookup, Notify]");
            Path simulated = h.script("sim.loom", candidate);
            Path allowed = h.script("allow.loom", candidate.replace("allow_private: true }", "allow_private: true replay: allow }"));
            // the candidate reads NOTIFY_URL from the process environment of the test: a host sets it for the replay
            h.envForCandidates.put("NOTIFY_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

            ReplayReport off = h.engine.run("Refund", h.options(simulated), null);
            assertThat(off.replayed).isEqualTo(3);
            assertThat(posts).as("simulated by default").isEmpty();

            ReplayReport on = h.engine.run("Refund", h.options(allowed), null);
            assertThat(on.replayed).isEqualTo(3);
            assertThat(posts).as("replay: allow runs it").hasSize(3);
            assertThat(on.toolsAllowed).containsExactly("Notify");
            assertThat(on.markdown()).contains("allowed to run (replay: allow): Notify");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Tag("EA-V7.5")
    void aPureBuiltInRunsAndTheClockToolAnswersWhenTheCaseWasDecided() throws Exception {
        ReplayHarness h = harness("Lookup", 3);
        h.candidateTools.put("Lookup", new Lookup());
        Path candidate = h.script("calc.loom", h.live.script.replace("tools: [Lookup]", "tools: [Lookup, calculator, current_time]"));
        h.candidateToolCalls.add(new String[] {"calculator", "{\"expression\": \"2 + 3\"}"});
        h.candidateToolCalls.add(new String[] {"current_time", "{}"});

        ReplayReport report = h.engine.run("Refund", h.options(candidate), null);

        assertThat(report.replayed).isEqualTo(3);
        assertThat(h.candidateObservations).anyMatch(o -> o.contains("5"));
        assertThat(h.candidateObservations).anyMatch(o -> o.contains("2026-03-01"));
    }
}
