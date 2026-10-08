package io.github.llm4j.loom.depth;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parity.HashingEmbeddingProvider;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
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
 * Executors over one scripted model that records every request; an audit log and a trace captured as
 * lists; embeddings from {@link HashingEmbeddingProvider}; an injected environment.
 */
final class Harness {

    final Path dir;
    final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    final List<String> models = Collections.synchronizedList(new ArrayList<>());
    final List<String> answers = new ArrayList<>();
    final AtomicInteger answered = new AtomicInteger();
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    final List<TraceEvent> trace = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> env = new HashMap<>();
    final ToolRegistry tools = new ToolRegistry();
    RunJournal journal;

    Harness(Path dir) {
        this.dir = dir;
    }

    static String call(String tool, String inputJson) {
        return "```json\n{\"thought\": \"use " + tool + "\", \"action\": \"" + tool + "\", \"action_input\": " + inputJson + "}\n```";
    }

    static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer.replace("\"", "\\\"") + "\"}\n```";
    }

    Harness answers(String... next) {
        answers.addAll(List.of(next));
        return this;
    }

    LLMClient client(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                requests.add(request);
                models.add(model);
                int i = answered.getAndIncrement();
                String content = i < answers.size() ? answers.get(i) : done("ok");
                return LLMResponse.builder().content(content).model(model).tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    /** Every message of every request, joined: what the model saw. */
    String seen() {
        StringBuilder sb = new StringBuilder();
        synchronized (requests) {
            for (LLMRequest r : requests) r.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        }
        return sb.toString();
    }

    /** The last message of request {@code i} (the task and scratchpad). */
    String task(int i) {
        List<io.github.llm4j.model.Message> m = requests.get(i).getMessages();
        return m.get(m.size() - 1).getContent();
    }

    HarnessExecutor executor(String source, Consumer<HarnessExecutor> configure) {
        return executor(source, this::client, configure);
    }

    HarnessExecutor executor(String source, LLMClientFactory factory, Consumer<HarnessExecutor> configure) {
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), tools, factory);
        e.setBaseDir(dir);
        e.setEnvLookup(env::get);
        e.setEmbeddingFactory(model -> new HashingEmbeddingProvider());
        e.setHumanInterface(message -> "yes");
        if (journal != null) e.setJournal(journal);
        e.setAuditLogger(new AuditLogger() {
            @Override public void logAgentDecision(AuditEvent event) { }
            @Override public void logToolExecution(String s, String t, String i, String o, Instant ts) {
                audit.add("tool " + t);
            }
            @Override public void logPromptUsage(String s, String p, String v, Instant ts) { }
            @Override public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                audit.add(type + " " + data);
            }
        });
        e.addTraceListener(trace::add);
        if (configure != null) configure.accept(e);
        return e;
    }

    HarnessExecutor ready(String source) {
        HarnessExecutor e = executor(source, null);
        e.initialize();
        return e;
    }

    /** A tool that records its calls. */
    static Tool recording(String name, List<Map<String, Object>> calls, String reply) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "test tool " + name; }
            @Override public String execute(Map<String, Object> args) {
                calls.add(args);
                return reply;
            }
        };
    }
}
