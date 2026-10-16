package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** Scripted models, a counting task and an in-memory database for the Loom tests. */
final class LoomFixtures {

    private LoomFixtures() { }

    /** An agent named in "You are X." answers {@code X#n}; a latch can hold one agent's call so a test can look at a run in flight. */
    static final class Models implements LLMClientFactory {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        volatile String failAgent;
        volatile String holdAgent;
        volatile CountDownLatch hold;
        volatile CountDownLatch entered;

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
                    String agent = system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
                    if (agent.equals(failAgent)) throw new io.github.llm4j.exception.ProviderException("test", "scripted failure", (Throwable) null);
                    if (agent.equals(holdAgent) && hold != null) {
                        if (entered != null) entered.countDown();
                        try {
                            hold.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int n = calls.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                    return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"" + agent + "#" + n + "\"}\n```").model(model).tokenUsage(100, 50, 150).build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.of(chat(request));
                }
            };
        }
    }

    /** A task that does something in the world; counts how many times it did. */
    static final class Notify implements Task {
        final AtomicInteger effects = new AtomicInteger();

        @Override public String getName() { return "Notify"; }

        @Override
        public TaskResult run(TaskContext context) {
            effects.incrementAndGet();
            return TaskResult.value("notified " + context.arg("msg"));
        }
    }

    static final class Refund implements Task {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() { return "Refund"; }

        @Override
        public TaskResult run(TaskContext context) {
            calls.incrementAndGet();
            return TaskResult.value(Map.of("approved", true, "amount", String.valueOf(context.arg("amount"))));
        }
    }

    /** A fresh in-memory H2 that outlives any one context (so a restart can be played) until the JVM ends. */
    static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
