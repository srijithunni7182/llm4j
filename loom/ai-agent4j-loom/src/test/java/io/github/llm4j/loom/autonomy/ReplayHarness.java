package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Cases decided for real by {@link DecideHarness}, and a replay engine over them: the runs are found in memory, the candidate's model is scripted
 * separately from the incumbent's, and what the candidate did (model calls, tools run) is counted so a test can say what a replay cost and touched.
 */
final class ReplayHarness {

    final DecideHarness live;
    final Path dir;
    final Path store;
    final Map<String, Path> scriptOf = new LinkedHashMap<>();
    final List<String> candidateTasks = Collections.synchronizedList(new ArrayList<>());
    final List<String> candidateQuestions = Collections.synchronizedList(new ArrayList<>());
    int candidateCalls;
    /** The system prompts the candidate's agent was given. */
    final List<String> candidateSystemPrompts = Collections.synchronizedList(new ArrayList<>());
    /** The candidate's executors carry a price table, so cost is measured. */
    boolean priced;
    /** The observations the candidate's agent saw (tool results) while proposing. */
    final List<String> candidateObservations = Collections.synchronizedList(new ArrayList<>());
    /** Environment variables the candidate's tools read. */
    final Map<String, String> envForCandidates = new LinkedHashMap<>();
    /** What the candidate's agent proposes for a case. */
    Function<Map<String, String>, String[]> candidateAgent = f -> new String[] {"approve", "same as before", "0.9"};
    /** Tool calls the candidate's agent makes before proposing. */
    List<String[]> candidateToolCalls = new ArrayList<>();
    final Map<String, io.github.llm4j.agent.Tool> candidateTools = new LinkedHashMap<>();
    /** The candidate's agent answers its Summarizer upstream step, were it asked to run it again. */
    boolean summarizerCalled;
    /** Runs that are "gone": their locator opens to nothing. */
    final java.util.Set<String> gone = new java.util.HashSet<>();
    ReplayEngine engine;
    final MutableClock clock = new MutableClock(DecideHarness.T0.plusSeconds(86400L * 100));

    ReplayHarness(DecideHarness live, Path dir) throws IOException {
        this.live = live;
        this.dir = dir;
        this.store = Files.createDirectories(dir.resolve("store"));
        this.engine = newEngine();
    }

    ReplayEngine newEngine() throws IOException {
        return new ReplayEngine(live.ledger, store.resolve("replays"), source(), candidates(), clock);
    }

    /** Writes a script file for the candidate, next to the incumbent's. */
    Path script(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    /** Every case ran under this script. */
    Path incumbent() throws IOException {
        if (incumbentFile == null || !Files.readString(incumbentFile).equals(live.script)) incumbentFile = script("incumbent.loom", live.script);
        return incumbentFile;
    }

    private Path incumbentFile;
    private Path incumbentCached;

    private CaseSource source() {
        return locator -> {
            if (gone.contains(locator)) return Optional.empty();
            RunJournal journal = live.journals.get(locator);
            if (journal == null) return Optional.empty();
            if (incumbentCached == null) {
                try {
                    incumbentCached = incumbent();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return Optional.of(new CaseSource.OpenedRun(journal, "Triage", live.startedWith.get(locator), incumbentCached));
        };
    }

    private Candidates candidates() {
        return (loaded, script, baseDir, run, journal) -> {
            ScriptedRun scripted = new ScriptedRun(baseDir);
            scripted.journal = journal;
            candidateTools.forEach(scripted.tools::register);
            scripted.env.putAll(envForCandidates);
            scripted.responder = r -> reply(r);
            scripted.human = q -> {
                candidateQuestions.add(q);
                return "approve";
            };
            HarnessExecutor executor = scripted.executor(loaded);
            executor.setRunId("replay");
            if (priced) {
                Path prices = Files.writeString(dir.resolve("prices.txt"), "m = 10 / 10\n");
                executor.setPriceTable(io.github.llm4j.budget.PriceTable.load(prices));
            }
            return executor;
        };
    }

    private String reply(LLMRequest r) {
        if (r.getMessages().get(0).getContent().contains("You are Summarizer")) {
            summarizerCalled = true;
            return ScriptedRun.done("summary");
        }
        candidateSystemPrompts.add(r.getMessages().get(0).getContent());
        String message = ScriptedRun.lastMessage(r);
        int marker = message.lastIndexOf("Current Task:");
        String task = marker < 0 ? message : message.substring(marker + "Current Task:".length()).trim();
        candidateCalls++;
        candidateTasks.add(task);
        if (message.contains("Observation:")) candidateObservations.add(message.substring(message.lastIndexOf("Observation:")));
        String[] says = candidateAgent.apply(DecideHarness.fieldsOf(task));
        if (!candidateToolCalls.isEmpty()) {
            int seen = message.split("Observation:", -1).length - 1;
            if (seen < candidateToolCalls.size()) return ScriptedRun.call(candidateToolCalls.get(seen)[0], candidateToolCalls.get(seen)[1]);
            return ScriptedRun.done("{\"choice\": \"" + says[0] + "\", \"reasoning\": \"" + says[1] + "\"}");
        }
        return "```json\n{\"choice\": \"" + says[0] + "\", \"reasoning\": \"" + says[1] + "\", \"confidence\": " + (says.length > 2 ? says[2] : "0.5") + "}\n```";
    }

    /** Decides {@code n} cases at watch: the person approves under 100 and rejects from 100 up; the incumbent agent proposes the same, except it approves everything under 300. */
    void seedCases(int n) {
        live.agent = f -> {
            int amount = Integer.parseInt(f.get("amount"));
            return new String[] {amount < 300 ? "approve" : "reject", "incumbent reasoning", "0.9"};
        };
        live.person = f -> Integer.parseInt(f.get("amount")) < 100 ? "approve" : "reject";
        for (int i = 0; i < n; i++) {
            live.runCase("run-" + i, live.inputs(i % 2 == 0 ? "gold" : "basic", 20 + i * 20));
            live.clock.advance(java.time.Duration.ofHours(1));
        }
    }

    ReplayOptions options(Path candidate) {
        return new ReplayOptions(candidate, null, null, 500, 1, 1, false, false, false, 0, null, null);
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
