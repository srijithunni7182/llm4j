package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.List;

/**
 * Makes a conversation smaller so that it fits the model's context. Pick a built-in from {@link ContextCompactors}, or
 * write your own; one method is enough, so a lambda works:
 *
 * <pre>{@code
 * agent.compaction((messages, request) -> CompactionResult.of("mine", messages, myTrim(messages), request));
 * }</pre>
 *
 * <p>The rules a compactor follows (the framework relies on them, and the shared contract test checks them):
 * <ol>
 *   <li><b>Pure.</b> Do not modify {@code messages}; return a new list. Do nothing else with side effects, apart from a
 *       model call that is part of the strategy.
 *   <li><b>Protected content stays.</b> The leading system messages, the first user message (the task, unless
 *       {@link CompactionRequest#protectTask()} is false), the last
 *       {@link CompactionRequest#keepRecent()} messages and every message {@link CompactionRequest#pinned()} accepts come back
 *       unchanged.
 *   <li><b>Pairs stay whole.</b> An assistant message with tool calls and the tool messages that answer it are kept or removed
 *       together.
 *   <li><b>Order is kept</b> among the messages that survive.
 *   <li><b>Never bigger.</b> {@link CompactionResult#of} enforces this: a result that is not smaller comes back as the input with
 *       outcome {@code NO_GAIN}.
 *   <li><b>Deterministic</b>, unless the strategy calls a model.
 *   <li><b>Idempotent in effect:</b> compacting an already compact result gains (almost) nothing more.
 *   <li><b>Marked.</b> A message you create carries {@code providerData} {@link Messages#COMPACTION} = your strategy's name.
 * </ol>
 */
@FunctionalInterface
public interface ContextCompactor {

    CompactionResult compact(List<Message> messages, CompactionRequest request);

    /** A short lower-case name for events and logs; a lambda is called {@code custom}. */
    default String name() {
        Class<?> c = getClass();
        String simple = c.getSimpleName();
        return c.isSynthetic() || c.isHidden() || simple.isEmpty() || simple.contains("$$") ? "custom" : simple;
    }
}
