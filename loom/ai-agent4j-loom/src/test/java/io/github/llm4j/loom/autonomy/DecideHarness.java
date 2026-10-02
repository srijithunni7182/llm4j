package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the same decision over many cases, each its own run with its own journal, against one ledger and one level store: the way a workflow is
 * used in practice. The agent and the person are scripted as functions of what the case contains.
 */
final class DecideHarness {

    static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    final Path dir;
    final Ledger ledger;
    final LevelStore levels;
    final MutableClock clock = new MutableClock(T0);
    /** Every question any person was asked, in order. */
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    /** Every audit event type and trace line seen across the runs. */
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, Object>> auditData = Collections.synchronizedList(new ArrayList<>());
    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    int modelCalls;
    /** Tools the host registers, by name. */
    final Map<String, io.github.llm4j.agent.Tool> tools = new LinkedHashMap<>();

    /** What the agent proposes for a case, from the values it was given: {choice, reasoning[, confidence]}. */
    Function<Map<String, String>, String[]> agent = f -> new String[] {"approve", "looks fine", "0.9"};
    /** What the person decides, from the case's values. */
    Function<Map<String, String>, String> person = f -> "approve";
    String script;
    /** Tool calls the agent makes, one per step, before it proposes: {tool, argsJson}. Empty: it proposes at once. */
    List<String[]> toolCalls = new ArrayList<>();
    private int runs;
    ScriptedRun last;
    HarnessExecutor lastExecutor;

    DecideHarness(Path dir, Ledger ledger, LevelStore levels, String script) {
        this.dir = dir;
        this.ledger = ledger;
        this.levels = levels;
        this.script = script;
    }

    private static final Pattern LINE = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*) = (.*)$");

    static Map<String, String> fieldsOf(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LINE.matcher(text);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private String task(LLMRequest r) {
        String message = ScriptedRun.lastMessage(r);
        int marker = message.lastIndexOf("Current Task:");
        return marker < 0 ? message : message.substring(marker + "Current Task:".length()).trim();
    }

    ScriptedRun newRun(RunJournal journal) {
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = journal;
        tools.forEach(run.tools::register);
        run.responder = r -> {
            String task = task(r);
            modelCalls++;
            tasks.add(task);
            String[] says = agent.apply(fieldsOf(task));
            if (!toolCalls.isEmpty()) {
                int seen = ScriptedRun.lastMessage(r).split("Observation:", -1).length - 1;
                if (seen < toolCalls.size()) return ScriptedRun.call(toolCalls.get(seen)[0], toolCalls.get(seen)[1]);
                return ScriptedRun.done("{\"choice\": \"" + says[0] + "\", \"reasoning\": \"" + says[1] + "\"" + (says.length > 2 && says[2] != null ? ", \"confidence\": " + says[2] : "") + "}");
            }
            return "```json\n{\"choice\": \"" + says[0] + "\", \"reasoning\": \"" + says[1] + "\""
                    + (says.length > 2 && says[2] != null ? ", \"confidence\": " + says[2] : "") + "}\n```";
        };
        run.human = q -> {
            asked.add(q);
            return person.apply(fieldsOf(q));
        };
        return run;
    }

    HarnessExecutor executor(ScriptedRun run, String runId) {
        HarnessExecutor e = run.executor(script);
        e.setRunId(runId);
        e.setClock(clock);
        e.setAutonomy(ledger, levels);
        e.addTraceListener(t -> trace.add(t.type() + ": " + t.text()));
        e.initialize();
        last = run;
        lastExecutor = e;
        return e;
    }

    /** One case, as its own run. */
    Map<String, Object> runCase(String id, Map<String, String> inputs) {
        ScriptedRun run = newRun(RunJournal.inMemory());
        HarnessExecutor e = executor(run, id);
        try {
            e.executeWorkflow("Triage", inputs);
        } finally {
            audit.addAll(run.audit);
            auditData.addAll(run.auditData);
        }
        return e.getContext().getAll();
    }

    Map<String, String> inputs(String tier, int amount) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tier", tier);
        m.put("amount", String.valueOf(amount));
        m.put("reason", "damaged");
        m.put("customer_since", "2020");
        m.put("ticket", "T-" + amount);
        return m;
    }

    /** The identity of the agent behind the Refund decision in this script, for seeding a level. */
    String hashOfRefund() {
        var parsed = DecisionParseTest.parse(script);
        return AgentIdentity.of(parsed, parsed.getDecisions().get(0), dir);
    }

    DecideHarness also(java.util.function.Consumer<DecideHarness> change) {
        change.accept(this);
        return this;
    }

    void day() {
        clock.advance(Duration.ofDays(1));
    }
}
