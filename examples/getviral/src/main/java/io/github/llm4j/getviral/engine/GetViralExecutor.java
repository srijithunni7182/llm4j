package io.github.llm4j.getviral.engine;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.getviral.studio.StudioRun;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loom's {@link HarnessExecutor}, specialised through its extension hooks:
 * <ul>
 *   <li>{@code customizeAgent} — streams every thought / tool call / observation to the studio and
 *       wires the Human-in-the-Loop approval gate.</li>
 *   <li>{@code agentForDelegate} — injects the Showrunner-written prompt for this agent, on the fly.</li>
 *   <li>{@code afterDelegateExecution} — versions new casting sheets, records results for eval4j,
 *       and caps the critic loop (a symbolic budget the neural critic can't talk its way past).</li>
 * </ul>
 */
public class GetViralExecutor extends HarnessExecutor {

    public static final String ORCHESTRATOR = "Showrunner";
    public static final String CRITIC = "ViralityCritic";

    private final StudioRun run;
    private final PromptBook promptBook;
    private final int maxRevisions;
    private final AtomicInteger criticRounds = new AtomicInteger();
    private final Map<String, List<AgentResult>> results = new ConcurrentHashMap<>();
    private volatile Runnable onShip = () -> { };

    public GetViralExecutor(LoomScript script, ToolRegistry tools, LLMClientFactory models,
                            StudioRun run, PromptBook promptBook, int maxRevisions) {
        super(script, tools, models);
        this.run = run;
        this.promptBook = promptBook;
        this.maxRevisions = maxRevisions;
    }

    /** Every AgentResult produced in this run, per agent — what the eval4j suite asserts on. */
    public Map<String, List<AgentResult>> results() {
        return results;
    }

    /** Called once the critic ships the pack — GetViral starts grading while the creator decides on publishing. */
    public void onShip(Runnable onShip) {
        this.onShip = onShip;
    }

    public int criticRounds() {
        return criticRounds.get();
    }

    @Override
    protected void customizeAgent(AgentDef agentDef, ReActAgent.Builder builder) {
        String agent = agentDef.getName();
        // The ArtDirector makes five images, one tool call each — give it room.
        builder.maxIterations(agent.equals("ArtDirector") || agent.equals("VideoEditor") ? 12 : 8)
               .temperature(0.8)
               .approvalCallback((tool, args, thought) -> run.approve(tool, args, thought))
               .addListener(new AgentEventListener() {
                   @Override
                   public void onThought(String thought) {
                       run.emit("thought", Map.of("agent", agent, "text", thought));
                   }

                   @Override
                   public void onAction(String tool, String input) {
                       run.emit("action", Map.of("agent", agent, "tool", tool, "input", input == null ? "" : input));
                   }

                   @Override
                   public void onObservation(String observation) {
                       run.emit("observation", Map.of("agent", agent, "text", clip(observation, 1200)));
                   }

                   @Override
                   public void onApprovalRequired(String tool, Map<String, Object> args, String thought) {
                       run.emit("approval_needed", Map.of("agent", agent, "tool", tool));
                   }
               });
    }

    @Override
    protected ReActAgent agentForDelegate(DelegateStmt stmt, AgentDef agentDef, ReActAgent agent) {
        return promptBook.current(agentDef.getName())
                .map(version -> {
                    String framed = PromptBook.frame(agentDef.getName(), version.prompt());
                    run.emit("prompt_injected", Map.of("agent", agentDef.getName(), "version", version.version()));
                    boolean usesTools = !agentDef.getTools().isEmpty() || !agentDef.getMcpServers().isEmpty();
                    ReActAgent.Builder rebuilt = agent.toBuilder().systemPrompt(null);
                    return usesTools
                            ? rebuilt.instructions(framed).build()
                            : rebuilt.systemPrompt(framed).build();
                })
                .orElse(agent);
    }

    @Override
    protected String beforeDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload,
                                             String assembledContext) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agentDef.getName());
        data.put("variable", stmt.getVariableName());
        data.put("task", clip(resolvedPayload, 600));
        if (CRITIC.equals(agentDef.getName())) data.put("round", criticRounds.get() + 1);
        run.emit("agent_start", data);
        return assembledContext;
    }

    @Override
    protected void afterDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload,
                                          String effectiveContext, AgentResult result, Object finalValue,
                                          DelegateSuccessHandler successHandler) {
        String agent = agentDef.getName();
        results.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>()).add(result);
        Object value = finalValue;

        if (ORCHESTRATOR.equals(agent)) {
            boolean recast = resolvedPayload.startsWith("RECAST");
            promptBook.apply(value, recast ? "Re-cast after critic feedback" : "Cast for this brief");
        }

        if (CRITIC.equals(agent)) {
            value = enforceRevisionBudget(value);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agent);
        data.put("variable", stmt.getVariableName());
        data.put("value", value);
        data.put("iterations", result.getIterations());
        data.put("completed", result.isCompleted());
        data.put("tools", result.getSteps().stream().map(AgentResult.AgentStep::getAction).toList());
        if (result.getConfidence() != null) data.put("confidence", result.getConfidence().getScore());
        run.emit("agent_done", data);

        successHandler.onSuccess(result, value);
        if (CRITIC.equals(agent) && value instanceof Map<?, ?> report && "SHIP".equals(report.get("verdict"))) {
            onShip.run();
        }
    }

    @Override
    protected void onDelegateError(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload,
                                   String effectiveContext, Exception error, int attempt, int maxAttempts) {
        run.emit("agent_error", Map.of("agent", agentDef.getName(), "error", String.valueOf(error.getMessage()),
                "attempt", attempt, "maxAttempts", maxAttempts));
    }

    /**
     * The critic loop is neural (the critic decides), but its budget is symbolic: after
     * {@code maxRevisions} revision rounds the pack ships regardless, with an honest note.
     */
    @SuppressWarnings("unchecked")
    private Object enforceRevisionBudget(Object value) {
        int round = criticRounds.incrementAndGet();
        Map<String, Object> report;
        if (value instanceof Map<?, ?> map) {
            report = new LinkedHashMap<>((Map<String, Object>) map);
        } else {
            report = new LinkedHashMap<>();
            report.put("verdict", "SHIP");
            report.put("headline", clip(String.valueOf(value), 200));
        }
        report.put("round", round);
        String verdict = String.valueOf(report.get("verdict")).trim().toUpperCase();
        if (!verdict.equals("SHIP") && round > maxRevisions) {
            report.put("verdict", "SHIP");
            report.put("budget_note", "Revision budget of " + maxRevisions
                    + " rounds reached — shipping the strongest version so far.");
        } else if (!verdict.equals("SHIP") && !verdict.equals("REVISE")) {
            report.put("verdict", "REVISE");
        } else {
            report.put("verdict", verdict);
        }
        return report;
    }

    static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
