package io.github.llm4j.telemetry;

import io.github.llm4j.agent.Tool;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A tool whose behaviour a test supplies; it may move the test clock to stand for time spent. */
final class TestTool implements Tool {

    @FunctionalInterface
    interface Body {
        String run(Map<String, Object> args, int call) throws Exception;
    }

    final AtomicInteger calls = new AtomicInteger();
    private final String name;
    private final Body body;
    boolean approval;
    java.util.function.Predicate<String> failure; // when set, overrides isFailure

    TestTool(String name, Body body) {
        this.name = name;
        this.body = body;
    }

    static TestTool returning(String name, String result) {
        return new TestTool(name, (a, n) -> result);
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return "test tool " + name; }
    @Override public boolean requiresApproval(Map<String, Object> args) { return approval; }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        return body.run(args, calls.incrementAndGet());
    }

    @Override
    public boolean isFailure(String result) {
        return failure != null ? failure.test(result) : Tool.super.isFailure(result);
    }
}
