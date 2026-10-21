package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The run's money: the run, agent and step budgets, metering of model clients against them, what
 * the run has spent (journaled so a resumed run knows what was already paid), and what happens when
 * a budget runs out (refill, ask for more, stop). {@link HarnessExecutor} owns one and keeps its
 * public methods ({@code getRunBudget}, {@code spend}, ...) as thin delegates.
 */
final class BudgetCoordinator {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    /** Journal key prefix for a step's usage: {@code <step>#usage:<agent>}. */
    static final String USAGE = "#usage:";

    /** What the budgets need from the run. */
    interface Host {
        LoomScript script();

        java.time.Clock clock();

        RunJournal journal();

        /** The id of the step the calling thread is executing. */
        String stepId();

        List<String> modelsOf(AgentDef agent);

        io.github.llm4j.audit.AuditLogger auditLogger();

        String sessionId();

        HumanInterface humanInterface();
    }

    private final Host host;

    /** The budgets that enclose the step this thread is running (per thread). */
    private final ThreadLocal<List<io.github.llm4j.budget.Budget>> scopes;

    /** True when the script (or an override) declares any budget; only then are clients metered. */
    private boolean budgeting;

    private io.github.llm4j.budget.Budget runBudget;
    private final Map<String, io.github.llm4j.budget.Budget> agentBudgets = new HashMap<>();
    private io.github.llm4j.budget.PriceTable priceTable;
    private io.github.llm4j.budget.TokenEstimator tokenEstimator;
    private Long overrideTokens;
    private Long overrideCalls;
    private java.math.BigDecimal overrideCost;
    private final java.util.concurrent.atomic.AtomicBoolean budgetExhausted =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final List<SpendReport.Line> spendLines =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean spendRestored;

    /**
     * Per budget name: what happens when it runs out, and its limits as written (for "ask"
     * top-ups).
     */
    private final Map<String, BudgetDef.WhenExhausted> budgetPolicies =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, io.github.llm4j.budget.Budget> budgetsByName =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, io.github.llm4j.budget.Limits> originalLimits =
            new java.util.concurrent.ConcurrentHashMap<>();

    BudgetCoordinator(ThreadLocal<List<io.github.llm4j.budget.Budget>> scopes, Host host) {
        this.scopes = scopes;
        this.host = host;
    }

    boolean budgeting() {
        return budgeting;
    }

    io.github.llm4j.budget.Budget runBudget() {
        return runBudget;
    }

    io.github.llm4j.budget.Budget agentBudget(String agentName) {
        return agentBudgets.get(agentName);
    }

    void priceTable(io.github.llm4j.budget.PriceTable prices) {
        this.priceTable = prices;
    }

    void tokenEstimator(io.github.llm4j.budget.TokenEstimator estimator) {
        this.tokenEstimator = estimator;
    }

    void overrides(Long tokens, Long calls, java.math.BigDecimal cost) {
        this.overrideTokens = tokens;
        this.overrideCalls = calls;
        this.overrideCost = cost;
    }

    /** Records that some budget refused a call. */
    void markExhausted() {
        budgetExhausted.set(true);
    }

    /** What the run has spent so far: totals, per agent and per step. */
    SpendReport spend() {
        return new SpendReport(List.copyOf(spendLines));
    }

    /**
     * True when a budget has refused a call or has nothing left: a rewind would only spend what
     * isn't there.
     */
    boolean overBudget() {
        return budgeting && (anyRefused(null) || (runBudget != null && runBudget.exhausted()));
    }

    /** {@code _budget}: the run budget, read live. */
    Map<String, Object> view() {
        return new BudgetView();
    }

    /** Builds the run and agent budgets, and decides whether clients are metered at all. */
    void setUp() {
        io.github.llm4j.loom.ast.BudgetDef runDef = host.script().getBudget();
        boolean overridden =
                overrideTokens != null || overrideCalls != null || overrideCost != null;
        budgeting =
                runDef != null
                        || overridden
                        || host.script().getAgents().stream().anyMatch(a -> a.getBudget() != null)
                        || host.script().getWorkflows().stream()
                                .anyMatch(w -> hasStatementBudget(w.getStatements()));
        if (!budgeting) return;

        io.github.llm4j.loom.ast.BudgetDef merged = new io.github.llm4j.loom.ast.BudgetDef();
        if (runDef != null) {
            merged.setTokens(runDef.getTokens());
            merged.setCalls(runDef.getCalls());
            merged.setCost(runDef.getCost());
            merged.setWarnAt(runDef.getWarnAt());
            merged.setWindow(runDef.getWindow());
            merged.setWhenExhausted(runDef.getWhenExhausted());
        }
        if (overrideTokens != null) merged.setTokens(overrideTokens);
        if (overrideCalls != null) merged.setCalls(overrideCalls);
        if (overrideCost != null) merged.setCost(overrideCost);
        runBudget = newBudget("run", merged);

        for (AgentDef agentDef : host.script().getAgents()) {
            if (agentDef.getBudget() != null && agentDef.getBudget().hasLimits()) {
                agentBudgets.put(
                        agentDef.getName(),
                        newBudget("agent " + agentDef.getName(), agentDef.getBudget()));
            }
        }
        // A cost limit needs a price for every model it covers — say so now, not mid-run.
        for (AgentDef agentDef : host.script().getAgents()) {
            boolean costed =
                    runBudget.limits().cost() != null
                            || (agentBudgets.containsKey(agentDef.getName())
                                    && agentBudgets.get(agentDef.getName()).limits().cost()
                                            != null);
            if (!costed) continue;
            for (String model : host.modelsOf(agentDef)) {
                if (priceTable == null || priceTable.price(model).isEmpty()) {
                    throw new IllegalStateException(
                            "A cost budget covers agent "
                                    + agentDef.getName()
                                    + ", but model '"
                                    + model
                                    + "' has no price. Supply a price table (weave --prices <file>, or setPriceTable).");
                }
            }
        }
    }

    private static boolean hasStatementBudget(List<Statement> statements) {
        for (Statement st : statements) {
            if (st instanceof DelegateStmt d
                    && (d.getBudget() != null || hasStatementBudget(d.getOnFailure()))) return true;
            if (st instanceof io.github.llm4j.loom.ast.RunStmt r
                    && hasStatementBudget(r.getOnFailure())) return true;
            if (st instanceof io.github.llm4j.loom.ast.IntentClassifyStmt ic
                    && hasStatementBudget(ic.getOnFailure())) return true;
            if (st instanceof BroadcastStmt b && b.getBudget() != null) return true;
            if (st instanceof LoopStmt l
                    && (l.getBudget() != null
                            || hasStatementBudget(l.getBody())
                            || hasStatementBudget(l.getOnExhausted()))) return true;
            if (st instanceof ForEachStmt f
                    && (f.getBudget() != null || hasStatementBudget(f.getBody()))) return true;
            if (st instanceof AltStmt a
                    && (hasStatementBudget(a.getIfBranch())
                            || (a.getElseBranch() != null
                                    && hasStatementBudget(a.getElseBranch())))) return true;
            if (st instanceof ParallelStmt p && hasStatementBudget(p.getBody())) return true;
            if (st instanceof GuardrailStmt g
                    && (hasStatementBudget(g.getBody()) || hasStatementBudget(g.getOnViolation())))
                return true;
        }
        return false;
    }

    /** Meters a client against run + agent + enclosing budgets, when the script uses budgets. */
    io.github.llm4j.LLMClient metered(
            io.github.llm4j.LLMClient client, AgentDef agentDef, String model) {
        return metered(
                client,
                agentDef.getName(),
                agentDef.getBudget() != null ? agentDef.getBudget().getPerCall() : null,
                model);
    }

    io.github.llm4j.LLMClient metered(
            io.github.llm4j.LLMClient client, String agentName, Integer perCall, String model) {
        if (!budgeting || client == null) return client;
        io.github.llm4j.budget.BudgetedLLMClient metered =
                io.github.llm4j.budget.BudgetedLLMClient.builder(client)
                        .budgets(() -> budgetsFor(agentName))
                        .estimator(tokenEstimator)
                        .prices(priceTable)
                        .model(model)
                        .perCallCap(perCall)
                        .build();
        metered.addChargeListener(
                (m, charge) -> {
                    String stepId = host.stepId();
                    spendLines.add(new SpendReport.Line(stepId, agentName, m, charge));
                    journalUsage(stepId, agentName, m, charge);
                });
        return metered;
    }

    /**
     * Meters a classifier against run + agent + enclosing budgets, when the script uses budgets: a
     * classify call is charged like a model call.
     */
    io.github.llm4j.classify.Classifier meteredClassifier(
            io.github.llm4j.classify.Classifier classifier, String agentName, String model) {
        if (!budgeting || classifier == null) return classifier;
        io.github.llm4j.budget.BudgetedClassifier metered =
                io.github.llm4j.budget.BudgetedClassifier.builder(classifier)
                        .budgets(() -> budgetsFor(agentName))
                        .estimator(tokenEstimator)
                        .prices(priceTable)
                        .model(model)
                        .build();
        metered.addChargeListener(
                (m, charge) -> {
                    String stepId = host.stepId();
                    spendLines.add(new SpendReport.Line(stepId, agentName, m, charge));
                    journalUsage(stepId, agentName, m, charge);
                });
        return metered;
    }

    /**
     * Records what a step's calls cost, cumulatively per step and agent, so a resumed run knows
     * what was already paid for — including calls of a step that never finished (they were billed
     * all the same).
     */
    private synchronized void journalUsage(
            String stepId, String agentName, String model, io.github.llm4j.budget.Charge c) {
        String key = stepId + USAGE + agentName;
        Map<String, Object> prior =
                host.journal()
                        .get(key)
                        .map(
                                e ->
                                        e.value() instanceof Map<?, ?> m
                                                ? Vals.castMap(m)
                                                : Map.<String, Object>of())
                        .orElse(Map.of());
        Map<String, Object> usage = new java.util.LinkedHashMap<>();
        usage.put("agent", agentName);
        usage.put("model", model);
        usage.put("prompt", Vals.number(prior.get("prompt")) + c.promptTokens());
        usage.put("completion", Vals.number(prior.get("completion")) + c.completionTokens());
        usage.put("calls", Vals.number(prior.get("calls")) + c.calls());
        usage.put("cost", Vals.money(prior.get("cost")).add(c.cost()).toPlainString());
        usage.put("estimated", Boolean.TRUE.equals(prior.get("estimated")) || c.estimated());
        usage.put(
                "at",
                host.clock()
                        .millis()); // budget windows: a step's spend counts in the window of its
        // last call
        host.journal().put(key, new RunJournal.Entry("usage", usage));
    }

    /** A resumed run: counts what earlier runs already spent (from the journal), once. */
    synchronized void restoreSpend() {
        if (spendRestored) return;
        spendRestored = true;
        for (Map.Entry<String, RunJournal.Entry> e : host.journal().all().entrySet()) {
            if (!"usage".equals(e.getValue().kind())
                    || !(e.getValue().value() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> u = Vals.castMap(raw);
            io.github.llm4j.budget.Charge charge =
                    new io.github.llm4j.budget.Charge(
                            Vals.number(u.get("prompt")),
                            Vals.number(u.get("completion")),
                            (int) Vals.number(u.get("calls")),
                            Vals.money(u.get("cost")),
                            Boolean.TRUE.equals(u.get("estimated")));
            io.github.llm4j.budget.Spent spent = io.github.llm4j.budget.Spent.of(charge);
            Instant at =
                    u.get("at") == null ? null : Instant.ofEpochMilli(Vals.number(u.get("at")));
            runBudget.restore(spent, at);
            String agent = String.valueOf(u.get("agent"));
            if (agentBudgets.containsKey(agent)) agentBudgets.get(agent).restore(spent, at);
            String stepId = e.getKey().substring(0, e.getKey().indexOf(USAGE));
            spendLines.add(
                    new SpendReport.Line(stepId, agent, String.valueOf(u.get("model")), charge));
        }
    }

    private io.github.llm4j.budget.BudgetSet budgetsFor(String agentName) {
        return io.github.llm4j.budget.BudgetSet.of(runBudget, agentBudgets.get(agentName))
                .with(scopes.get().toArray(io.github.llm4j.budget.Budget[]::new));
    }

    io.github.llm4j.budget.Budget newBudget(String name, io.github.llm4j.loom.ast.BudgetDef def) {
        io.github.llm4j.budget.Budget.Builder b =
                io.github.llm4j.budget.Budget.builder().name(name);
        if (def.getTokens() != null) b.tokens(def.getTokens());
        if (def.getCalls() != null) b.calls(def.getCalls());
        if (def.getCost() != null) b.cost(def.getCost());
        if (def.getWarnAt() != null) b.warnAt(def.getWarnAt());
        if (def.getWindow() != null) b.window(def.getWindow());
        b.clock(host.clock());
        io.github.llm4j.budget.Budget budget = b.build();
        budgetPolicies.put(
                name,
                def.getWhenExhausted() != null
                        ? def.getWhenExhausted()
                        : BudgetDef.WhenExhausted.STOP);
        budgetsByName.put(name, budget);
        originalLimits.put(name, budget.limits());
        budget.addListener(
                event -> {
                    String kind =
                            event.kind() == io.github.llm4j.budget.BudgetEvent.Kind.WARNING
                                    ? "budget_warning"
                                    : "budget_refused";
                    log.warning(
                            kind
                                    + ": "
                                    + event.budget()
                                    + " spent "
                                    + event.spent().tokens()
                                    + " tokens, "
                                    + event.spent().calls()
                                    + " calls (limits: "
                                    + event.limits()
                                    + ")");
                    Map<String, Object> data = new java.util.LinkedHashMap<>();
                    data.put("budget", event.budget());
                    data.put("spentTokens", String.valueOf(event.spent().tokens()));
                    data.put("spentCalls", String.valueOf(event.spent().calls()));
                    data.put("spentCost", event.spent().cost().toPlainString());
                    data.put("limits", event.limits().toString());
                    host.auditLogger().logConversationEvent(host.sessionId(), null, kind, data);
                });
        return budget;
    }

    static List<io.github.llm4j.budget.Budget> plus(
            List<io.github.llm4j.budget.Budget> list, io.github.llm4j.budget.Budget more) {
        List<io.github.llm4j.budget.Budget> all = new java.util.ArrayList<>(list);
        all.add(more);
        return List.copyOf(all);
    }

    /**
     * True when {@code own}, the run budget, or any enclosing budget has already refused a call.
     */
    boolean anyRefused(io.github.llm4j.budget.Budget own) {
        if (!budgeting) return false;
        if (own != null && own.refused()) return true;
        if (runBudget != null && runBudget.refused()) return true;
        for (io.github.llm4j.budget.Budget b : scopes.get()) if (b.refused()) return true;
        return false;
    }

    /**
     * True when the refusing budget refills at a known time and its script says to wait for that.
     */
    boolean refills(io.github.llm4j.budget.BudgetExceeded be) {
        return be != null
                && be.resetAt().isPresent()
                && budgetPolicies.get(be.budget()) == BudgetDef.WhenExhausted.SUSPEND;
    }

    /**
     * {@code when_exhausted: ask}: asks a person whether to allow the budget's amount again. The
     * answer is journaled, so a resumed run gets the same top-up without asking twice. Returns true
     * when allowed (the budget has been raised); may pause the run ({@link RunSuspended}) until
     * someone answers.
     */
    boolean askForMore(io.github.llm4j.budget.BudgetExceeded be, int[] asks) {
        if (be == null || budgetPolicies.get(be.budget()) != BudgetDef.WhenExhausted.ASK)
            return false;
        io.github.llm4j.budget.Budget budget = budgetsByName.get(be.budget());
        io.github.llm4j.budget.Limits original = originalLimits.get(be.budget());
        if (budget == null || original == null) return false;
        String key = host.stepId() + "#more:" + be.budget() + ":" + (++asks[0]);
        String answer = host.journal().get(key).map(e -> String.valueOf(e.value())).orElse(null);
        if (answer == null) {
            if (host.humanInterface() == null) return false; // nobody to ask: stop as usual
            String question =
                    "Budget "
                            + be.budget()
                            + " is used up ("
                            + be.getMessage()
                            + "). Allow "
                            + describe(original)
                            + " more? yes/no";
            answer =
                    host.humanInterface()
                            .promptHuman(
                                    key,
                                    question,
                                    new HumanInterface.Hints(
                                            HumanInterface.Hints.Kind.APPROVAL,
                                            java.util.List.of("yes", "no"),
                                            null));
            host.journal().put(key, new RunJournal.Entry("human", answer));
        }
        String a = answer.trim().toLowerCase(java.util.Locale.ROOT);
        if (!(a.startsWith("y") || a.equals("ok") || a.equals("approve") || a.equals("true")))
            return false;
        budget.raise(original);
        log.info("Budget " + be.budget() + " raised by " + describe(original) + " (approved)");
        return true;
    }

    private static String describe(io.github.llm4j.budget.Limits l) {
        List<String> parts = new java.util.ArrayList<>();
        if (l.tokens() != null) parts.add(l.tokens() + " tokens");
        if (l.calls() != null) parts.add(l.calls() + " calls");
        if (l.cost() != null) parts.add("$" + l.cost().stripTrailingZeros().toPlainString());
        return String.join(", ", parts);
    }

    /** {@code _budget.*}: the run budget, read live. */
    final class BudgetView extends java.util.AbstractMap<String, Object> {
        @Override
        public java.util.Set<Map.Entry<String, Object>> entrySet() {
            io.github.llm4j.budget.Spent spent = runBudget.spent();
            java.util.OptionalLong left = runBudget.remaining().tokens();
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("spent", spent.tokens());
            view.put("remaining", left.isPresent() ? (Object) left.getAsLong() : "unlimited");
            view.put("calls", spent.calls());
            view.put("cost", spent.cost().stripTrailingZeros().toPlainString());
            view.put("exhausted", String.valueOf(budgetExhausted.get() || runBudget.exhausted()));
            return view.entrySet();
        }

        @Override
        public String toString() {
            return "budget" + entrySet();
        }
    }
}
