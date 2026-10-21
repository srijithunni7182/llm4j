package io.github.llm4j.loom.context;

import io.github.llm4j.context.CompactionRequest;
import io.github.llm4j.context.CompactionResult;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.context.ContextCompactorProvider;
import io.github.llm4j.context.ProviderContext;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Strategies of "a developer" for the .loct tests. They are public, with public constructors, as a
 * .loct class must be.
 */
public final class Strategies {
    private Strategies() {}

    public static final AtomicInteger calls = new AtomicInteger();

    /** Keeps the protected ends and the newest quarter of the rest; plain: no options. */
    public static class KeepNewest implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> messages, CompactionRequest request) {
            calls.incrementAndGet();
            int n = messages.size();
            List<Message> out = new ArrayList<>();
            int keepFrom = Math.max(1, n - Math.max(request.keepRecent(), n / 4));
            for (int i = 0; i < n; i++) if (i == 0 || i >= keepFrom) out.add(messages.get(i));
            return CompactionResult.of(this, messages, out, request);
        }

        @Override
        public String name() {
            return "keep_newest";
        }
    }

    /** Reads an option and a context. */
    public static class WithOptions implements ContextCompactorProvider {
        public static volatile Map<String, String> lastOptions;
        public static volatile ProviderContext lastContext;

        @Override
        public String name() {
            return "with_options";
        }

        @Override
        public ContextCompactor create(Map<String, String> options, ProviderContext context) {
            lastOptions = options;
            lastContext = context;
            if (options.containsKey("bad"))
                throw new IllegalArgumentException("with_options has no option 'bad'");
            return new KeepNewest();
        }
    }

    public static class Throws implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> m, CompactionRequest r) {
            throw new IllegalStateException("boom");
        }
    }

    /** Returns more than it was given. */
    public static class Grows implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> messages, CompactionRequest request) {
            List<Message> out = new ArrayList<>(messages);
            out.add(Message.user("x".repeat(5000)));
            return new CompactionResult(
                    out,
                    "grows",
                    CompactionResult.Outcome.COMPACTED,
                    messages.size(),
                    out.size(),
                    1,
                    2,
                    0,
                    null,
                    null);
        }
    }

    public static class NotAStrategy {}

    public static class NoDefaultConstructor implements ContextCompactor {
        public NoDefaultConstructor(String x) {}

        @Override
        public CompactionResult compact(List<Message> m, CompactionRequest r) {
            return CompactionResult.unchanged("n", m, r, CompactionResult.Outcome.SKIPPED, "");
        }
    }
}
