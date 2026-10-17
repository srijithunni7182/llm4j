package io.github.llm4j.loom.host;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.resume.TestClock;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * One host under test: scripted models that count their calls, a task that counts its effects, a clock the test moves, and a store of the kind under test.
 * {@link #reopen} builds a second host on the same store, as a restart would.
 */
final class HostEnv {

    static final String AGENTS = """
            agent Researcher { model: "test/model" system: "You are Researcher." }
            agent Drafter { model: "test/model" system: "You are Drafter." }
            agent Reviewer { model: "test/model" system: "You are Reviewer." }
            agent Writer { model: "test/model" system: "You are Writer." }
            """;

    static final String THREE_STEPS = AGENTS + """
            workflow Main() {
                delegate "research" to Researcher -> research
                delegate "draft {research}" to Drafter -> draft
                delegate "review {draft}" to Reviewer -> review
            }
            """;

    static final String APPROVE = AGENTS + """
            workflow Main() {
                delegate "draft" to Writer -> draft
                human_prompt "Publish? (yes/no)" -> ok
                run Notify(msg = ok) -> sent
            }
            """;

    static final String SLEEPY = AGENTS + """
            workflow Main() {
                delegate "research" to Researcher -> research
                sleep 1h
                delegate "draft {research}" to Drafter -> draft
            }
            """;

    static final String SCHEDULED = AGENTS + """
            workflow Digest() { delegate "digest" to Writer -> out }
            schedule Daily { cron: "0 7 * * *" run: Digest() }
            """;

    static final String[] KINDS = {"file", "jdbc"};

    final String kind;
    final Path dir;
    final DataSource ds;
    final TestClock clock = new TestClock();
    final Models models = new Models(clock);
    final AtomicInteger effects = new AtomicInteger();
    final List<String> questions = Collections.synchronizedList(new ArrayList<>());
    final List<HarnessExecutorSpy> spies = Collections.synchronizedList(new ArrayList<>());
    final TaskRegistry tasks = new TaskRegistry();
    LoomHost host;

    HostEnv(String kind, Path dir) {
        this.kind = kind;
        this.dir = dir;
        if (kind.equals("jdbc")) {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            this.ds = h2;
            LoomHost.createSchema(ds);
        } else {
            this.ds = null;
        }
        tasks.register(new Task() {
            @Override public String getName() { return "Notify"; }
            @Override public TaskResult run(TaskContext context) {
                effects.incrementAndGet();
                return TaskResult.value("notified " + context.arg("msg"));
            }
        });
    }

    /** An executor was assembled and then shut down: how a test sees that {@code shutdown()} ran. */
    interface HarnessExecutorSpy { }

    Persistence persistence() {
        return switch (kind) {
            case "file" -> Persistence.file(dir);
            case "jdbc" -> Persistence.jdbc(ds);
            default -> Persistence.none();
        };
    }

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    LoomHost.Builder builder(String source) {
        return LoomHost.builder(parse(source), new ToolRegistry(), models)
                .persistence(persistence()).clock(clock).sleeper(d -> clock.advance(d)).taskRegistry(tasks)
                .questionListener((run, step, prompt, choices) -> questions.add(run + "|" + step + "|" + prompt));
    }

    HostEnv open(String source) {
        return open(source, b -> { });
    }

    HostEnv open(String source, Consumer<LoomHost.Builder> configure) {
        LoomHost.Builder b = builder(source);
        configure.accept(b);
        host = b.build();
        return this;
    }

    /** A second host on the same store: what a restart finds. The first is closed. */
    HostEnv reopen(String source) {
        if (host != null) host.close();
        return open(source);
    }

    static RunOutcome.Suspended suspended(RunOutcome o) {
        if (o instanceof RunOutcome.Suspended s) return s;
        throw new AssertionError("expected a suspended run, got " + o);
    }

    /** Waits (without sleeping for a fixed time) until {@code check} is true. */
    static void await(Supplier<Boolean> check, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.get()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Scripted models: an agent named in "You are X." answers {@code X#n}; a gate can refuse a call; a latch can hold one. */
    static final class Models implements LLMClientFactory {
        private final TestClock clock;
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        volatile BiFunction<String, String, RateLimitInfo> gate;
        volatile String failAgent;
        volatile CountDownLatch holdAt;
        volatile CountDownLatch entered;
        volatile String holdAgent;

        Models(TestClock clock) { this.clock = clock; }

        int calls(String agent) {
            AtomicInteger n = calls.get(agent);
            return n == null ? 0 : n.get();
        }

        int total() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        @Override
        public LLMClient createClient(String model) {
            return new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest request) {
                    String system = request.getMessages().get(0).getContent();
                    String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                    String agent = system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
                    BiFunction<String, String, RateLimitInfo> g = gate;
                    RateLimitInfo limit = g == null ? null : g.apply(agent, user);
                    if (limit != null) throw new RateLimitException(limit, clock.instant());
                    if (agent.equals(failAgent)) throw new io.github.llm4j.exception.ProviderException("test", "scripted failure", (Throwable) null);
                    if (agent.equals(holdAgent) && holdAt != null) {
                        if (entered != null) entered.countDown();
                        try {
                            holdAt.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int n = calls.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                    return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"" + agent + "#" + n + "\"}\n```")
                            .model(model).tokenUsage(100, 50, 150).build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.of(chat(request));
                }
            };
        }
    }

    static BiFunction<String, String, RateLimitInfo> limitedUntil(TestClock clock, String agent, Instant until) {
        return (a, task) -> a.equals(agent) && clock.instant().isBefore(until)
                ? RateLimitInfo.at(until, RateLimitInfo.Scope.REQUESTS, "test", "limited until " + until) : null;
    }
}
