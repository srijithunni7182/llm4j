package io.github.llm4j.context;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The built-in strategies, registered through the same {@code ServiceLoader} path as a developer's own. Not for direct use. */
public final class BuiltInProviders {

    private BuiltInProviders() {}

    static void noOptions(String strategy, Map<String, String> options) {
        if (!options.isEmpty()) {
            throw new IllegalArgumentException(strategy + " takes no options, got: " + String.join(", ", options.keySet()));
        }
    }

    public static final class None implements ContextCompactorProvider {
        @Override
        public String name() {
            return NoCompaction.NAME;
        }

        @Override
        public ContextCompactor create(Map<String, String> options, ProviderContext context) {
            noOptions(name(), options);
            return ContextCompactors.none();
        }
    }

    public static final class DropOldestProvider implements ContextCompactorProvider {
        @Override
        public String name() {
            return DropOldest.NAME;
        }

        @Override
        public ContextCompactor create(Map<String, String> options, ProviderContext context) {
            noOptions(name(), options);
            return ContextCompactors.dropOldest();
        }
    }

    public static final class TrimToolResultsProvider implements ContextCompactorProvider {
        static final int DEFAULT_MAX_CHARS = 2_000;

        @Override
        public String name() {
            return TrimToolResults.NAME;
        }

        @Override
        public ContextCompactor create(Map<String, String> options, ProviderContext context) {
            for (String key : options.keySet()) {
                if (!key.equals("max_chars")) {
                    throw new IllegalArgumentException(name() + " has no option '" + key + "'; its option is max_chars");
                }
            }
            int max = DEFAULT_MAX_CHARS;
            String text = options.get("max_chars");
            if (text != null) {
                try {
                    max = Integer.parseInt(text.strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name() + " option max_chars: '" + text + "' is not a whole number");
                }
            }
            return ContextCompactors.trimToolResults(max);
        }
    }

    public static final class ChainProvider implements ContextCompactorProvider {
        @Override
        public String name() {
            return "chain";
        }

        @Override
        public ContextCompactor create(Map<String, String> options, ProviderContext context) {
            for (String key : options.keySet()) {
                if (!key.equals("strategies")) throw new IllegalArgumentException("chain has no option '" + key + "'; its option is strategies");
            }
            String list = options.get("strategies");
            if (list == null || list.isBlank()) {
                throw new IllegalArgumentException("chain needs the option 'strategies', such as strategies = trim_tool_results, drop_oldest");
            }
            List<ContextCompactor> stages = Arrays.stream(list.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .map(s -> ContextCompactors.byName(s, Map.of(), context))
                    .toList();
            return ContextCompactors.chain(stages.toArray(new ContextCompactor[0]));
        }
    }
}
