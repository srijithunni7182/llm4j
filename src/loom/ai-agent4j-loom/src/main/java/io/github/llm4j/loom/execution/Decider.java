package io.github.llm4j.loom.execution;

import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.autonomy.AgentIdentity;
import io.github.llm4j.loom.autonomy.Case;
import io.github.llm4j.loom.autonomy.Engine;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.LevelState;
import io.github.llm4j.loom.autonomy.MemoryLedger;
import io.github.llm4j.loom.autonomy.MemoryLevelStore;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a {@code decide} statement: the agent proposes, the level says who decides, a person is asked when the level says so, and the case is
 * written to the ledger. All of it is journaled under the decide step so a resumed run repeats nothing and a fork of the run sees the same inputs.
 *
 * <p>The proposal is kept from the person in {@code watch}: it is written to a journal key the question never reads, and every trace event and audit
 * entry made while the agent proposes is held back until the verdict is in.
 */
final class Decider {

    private static final Logger log = Logger.getLogger(Decider.class.getName());
    static final String DEFAULT_SCOPE = "all";
    /** Evidence beyond this many characters (after masking) is cut off and the case marked, so a replay can say it cannot be faithful. */
    static final int EVIDENCE_LIMIT = 64 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private final HarnessExecutor run;
    private final ThreadLocal<Proposing> proposing = new ThreadLocal<>();
    private final Map<String, Engine> engines = new ConcurrentHashMap<>();
    private final Map<String, String> identities = new ConcurrentHashMap<>();
    private MemoryLedger fallbackLedger;
    private MemoryLevelStore fallbackLevels;

    Decider(HarnessExecutor run) {
        this.run = run;
    }

    // ---- the proposal in progress ---------------------------------------------------------------------------------------

    /** What is being gathered while an agent proposes: its evidence, and what must not be seen yet. */
    static final class Proposing {
        private final RunJournal journal;
        private final String step;
        private final boolean hide;
        private java.util.function.UnaryOperator<String> scrub = t -> t;
        private final Map<String, String> recorded = new HashMap<>();
        private final List<Runnable> held = new ArrayList<>();
        private int count;
        private int size;
        private boolean truncated;
        private boolean effects;

        Proposing(RunJournal journal, String step, boolean hide) {
            this.journal = journal;
            this.step = step;
            this.hide = hide;
        }

        synchronized void record(String tool, String argsHash, String raw) {
            String result = scrub.apply(raw);
            if (size + result.length() > EVIDENCE_LIMIT) {
                truncated = true;
                return;
            }
            size += result.length();
            EvidenceTool.write(journal, step, count++, tool, argsHash, result);
        }

        synchronized void effectDuringProposal() {
            effects = true;
        }

        /** The answer a read got in the original case, when the same call was recorded (a replay). */
        @SuppressWarnings("unchecked")
        synchronized String recorded(String tool, String argsHash) {
            if (recorded.isEmpty()) {
                for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
                    if (!e.getKey().startsWith(step + "#decide-evidence:") || !(e.getValue().value() instanceof Map<?, ?> m)) continue;
                    recorded.putIfAbsent(m.get("tool") + "|" + m.get("args"), String.valueOf(m.get("result")));
                }
                recorded.putIfAbsent("", "");
            }
            return recorded.get(tool + "|" + argsHash);
        }

        synchronized boolean hasEvidence() {
            return count > 0;
        }

        synchronized void flush() {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            now.forEach(Runnable::run);
        }
    }

    Proposing proposing() {
        return proposing.get();
    }

    /** Defers a trace event or audit entry while a proposal is being kept from a person; false when nothing is being held back. */
    boolean hold(Runnable delivery) {
        Proposing p = proposing.get();
        if (p == null || !p.hide) return false;
        synchronized (p) {
            p.held.add(delivery);
        }
        return true;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private DecisionDef decisionOf(DecideStmt stmt) {
        return run.script().getDecisions().stream().filter(d -> d.getName().equals(stmt.getDecision())).findFirst()
                .orElseThrow(() -> new IllegalStateException("decision " + stmt.getDecision() + " is not defined"));
    }

    private synchronized io.github.llm4j.loom.autonomy.Ledger ledger() {
        if (run.ledger() != null) return run.ledger();
        if (fallbackLedger == null) fallbackLedger = new MemoryLedger();
        return fallbackLedger;
    }

    private synchronized io.github.llm4j.loom.autonomy.LevelStore levels() {
        if (run.levelStore() != null) return run.levelStore();
        if (fallbackLevels == null) fallbackLevels = new MemoryLevelStore();
        return fallbackLevels;
    }

    Engine engine(DecisionDef def) {
        return engines.computeIfAbsent(def.getName(), n -> new Engine(def, ledger(), levels(), run.clock()));
    }

    private String identityOf(DecisionDef def) {
        return identities.computeIfAbsent(def.getName(), n -> AgentIdentity.of(run.script(), def, run.baseDir(), run.promptCatalog()));
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String mask(DecisionDef def, String value) {
        String masked = run.forStorage(def.getAgent(), value);
        return new io.github.llm4j.tools.Redactor(secrets()).scrub(masked);
    }

    private java.util.List<String> secrets() {
        java.util.List<String> out = new ArrayList<>();
        for (io.github.llm4j.loom.ast.ToolDef t : run.script().getTools()) {
            t.getOptions().forEach((k, v) -> {
                if (v.isReference()) {
                    String value = run.credentialValue(v);
                    if (value != null && !value.isBlank()) out.add(value);
                }
            });
        }
        return out;
    }

    /** The values the decision remembers, as text, masked as the agent's guard says. */
    private Map<String, String> fields(DecisionDef def) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String name : def.getRemember()) {
            Object v = run.view().getVariable(name);
            fields.put(name, mask(def, text(v)));
        }
        return fields;
    }

    private String scopeOf(DecisionDef def) {
        if (def.getGroupBy() == null) return DEFAULT_SCOPE;
        String v = text(run.view().getVariable(def.getGroupBy())).strip();
        return v.isEmpty() ? DEFAULT_SCOPE : v;
    }

    private static int generationOf(String step) {
        int g = 1;
        Matcher m = Pattern.compile("~(\\d+)").matcher(step);
        while (m.find()) g = Math.max(g, Integer.parseInt(m.group(1)));
        return g;
    }

    /** Deterministic: a resume makes the same choice, and nothing the agent says can change it. */
    static boolean sampledForAudit(String caseId, String decision, double percent) {
        if (percent <= 0) return false;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((caseId + "|" + decision + "|audit").getBytes(StandardCharsets.UTF_8));
            long n = ((h[0] & 0xffL) << 24 | (h[1] & 0xffL) << 16 | (h[2] & 0xffL) << 8 | (h[3] & 0xffL)) % 10000;
            return n < percent * 100;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the statement --------------------------------------------------------------------------------------------------------

    void decide(DecideStmt stmt) {
        DecisionDef def = decisionOf(stmt);
        AgentDef agent = run.script().getAgents().stream().filter(a -> a.getName().equals(def.getAgent())).findFirst()
                .orElseThrow(() -> new IllegalStateException("agent " + def.getAgent() + " is not defined"));
        String step = run.currentStep();
        RunJournal journal = run.journal();

        if (run.replay() != null) {
            replayProposal(def, agent, stmt, step);
            return;
        }

        String free = run.identityStep();
        String caseId = text(run.getRunId()) + "/" + free;

        Map<String, String> fields = fields(def);
        String scope = scopeOf(def);
        Map<String, Object> pick = levelFor(def, caseId, step, scope);
        Level ranAt = Level.of(text(pick.get("level")));
        boolean blind = ranAt == Level.WATCH;

        openCase(def, caseId, step, free, scope, fields, pick);

        var done = journal.get(step + "#decide-result");
        if (done.isPresent() && done.get().value() instanceof Map<?, ?> m) {
            // a resumed run: the case is decided; make sure the ledger has all of it (a crash may have come between the journal and the ledger)
            Map<String, Object> result = castMap(m);
            journal.get(step + "#decide-proposal").filter(e -> e.value() instanceof Map<?, ?>).ifPresent(e -> appendProposed(def, caseId, step, castMap((Map<?, ?>) e.value())));
            bind(stmt, result);
            appendDecided(def, caseId, step, result);
            return;
        }

        // 1. the proposal
        Map<String, Object> proposal = journal.get(step + "#decide-proposal").filter(e -> e.value() instanceof Map<?, ?>).map(e -> castMap((Map<?, ?>) e.value())).orElse(null);
        Proposing p = null;
        if (proposal == null) {
            p = new Proposing(journal, step, blind);
            p.scrub = text -> new io.github.llm4j.tools.Redactor(secrets()).scrub(text);
            proposal = propose(def, agent, step, fields, p, ranAt);
            if (ranAt == Level.ACT && Boolean.TRUE.equals(proposal.get("malformed"))) {
                pick = new LinkedHashMap<>(pick);
                pick.put("level", Level.SUGGEST.word());
                pick.put("how", "unusable proposal");
                journal.put(step + "#level", new RunJournal.Entry("level", pick));
                ranAt = Level.SUGGEST;
                blind = false;
            }
            journal.put(step + "#decide-proposal", new RunJournal.Entry("proposal", proposal));
        }
        appendProposed(def, caseId, step, proposal);
        announceProposal(def, caseId, ranAt, blind, proposal);
        run.stopPoint(step + "#decide-proposal");

        // 2. who decides
        String verdict;
        String decider;
        boolean shown;
        long millis = 0;
        if (ranAt == Level.ACT) {
            verdict = text(proposal.get("choice"));
            decider = "agent";
            shown = false;
        } else {
            shown = ranAt == Level.SUGGEST;
            long askedAt = journal.get(step + "#decide-asked-at").map(e -> ((Number) e.value()).longValue()).orElseGet(() -> {
                long now = run.clock().millis();
                journal.put(step + "#decide-asked-at", new RunJournal.Entry("time", now));
                return now;
            });
            verdict = ask(def, step, fields, shown ? proposal : null);
            millis = Math.max(0, run.clock().millis() - askedAt);
            decider = def.getAsk();
        }
        if (p != null) p.flush();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verdict", verdict);
        result.put("proposal", proposal.get("choice"));
        result.put("level", ranAt.word());
        result.put("decider", decider);
        result.put("shown", shown);
        result.put("millis", millis);
        journal.put(step + "#decide-result", new RunJournal.Entry("decision", result));
        bind(stmt, result);
        appendDecided(def, caseId, step, result);
        afterCase(def, scope, caseId, verdict, ranAt, decider);
    }

    private void bind(DecideStmt stmt, Map<String, Object> result) {
        run.setVariable(stmt.getVariable(), text(result.get("verdict")));
        run.setVariable(stmt.getVariable() + "_proposal", text(result.get("proposal")));
        run.setVariable(stmt.getVariable() + "_level", text(result.get("level")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    // ---- the level ------------------------------------------------------------------------------------------------------------

    /** The level this case runs at, read once and journaled, so a run resumed after a level change finishes the case at the level it began with. */
    private Map<String, Object> levelFor(DecisionDef def, String caseId, String step, String scope) {
        RunJournal journal = run.journal();
        var known = journal.get(step + "#level");
        if (known.isPresent() && known.get().value() instanceof Map<?, ?> m) return castMap(m);
        Map<String, Object> pick = new LinkedHashMap<>();
        pick.put("scope", scope);
        try {
            Engine engine = engine(def);
            String identity = identityOf(def);
            LevelState state = engine.state(scope, identity);
            if (!identity.equals(state.identity())) state = newEpoch(def, engine, scope, state, identity);
            Level level = engine.effective(state);
            String how = level.word();
            if (level == Level.ACT || level == Level.SUGGEST) {
                // a share of cases is asked blind at suggest as well as at act: otherwise nothing could be measured once the agent is trusted with more
                if (sampledForAudit(caseId, def.getName(), def.getAuditPercent())) {
                    level = Level.WATCH;
                    how = "audit";
                } else if (level == Level.ACT && limited(def)) {
                    level = Level.SUGGEST;
                    how = "limit";
                }
            }
            pick.put("level", level.word());
            pick.put("how", how);
            pick.put("earned", state.level().word());
            pick.put("epoch", state.epoch());
            pick.put("identity", state.identity());
            if (engine.unearned(scope, state)) pick.put("forced", true);
        } catch (RuntimeException e) {
            // fail closed: a ledger or level store that cannot be read never means a higher level
            log.warning("Decision " + def.getName() + ": the ledger or level store failed (" + e.getMessage() + "); running at watch");
            run.audit("autonomy_degraded", Map.of("decision", def.getName(), "case", caseId, "reason", text(e.getMessage())));
            pick.put("level", Level.WATCH.word());
            pick.put("how", "degraded");
            pick.put("epoch", 1);
            pick.put("identity", identityOf(def));
        }
        journal.put(step + "#level", new RunJournal.Entry("level", pick));
        return pick;
    }

    private boolean limited(DecisionDef def) {
        for (String condition : def.getAskWhen()) {
            if (ConditionEvaluator.evaluate(condition, run.view())) return true;
        }
        if (def.getAskAfterPerDay() > 0) {
            Instant startOfDay = LocalDate.ofInstant(run.clock().instant(), ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            long today = ledger().cases(def.getName(), c -> !c.superseded() && !c.at().isBefore(startOfDay)).size();
            return today >= def.getAskAfterPerDay();
        }
        return false;
    }

    private LevelState newEpoch(DecisionDef def, Engine engine, String scope, LevelState old, String identity) {
        String detail = "";
        Level level;
        switch (def.getOnChange()) {
            case START_OVER -> level = def.getStartAt();
            case KEEP_TRUST -> level = old.level();
            default -> {
                HarnessExecutor.Inherited inherited = run.inheritedLevel(def, scope, old, identity);
                level = Level.min(inherited.level(), old.level());
                detail = "; " + inherited.reason();
            }
        }
        String why = "the agent behind " + def.getName() + " changed (" + AgentIdentity.shorten(old.identity()) + " to " + AgentIdentity.shorten(identity) + "): " + def.getOnChange().phrase() + detail;
        Engine.Change change = engine.newEpoch(scope, old, identity, level, why);
        if (change != null) announce(def, change);
        return engine.state(scope, identity).identity().equals(identity) ? engine.state(scope, identity) : old;
    }

    // ---- the proposal ---------------------------------------------------------------------------------------------------------

    private String taskText(DecisionDef def, Map<String, String> fields) {
        String template = def.getTask();
        StringBuilder values = new StringBuilder();
        fields.forEach((k, v) -> values.append(k).append(" = ").append(v).append('\n'));
        if (template == null) {
            return "Decide " + def.getName() + " for this case. Choose one of: " + String.join(", ", def.getChoices()) + ".\n\n" + values;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = fields.containsKey(name) ? fields.get(name) : mask(def, text(run.view().getVariable(name)));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out + "\n\nChoose one of: " + String.join(", ", def.getChoices()) + ".";
    }

    private Map<String, Object> propose(DecisionDef def, AgentDef agent, String step, Map<String, String> fields, Proposing p, Level ranAt) {
        RunJournal journal = run.journal();
        String task = taskText(def, fields);
        journal.put(step + "#decide-task", new RunJournal.Entry("task", task));
        Map<String, Object> proposal = null;
        String reminder = "";
        for (int attempt = 0; attempt < 2 && proposal == null; attempt++) {
            String where = step + (attempt == 0 ? "#propose" : "#propose-retry");
            Object raw;
            proposing.set(p);
            try {
                run.setVariable("_decide_task", task + reminder);
                DelegateStmt del = new DelegateStmt("{_decide_task}", agent.getName(), "_decide_proposal");
                run.delegateAt(where, del);
                raw = journal.get(where).map(e -> e.value()).orElse(null);
            } catch (Replay.Unreplayable | io.github.llm4j.loom.runtime.RunStopped | io.github.llm4j.agent.AgentInterrupt e) {
                throw e;
            } catch (RuntimeException failed) {
                // a proposal that could not be made is sent to a person as an unusable one; the failure itself is not hidden
                log.warning("Decision " + def.getName() + ": the agent could not propose (" + failed.getMessage() + ")");
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("choice", "escalate");
                bad.put("reasoning", "");
                bad.put("malformed", true);
                bad.put("failed", true);
                return withFlags(bad, p);
            } finally {
                proposing.remove();
                run.removeVariable("_decide_task");
                run.removeVariable("_decide_proposal");
            }
            proposal = parse(def, raw);
            if (proposal == null && attempt == 0) {
                reminder = "\n\nYour last answer did not name one of the choices. The choice must be exactly one of: " + String.join(", ", def.getChoices()) + ".";
            }
        }
        if (proposal == null) {
            proposal = new LinkedHashMap<>();
            proposal.put("choice", "escalate");
            proposal.put("reasoning", "");
            proposal.put("malformed", true);
        }
        return withFlags(proposal, p);
    }

    private Map<String, Object> withFlags(Map<String, Object> proposal, Proposing p) {
        List<String> flags = new ArrayList<>();
        if (p.truncated) flags.add("evidence_truncated");
        if (p.effects) flags.add("effects_during_proposal");
        if (!flags.isEmpty()) proposal.put("flags", flags);
        return proposal;
    }

    /** The proposal as a choice (one of the declared ones, exactly), a reasoning and a confidence; null when the answer does not name a choice. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(DecisionDef def, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object choice = ((Map<String, Object>) m).get("choice");
        if (choice == null) return null;
        String c = String.valueOf(choice).strip();
        String matched = def.getChoices().stream().filter(x -> x.equalsIgnoreCase(c)).findFirst().orElse(null);
        if (matched == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("choice", matched);
        out.put("reasoning", mask(def, text(((Map<String, Object>) m).get("reasoning"))));
        Object confidence = ((Map<String, Object>) m).get("confidence");
        if (confidence instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 1) out.put("confidence", n.doubleValue());
        return out;
    }

    // ---- the person -----------------------------------------------------------------------------------------------------------

    private String question(DecisionDef def, Map<String, String> fields, Map<String, Object> proposal) {
        StringBuilder q = new StringBuilder();
        q.append(def.getAsk()).append(", please decide ").append(def.getName()).append(" (").append(String.join(" / ", def.getChoices())).append(")\n");
        fields.forEach((k, v) -> q.append(neutralise(k)).append(" = ").append(neutralise(v)).append('\n'));
        if (proposal != null) {
            q.append("\nThe agent proposes: ").append(proposal.get("choice"));
            if (proposal.get("confidence") != null) q.append(" (confidence ").append(proposal.get("confidence")).append(')');
            q.append("\nIts reasoning: ").append(neutralise(text(proposal.get("reasoning")))).append('\n');
        }
        return q.toString();
    }

    private static String neutralise(String text) {
        return io.github.llm4j.loom.travel.RunTravel.neutralise(text);
    }

    private String ask(DecisionDef def, String step, Map<String, String> fields, Map<String, Object> shownProposal) {
        if (run.humanInterface() == null) {
            throw new IllegalStateException("decision " + def.getName() + " needs a person to ask (" + def.getAsk() + "), but no human interface is configured");
        }
        String question = question(def, fields, shownProposal);
        for (int round = 0; round < 2; round++) {
            String key = step + (round == 0 ? "#decide-ask" : "#decide-ask2");
            String q = round == 0 ? question : question + "\nPlease answer with exactly one of: " + String.join(", ", def.getChoices()) + ".";
            String answer = run.atStep(key, () -> {
                String recorded = run.recordedAnswer(q);
                if (recorded != null) return recorded;
                String got = run.humanInterface().promptHuman(key, q, new io.github.llm4j.loom.runtime.HumanInterface.Hints(io.github.llm4j.loom.runtime.HumanInterface.Hints.Kind.DECIDE, def.getChoices(), def.getAsk()));
                run.recordAnswer(q, got);
                return got;
            });
            String choice = match(def, answer);
            if (choice != null) return choice;
        }
        throw new IllegalStateException("decision " + def.getName() + ": the answer was not one of " + def.getChoices());
    }

    /** An answer matched to a choice: exactly (any case), or as the one choice it is the start of. */
    static String match(DecisionDef def, String answer) {
        if (answer == null) return null;
        String a = answer.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return null;
        for (String c : def.getChoices()) if (c.equalsIgnoreCase(a)) return c;
        List<String> starts = def.getChoices().stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(a)).toList();
        return starts.size() == 1 ? starts.get(0) : null;
    }

    // ---- the ledger -----------------------------------------------------------------------------------------------------------

    private void openCase(DecisionDef def, String caseId, String step, String free, String scope, Map<String, String> fields, Map<String, Object> pick) {
        Rec rec = new Rec(caseId + "#case#" + generationOf(step), def.getName(), Rec.CASE, run.clock().instant(), caseId, generationOf(step),
                Rec.map("scope", scope, "locator", run.runLocator(), "step", free, "journalStep", step, "fields", fields, "level", pick.get("level"), "how", pick.get("how"),
                        "identity", pick.get("identity"), "epoch", pick.get("epoch"), "forced", pick.get("forced")));
        write(def, rec);
    }

    private void appendProposed(DecisionDef def, String caseId, String step, Map<String, Object> proposal) {
        write(def, new Rec(caseId + "#proposed#" + generationOf(step), def.getName(), Rec.PROPOSED, run.clock().instant(), caseId, generationOf(step),
                Rec.map("choice", proposal.get("choice"), "reasoning", proposal.get("reasoning"), "confidence", proposal.get("confidence"),
                        "malformed", Boolean.TRUE.equals(proposal.get("malformed")) ? true : null, "flags", proposal.get("flags"))));
    }

    private void appendDecided(DecisionDef def, String caseId, String step, Map<String, Object> result) {
        write(def, new Rec(caseId + "#decided#" + generationOf(step), def.getName(), Rec.DECIDED, run.clock().instant(), caseId, generationOf(step),
                Rec.map("verdict", result.get("verdict"), "decider", result.get("decider"), "shown", Boolean.TRUE.equals(result.get("shown")), "millis", result.get("millis"))));
    }

    /** A ledger that cannot be written never stops a decision: the verdict is in the run journal and is appended again on resume. */
    private void write(DecisionDef def, Rec rec) {
        try {
            ledger().append(rec);
        } catch (RuntimeException e) {
            log.warning("Decision " + def.getName() + ": the ledger could not be written (" + e.getMessage() + "); the run journal holds the case");
            run.audit("autonomy_degraded", Map.of("decision", def.getName(), "case", rec.caseId(), "reason", "ledger write failed: " + text(e.getMessage())));
        }
    }

    private void afterCase(DecisionDef def, String scope, String caseId, String verdict, Level ranAt, String decider) {
        try {
            for (Engine.Change change : engine(def).afterCase(scope)) announce(def, change);
        } catch (RuntimeException e) {
            log.warning("Decision " + def.getName() + ": could not apply the ladder (" + e.getMessage() + ")");
        }
    }

    // ---- telling people -------------------------------------------------------------------------------------------------------

    private void announceProposal(DecisionDef def, String caseId, Level ranAt, boolean blind, Map<String, Object> proposal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", def.getName());
        data.put("case", caseId);
        data.put("level", ranAt.word());
        if (blind) {
            run.trace(TraceEvent.DECISION, def.getAgent(), "proposal recorded (hidden)", data);
        } else {
            data.put("choice", proposal.get("choice"));
            run.trace(TraceEvent.DECISION, def.getAgent(), "proposes " + proposal.get("choice"), data);
        }
        if (!blind) run.audit("decision_proposed", data);
    }

    private void announce(DecisionDef def, Engine.Change change) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", def.getName());
        data.put("scope", change.scope());
        data.put("kind", change.kind());
        data.put("from", change.from().word());
        data.put("to", change.to().word());
        data.put("reason", change.reason());
        if (change.forced()) data.put("forced", true);
        run.audit("proposed".equals(change.kind()) ? "promotion_proposed" : "level_changed", data);
        run.trace(TraceEvent.DECISION, def.getAgent(), change.sentence(), data);
        if (def.getTellTool() != null) tell(def, change);
    }

    private void tell(DecisionDef def, Engine.Change change) {
        try {
            io.github.llm4j.agent.Tool tool = run.toolNamed(def.getTellTool());
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("title", "Trust in " + def.getName() + " changed");
            args.put("text", change.sentence());
            run.atStep(run.currentStep() + "#tell:" + change.scope() + ":" + change.kind() + ":" + change.to().word(), () -> {
                try {
                    return tool.execute(args);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (RuntimeException e) {
            log.warning("Decision " + def.getName() + ": could not tell " + def.getTellTool() + " (" + e.getMessage() + ")");
            run.audit("autonomy_notification_failed", Map.of("decision", def.getName(), "tool", def.getTellTool(), "reason", text(e.getMessage())));
        }
    }

    // ---- replay (a past case under a candidate) ---------------------------------------------------------------------------------

    private void replayProposal(DecisionDef def, AgentDef agent, DecideStmt stmt, String step) {
        RunJournal journal = run.journal();
        // a decide that came before the one being replayed (an earlier round of a loop, say) already has its verdict in the journal: it is not replayed
        var recorded = journal.get(step + "#decide-result");
        if (recorded.isPresent() && recorded.get().value() instanceof Map<?, ?> m) {
            bind(stmt, castMap(m));
            return;
        }
        Proposing p = new Proposing(journal, run.replay().evidenceStep(), false);
        Map<String, String> fields = fields(def);
        Map<String, Object> proposal = propose(def, agent, step, fields, p, Level.WATCH);
        if (proposal.get("failed") != null) throw new Replay.Unreplayable("proposal_failed", "the candidate could not propose");
        journal.put(step + "#decide-proposal", new RunJournal.Entry("proposal", proposal));
        run.stopPoint(step + "#decide-proposal");
        // no stop point named: the replay was not asked to end here, so give the statement its answer and let the run carry on
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verdict", proposal.get("choice"));
        result.put("proposal", proposal.get("choice"));
        result.put("level", Level.WATCH.word());
        bind(stmt, result);
    }
}
