package io.github.llm4j.loom.generic.support;

import io.github.llm4j.LLMClient;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A real Loom executor over a scripted model: the model's replies are fixed in advance (tool calls and
 * final answers in the agent's JSON protocol), and everything the run reports is captured.
 */
public final class ScriptedRun {

    public final Path dir;
    public final Map<String, String> env = new HashMap<>();
    public final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    public final List<Map<String, Object>> auditData = Collections.synchronizedList(new ArrayList<>());
    public final List<TraceEvent> trace = Collections.synchronizedList(new ArrayList<>());
    public final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    public final List<String> questions = Collections.synchronizedList(new ArrayList<>());
    public RunJournal journal;
    public Function<String, String> human = q -> "yes";
    /** When set, decides each reply from the request instead of the fixed list (for runs with parallel agents). */
    public Function<LLMRequest, String> responder;
    private final List<String> replies = new ArrayList<>();
    private final AtomicInteger answered = new AtomicInteger();

    public ScriptedRun(Path dir) {
        this.dir = dir;
    }

    /** The model's next replies, in order; once they run out it answers "ok". */
    public ScriptedRun replies(String... next) {
        replies.addAll(List.of(next));
        return this;
    }

    public static String call(String tool, String argsJson) {
        return "```json\n{\"thought\": \"use " + tool + "\", \"action\": \"" + tool + "\", \"action_input\": " + argsJson + "}\n```";
    }

    public static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer.replace("\"", "\\\"") + "\"}\n```";
    }

    public HarnessExecutor executor(String source) {
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                requests.add(request);
                int i = answered.getAndIncrement();
                String content = responder != null ? responder.apply(request) : i < replies.size() ? replies.get(i) : done("ok");
                return LLMResponse.builder().content(content).model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), new ToolRegistry(), x -> model);
        e.setBaseDir(dir);
        e.setEnvLookup(env::get);
        e.setHumanInterface(new HumanInterface() {
            @Override public String promptHuman(String message) {
                questions.add(message);
                return human.apply(message);
            }
        });
        if (journal != null) e.setJournal(journal);
        e.setAuditLogger(new AuditLogger() {
            @Override public void logAgentDecision(AuditEvent event) { }
            @Override public void logToolExecution(String s, String t, String i, String o, Instant ts) { }
            @Override public void logPromptUsage(String s, String p, String v, Instant ts) { }
            @Override public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                audit.add(type);
                auditData.add(data);
            }
        });
        e.addTraceListener(trace::add);
        return e;
    }

    /** The last message of a request: the task and, once tools have run, their observations. */
    public static String lastMessage(LLMRequest request) {
        List<io.github.llm4j.model.Message> m = request.getMessages();
        return m.get(m.size() - 1).getContent();
    }

    /** Everything the run reported, as one string. */
    public String everything() {
        return audit + " " + auditData + " " + trace + " " + (journal == null ? "" : journal.all().toString());
    }

    /** What the model was sent, joined. */
    public String seen() {
        StringBuilder sb = new StringBuilder();
        synchronized (requests) {
            for (LLMRequest r : requests) r.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        }
        return sb.toString();
    }
}
