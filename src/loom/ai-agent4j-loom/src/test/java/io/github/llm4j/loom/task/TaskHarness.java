package io.github.llm4j.loom.task;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs scripts against a scripted model (no network, no keys) that counts its calls, plus a trace and audit capture, a journal,
 * a task registry, and a human who says yes. The model call counter is the oracle for "deterministic".
 */
final class TaskHarness {

    final AtomicInteger modelCalls = new AtomicInteger();
    final List<String> modelPrompts = Collections.synchronizedList(new ArrayList<>());
    final List<String> answers = new ArrayList<>();
    final List<TraceEvent> trace = Collections.synchronizedList(new ArrayList<>());
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    final TaskRegistry tasks = new TaskRegistry();
    final ToolRegistry tools = new ToolRegistry();
    RunJournal journal = RunJournal.inMemory();
    HumanInterface human = message -> "yes";

    static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer.replace("\"", "\\\"") + "\"}\n```";
    }

    static String call(String tool, String inputJson) {
        return "```json\n{\"thought\": \"use " + tool + "\", \"action\": \"" + tool + "\", \"action_input\": " + inputJson + "}\n```";
    }

    TaskHarness answers(String... next) {
        answers.addAll(List.of(next));
        return this;
    }

    /** Counts calls of a task and records what each saw. */
    static final class Probe {
        final AtomicInteger calls = new AtomicInteger();
        final List<TaskContext> contexts = Collections.synchronizedList(new ArrayList<>());
    }

    Probe register(String name, TaskEffect effect, EffectPolicy policy, Body body) {
        Probe probe = new Probe();
        tasks.register(Task.of(name, effect, policy, ctx -> {
            probe.calls.incrementAndGet();
            probe.contexts.add(ctx);
            return body.run(ctx);
        }));
        return probe;
    }

    Probe pure(String name, Body body) {
        return register(name, TaskEffect.NONE, EffectPolicy.DEFAULT, body);
    }

    Probe changes(String name, Body body) {
        return register(name, TaskEffect.CHANGES, EffectPolicy.DEFAULT, body);
    }

    Probe idempotent(String name, Body body) {
        return register(name, TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0), body);
    }

    @FunctionalInterface
    interface Body {
        TaskResult run(TaskContext ctx) throws Exception;
    }

    LLMClient client(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                int i = modelCalls.getAndIncrement();
                var m = request.getMessages();
                modelPrompts.add(m.get(m.size() - 1).getContent());
                String content = i < answers.size() ? answers.get(i) : done("ok");
                return LLMResponse.builder().content(content).model(model).tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    HarnessExecutor executor(String source) {
        return executor(source, null);
    }

    HarnessExecutor executor(String source, Consumer<HarnessExecutor> configure) {
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), tools, this::client);
        e.setTaskRegistry(tasks);
        e.setHumanInterface(human);
        e.setJournal(journal);
        e.setSleeper(sleeps::add);
        e.setAuditLogger(new AuditLogger() {
            @Override public void logAgentDecision(AuditEvent event) { }
            @Override public void logToolExecution(String s, String t, String i, String o, Instant ts) { audit.add("tool " + t); }
            @Override public void logPromptUsage(String s, String p, String v, Instant ts) { }
            @Override public void logConversationEvent(String s, String u, String type, Map<String, Object> data) { audit.add(type + " " + data); }
        });
        e.addTraceListener(trace::add);
        if (configure != null) configure.accept(e);
        return e;
    }

    /** Initializes and runs {@code workflow}; returns the executor so a test can read variables. */
    HarnessExecutor run(String source, String workflow, Map<String, String> inputs) {
        HarnessExecutor e = executor(source);
        e.initialize();
        e.executeWorkflow(workflow, inputs);
        return e;
    }

    HarnessExecutor run(String source) {
        return run(source, "Main", new HashMap<>());
    }

    List<String> traceTypes() {
        synchronized (trace) {
            return trace.stream().map(TraceEvent::type).toList();
        }
    }

    List<TraceEvent> traceOf(String type) {
        synchronized (trace) {
            return trace.stream().filter(t -> t.type().equals(type)).toList();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
