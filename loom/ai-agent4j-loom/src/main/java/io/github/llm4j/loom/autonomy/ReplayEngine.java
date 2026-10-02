package io.github.llm4j.loom.autonomy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.cli.ScriptDrift;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.Replay;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.travel.OverlayJournal;
import io.github.llm4j.loom.travel.RunTravel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Replays past cases under a candidate: for each, an ephemeral fork of the case's run (an overlay over its journal, read only) taken at the decide
 * statement, run in simulate mode under the candidate script, stopped right after the proposal is journaled and before anyone is asked. What the
 * candidate proposed is read from the overlay; the overlay is then discarded. The ledger, the level store and the run's own journal are never written.
 *
 * <p>A replay writes only its own directory: {@code <replays>/<id>/plan.json}, {@code log.jsonl} (one line per case, appended as it finishes, so
 * a replay cut short can be resumed) and the report.
 */
public final class ReplayEngine {

    private static final Logger log = Logger.getLogger(ReplayEngine.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Ledger ledger;
    private final Path replays;
    private final CaseSource source;
    private final Candidates candidates;
    private final Clock clock;

    /** @param replays the directory replays are kept in, {@code <store>/autonomy/<decision>/replays} */
    public ReplayEngine(Ledger ledger, Path replays, CaseSource source, Candidates candidates, Clock clock) {
        this.ledger = ledger;
        this.replays = replays;
        this.source = source;
        this.candidates = candidates;
        this.clock = clock;
    }

    // ---- selecting -----------------------------------------------------------------------------------------------------

    /**
     * The cases a replay covers: current, decided blind (the cases that count as evidence), in the scope and period asked, sampled with a seeded
     * shuffle so a large ledger is sampled the same way for the same seed.
     */
    public static List<Case> select(List<Case> all, ReplayOptions o, Integer epoch) {
        List<Case> pool = new ArrayList<>();
        for (Case c : all) {
            if (!c.blindEvidence()) continue;
            if (o.scope() != null && !o.scope().equals(c.scope())) continue;
            if (epoch != null && c.epoch() != epoch) continue;
            if (o.since() != null && c.decidedAt() != null && c.decidedAt().isBefore(o.since())) continue;
            pool.add(c);
        }
        pool.sort((a, b) -> a.id().compareTo(b.id()));
        Collections.shuffle(pool, new Random(o.seed()));
        return pool.size() > o.limit() ? new ArrayList<>(pool.subList(0, o.limit())) : pool;
    }

    // ---- running -------------------------------------------------------------------------------------------------------

    /** A new replay of {@code decision}. */
    public ReplayReport run(String decision, ReplayOptions options, Integer epoch) throws Exception {
        Names.check(decision);
        LoomScript script = new LoomLoader().load(options.candidate().toString());
        DecisionDef def = script.getDecisions().stream().filter(d -> d.getName().equals(decision)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the candidate script " + options.candidate() + " has no decision named " + decision));
        List<Case> chosen = select(ledger.cases(decision), options, epoch);
        String base = "r" + Long.toHexString(clock.millis()) + "-" + Integer.toHexString((decision + options.candidate() + options.seed()).hashCode() & 0xffff);
        String id = base;
        for (int n = 2; Files.exists(replays.resolve(id)); n++) id = base + "-" + n;
        Path dir = replays.resolve(id);
        Files.createDirectories(dir);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", id);
        plan.put("decision", decision);
        plan.put("candidate", options.candidate().toString());
        plan.put("candidateIdentity", AgentIdentity.of(script, def, options.candidate().toAbsolutePath().getParent()));
        plan.put("scope", options.scope());
        plan.put("since", options.since() == null ? null : options.since().toString());
        plan.put("limit", options.limit());
        plan.put("seed", options.seed());
        plan.put("repeat", options.repeat());
        plan.put("liveReads", options.liveReads());
        plan.put("allowDrift", options.allowDrift());
        plan.put("noMemory", options.noMemory());
        plan.put("policy", options.policy() == null ? null : options.policy().toString());
        plan.put("epoch", epoch);
        plan.put("selected", chosen.stream().map(Case::id).toList());
        plan.put("startedAt", clock.instant().toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("plan.json").toFile(), plan);
        Files.writeString(dir.resolve("log.jsonl"), "");
        return execute(decision, id, dir, options, epoch, chosen.size(), options);
    }

    /** Carries on a replay that was cut short (a budget, a rate limit, a crash): the cases already in its log are not run again. */
    @SuppressWarnings("unchecked")
    public ReplayReport resume(String decision, String id, BigDecimal maxCost, long maxTokens) throws Exception {
        Path dir = replays.resolve(Names.check(id));
        if (!Files.exists(dir.resolve("plan.json"))) throw new IllegalArgumentException("there is no replay " + id + " for decision " + decision);
        Map<String, Object> plan = JSON.readValue(dir.resolve("plan.json").toFile(), new TypeReference<Map<String, Object>>() { });
        ReplayOptions saved = new ReplayOptions(Path.of((String) plan.get("candidate")), (String) plan.get("scope"), plan.get("since") == null ? null : Instant.parse((String) plan.get("since")),
                ((Number) plan.get("limit")).intValue(), ((Number) plan.get("seed")).longValue(), ((Number) plan.get("repeat")).intValue(), Boolean.TRUE.equals(plan.get("liveReads")),
                Boolean.TRUE.equals(plan.get("allowDrift")), Boolean.TRUE.equals(plan.get("noMemory")), maxTokens, maxCost, plan.get("policy") == null ? null : Path.of((String) plan.get("policy")));
        Integer epoch = plan.get("epoch") instanceof Number n ? n.intValue() : null;
        return execute(decision, id, dir, saved, epoch, ((List<String>) plan.get("selected")).size(), saved);
    }

    @SuppressWarnings("unchecked")
    private ReplayReport execute(String decision, String id, Path dir, ReplayOptions o, Integer epoch, int selectedCount, ReplayOptions budgetFrom) throws Exception {
        Map<String, Object> plan = JSON.readValue(dir.resolve("plan.json").toFile(), new TypeReference<Map<String, Object>>() { });
        List<String> selected = (List<String>) plan.get("selected");
        LoomScript script = new LoomLoader().load(o.candidate().toString());
        DecisionDef def = script.getDecisions().stream().filter(d -> d.getName().equals(decision)).findFirst().orElseThrow();
        Path scriptDir = o.candidate().toAbsolutePath().getParent();
        Path baseDir = scriptDir;
        Map<String, String> policyHashes = new LinkedHashMap<>();
        if (o.policy() != null) {
            PolicyOverlay.Prepared prepared = PolicyOverlay.prepare(script, def, scriptDir, o.policy(), dir.resolve("policy"));
            baseDir = prepared.baseDir();
            policyHashes = prepared.hashes();
        }
        Set<String> allowed = new HashSet<>();
        for (ToolDef t : script.getTools()) if (t.getOptions().containsKey("replay") && "allow".equals(t.getOptions().get("replay").value())) allowed.add(t.getName());

        Map<String, Case> byId = new LinkedHashMap<>();
        for (Case c : ledger.cases(decision)) if (!c.superseded()) byId.putIfAbsent(c.id(), c);

        List<ReplayReport.Row> rows = new ArrayList<>();
        List<ReplayReport.Skip> skips = new ArrayList<>();
        Set<String> done = new HashSet<>();
        long tokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (String line : Files.readAllLines(dir.resolve("log.jsonl"))) {
            if (line.isBlank()) continue;
            Map<String, Object> m = JSON.readValue(line, new TypeReference<Map<String, Object>>() { });
            if ("stopped".equals(m.get("kind"))) continue;
            done.add((String) m.get("case"));
            if ("row".equals(m.get("kind"))) {
                ReplayReport.Row row = row(m);
                rows.add(row);
                tokens += row.tokens();
                cost = cost.add(row.cost());
            } else {
                skips.add(new ReplayReport.Skip((String) m.get("case"), (String) m.get("scope"), (String) m.get("reason"), (String) m.get("detail")));
            }
        }

        boolean partial = false;
        String why = null;
        long budgetTokens = budgetFrom.maxTokens();
        BigDecimal budgetCost = budgetFrom.maxCost();
        long startTokens = tokens;
        BigDecimal startCost = cost;
        for (String caseId : selected) {
            if (done.contains(caseId)) continue;
            Case c = byId.get(caseId);
            if (c == null) continue;
            if (budgetTokens > 0 && tokens - startTokens >= budgetTokens) {
                partial = true;
                why = "stopped after " + (tokens - startTokens) + " tokens (--max-tokens " + budgetTokens + ")";
                break;
            }
            if (budgetCost != null && cost.subtract(startCost).compareTo(budgetCost) >= 0) {
                partial = true;
                why = "stopped after spending " + cost.subtract(startCost).toPlainString() + " (--max-cost " + budgetCost.toPlainString() + ")";
                break;
            }
            Object result = one(c, def, o, o.candidate(), baseDir, allowed);
            if (result instanceof ReplayReport.Row row) {
                rows.add(row);
                tokens += row.tokens();
                cost = cost.add(row.cost());
                append(dir, rowMap(row));
            } else {
                ReplayReport.Skip skip = (ReplayReport.Skip) result;
                skips.add(skip);
                append(dir, skipMap(skip));
            }
        }
        if (partial) append(dir, Map.of("kind", "stopped", "why", why));

        ReplayReport report = ReplayReport.build(def, id, rows, skips, selected.size());
        report.candidateIdentity = (String) plan.get("candidateIdentity");
        report.partial = partial;
        report.stoppedBecause = why;
        report.repeat = o.repeat();
        report.policyHashes = policyHashes;
        report.toolsAllowed = new ArrayList<>(allowed);
        for (ReplayReport.Row r : rows) if (r.nonDeterministic()) report.toolsLive.add(r.caseId());
        report.toolsSimulated = simulatedFrom(dir);
        Files.writeString(dir.resolve("report.md"), report.markdown());
        Files.writeString(dir.resolve("report.json"), report.json());
        return report;
    }

    // ---- one case --------------------------------------------------------------------------------------------------------

    private Object one(Case c, DecisionDef def, ReplayOptions o, Path candidate, Path baseDir, Set<String> allowed) {
        String scope = c.scope();
        for (String flag : c.flags()) {
            if (List.of("evidence_truncated", "effects_during_proposal", "fields_masked").contains(flag)) return new ReplayReport.Skip(c.id(), scope, flag, "the case is marked " + flag);
        }
        var opened = source.open(c.locator());
        if (opened.isEmpty()) return new ReplayReport.Skip(c.id(), scope, "journal_missing", "the run " + c.locator() + " is gone");
        CaseSource.OpenedRun run = opened.get();
        if (!o.allowDrift()) {
            String drift = ScriptDrift.between(run.script(), candidate, run.journal(), run.workflow(), c.step());
            if (drift != null) return new ReplayReport.Skip(c.id(), scope, "prefix_drift", drift);
        }
        List<String> choices = new ArrayList<>();
        String reasoning = null;
        long tokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean after = false, live = false;
        Set<String> simulated = new java.util.TreeSet<>();
        for (int rep = 0; rep < o.repeat(); rep++) {
            OverlayJournal overlay = new OverlayJournal(run.journal());
            Replay replay = new Replay(o.liveReads(), o.noMemory(), allowed, c.decidedAt() != null ? c.decidedAt() : c.at(), c.journalStep() != null ? c.journalStep() : c.step());
            String stopAt;
            try {
                RunTravel.rewind(overlay, run.workflow(), c.step(), Map.of(), "keep", false, "replay of " + c.id(), "replay");
                Generations.Located at = new Generations(overlay).locate(c.step());
                if (at == null) return new ReplayReport.Skip(c.id(), scope, "decide_step_missing", c.step() + " is not a step a replay can start from");
                stopAt = at.liveId() + "#decide-proposal";
            } catch (IllegalArgumentException e) {
                return new ReplayReport.Skip(c.id(), scope, "decide_step_missing", e.getMessage());
            }
            HarnessExecutor executor = null;
            try {
                executor = candidates.create(candidate, baseDir, run, overlay);
                executor.setJournal(overlay);
                executor.setSimulate(true);
                executor.setReplay(replay);
                executor.setStopAt(stopAt);
                executor.setAutonomy(null, null);
                executor.setBudgetOverrides(1L << 50, null, null); // spend is measured, never limited here: the replay's own limits are checked between cases
                executor.setHumanInterface(msg -> {
                    throw new IllegalStateException("a replay never asks a person");
                });
                executor.initialize();
                try {
                    executor.executeWorkflow(run.workflow(), run.inputs());
                    return new ReplayReport.Skip(c.id(), scope, "decide_not_reached", "the candidate never reached the decide step");
                } catch (RunStopped stopped) {
                    // the proposal is journaled and nothing has been asked of anybody: this is where a replay ends
                }
            } catch (Exception e) {
                return new ReplayReport.Skip(c.id(), scope, "replay_failed", String.valueOf(e.getMessage()));
            } finally {
                if (executor != null) executor.shutdown();
            }
            if (!replay.unrecordedReads().isEmpty()) {
                return new ReplayReport.Skip(c.id(), scope, "unrecorded_read", "the candidate read " + String.join(", ", replay.unrecordedReads()) + " in a way the case had not recorded");
            }
            Object proposal = overlay.get(stopAt).map(RunJournal.Entry::value).orElse(null);
            if (!(proposal instanceof Map<?, ?> p) || p.get("choice") == null) return new ReplayReport.Skip(c.id(), scope, "no_proposal", "the candidate produced no proposal");
            choices.add(String.valueOf(p.get("choice")));
            if (reasoning == null) reasoning = p.get("reasoning") == null ? "" : String.valueOf(p.get("reasoning"));
            for (Map.Entry<String, RunJournal.Entry> e : overlay.written().entrySet()) {
                if (e.getKey().contains("#usage:") && e.getValue().value() instanceof Map<?, ?> u) {
                    tokens += num(u.get("prompt")) + num(u.get("completion"));
                    cost = cost.add(u.get("cost") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(u.get("cost"))));
                }
            }
            after |= replay.proposedAfterSimulation();
            live |= !replay.liveReadsUsed().isEmpty();
            simulated.addAll(replay.simulatedCalls());
        }
        appendSimulated(simulated);
        return new ReplayReport.Row(c.id(), scope, c.verdict(), c.proposal(), c.malformed(), choices, reasoning, c.fields(), after, live, tokens, cost);
    }

    private final Set<String> simulatedTools = new java.util.concurrent.ConcurrentSkipListSet<>();

    private void appendSimulated(Set<String> tools) {
        simulatedTools.addAll(tools);
    }

    private List<String> simulatedFrom(Path dir) {
        return new ArrayList<>(simulatedTools);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    // ---- the log -----------------------------------------------------------------------------------------------------------

    private static void append(Path dir, Map<String, Object> line) {
        try {
            Files.writeString(dir.resolve("log.jsonl"), JSON.writeValueAsString(line) + "\n", StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> rowMap(ReplayReport.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "row");
        m.put("case", r.caseId());
        m.put("scope", r.scope());
        m.put("human", r.humanVerdict());
        m.put("incumbent", r.incumbent());
        m.put("incumbentMalformed", r.incumbentMalformed());
        m.put("choices", r.choices());
        m.put("reasoning", r.reasoning());
        m.put("fields", r.fields());
        m.put("afterSimulation", r.afterSimulation());
        m.put("nonDeterministic", r.nonDeterministic());
        m.put("tokens", r.tokens());
        m.put("cost", r.cost().toPlainString());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static ReplayReport.Row row(Map<String, Object> m) {
        return new ReplayReport.Row((String) m.get("case"), (String) m.get("scope"), (String) m.get("human"), (String) m.get("incumbent"), Boolean.TRUE.equals(m.get("incumbentMalformed")),
                (List<String>) m.get("choices"), (String) m.get("reasoning"), (Map<String, String>) m.get("fields"), Boolean.TRUE.equals(m.get("afterSimulation")),
                Boolean.TRUE.equals(m.get("nonDeterministic")), ((Number) m.get("tokens")).longValue(), new BigDecimal((String) m.get("cost")));
    }

    private static Map<String, Object> skipMap(ReplayReport.Skip s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "skip");
        m.put("case", s.caseId());
        m.put("scope", s.scope());
        m.put("reason", s.reason());
        m.put("detail", s.detail());
        return m;
    }
}
