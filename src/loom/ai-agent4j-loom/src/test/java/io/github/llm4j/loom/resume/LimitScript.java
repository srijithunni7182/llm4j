package io.github.llm4j.loom.resume;

import io.github.llm4j.LLMClient;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * One run (or resume) of a script against scripted models. Agents are recognised by "You are X." in
 * their system prompt and answer {@code X#n} (n counts this instance's calls). A {@link Gate} decides,
 * per agent and task, whether a call is refused with a rate limit — tests use the shared clock to
 * model a provider whose limit lifts at a given time. Every successful call reports 100 + 50 tokens.
 */
final class LimitScript {

    /** Returns the limit that refuses this call, or null to answer. */
    interface Gate extends BiFunction<String, String, RateLimitInfo> { }

    static final TokenEstimator FIXED = new TokenEstimator() {
        @Override
        public long prompt(LLMRequest request) {
            return 100;
        }

        @Override
        public long completion(String content) {
            return 50;
        }
    };

    /** Refuses {@code agent} until {@code until} (by the clock), as a provider's per-minute limit would. */
    static Gate limitedUntil(TestClock clock, String agent, Instant until) {
        return (a, task) -> a.equals(agent) && clock.instant().isBefore(until)
                ? RateLimitInfo.at(until, RateLimitInfo.Scope.REQUESTS, "test", "limited until " + until) : null;
    }

    final HarnessExecutor executor;
    final RunJournal journal;
    final TestClock clock;
    final List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, Object>> auditData = Collections.synchronizedList(new ArrayList<>());
    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger refusals = new AtomicInteger();
    final Map<String, AtomicInteger> callsByAgent = new ConcurrentHashMap<>();

    LimitScript(String source, RunJournal journal, TestClock clock, Gate gate) {
        this(source, journal, clock, gate, null);
    }

    LimitScript(String source, RunJournal journal, TestClock clock, Gate gate,
                java.util.function.Consumer<HarnessExecutor> configure) {
        this.journal = journal;
        this.clock = clock;
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        LLMClientFactory factory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                String agent = system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
                RateLimitInfo limit = gate == null ? null : gate.apply(agent, user);
                if (limit != null) {
                    refusals.incrementAndGet();
                    throw new RateLimitException(limit, clock.instant());
                }
                calls.incrementAndGet();
                tasks.add(user);
                int n = callsByAgent.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                return LLMResponse.builder()
                        .content("```json\n{\"thought\": \"t\", \"final_answer\": \"" + agent + "#" + n + "\"}\n```")
                        .model(model).tokenUsage(100, 50, 150).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.setJournal(journal);
        executor.setClock(clock);
        executor.setSleeper(d -> {
            slept.add(d);
            clock.advance(d);
        });
        executor.setTokenEstimator(FIXED);
        executor.setAuditLogger(new AuditLogger() {
            @Override
            public void logAgentDecision(AuditEvent event) { }

            @Override
            public void logToolExecution(String s, String t, String i, String o, Instant ts) { }

            @Override
            public void logPromptUsage(String s, String p, String v, Instant ts) { }

            @Override
            public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                audit.add(type);
                auditData.add(Map.copyOf(data));
            }
        });
        if (configure != null) configure.accept(executor);
        executor.initialize();
    }

    LimitScript run() {
        executor.executeWorkflow("Main", Map.of());
        return this;
    }

    Object var(String name) {
        return executor.getContext().getVariable(name);
    }

    int calls(String agent) {
        AtomicInteger n = callsByAgent.get(agent);
        return n == null ? 0 : n.get();
    }

    long audited(String type) {
        return audit.stream().filter(type::equals).count();
    }
}
