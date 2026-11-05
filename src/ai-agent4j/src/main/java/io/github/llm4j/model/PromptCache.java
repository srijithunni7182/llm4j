package io.github.llm4j.model;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The stable parts of a request that a provider may keep in its prompt cache, so the next call that
 * starts the same way is billed less for them.
 *
 * <p>A part is cached up to its end: {@link Builder#system()} the system prompt, {@link
 * Builder#tools()} the tool list, {@link Builder#throughMessage(int)} everything up to the end of
 * one message of the request (by its index in {@link LLMRequest#getMessages()}). Choose the end of
 * what does not change between calls; what comes after is sent at the normal price.
 *
 * <p>This is a hint. Anthropic caches only what is marked, and bills a write above the normal input
 * price, so a one-shot call should not ask. OpenAI and Google cache a long prefix by themselves and
 * ignore this. A provider that does not cache ignores it too. Caching changes what a call costs,
 * never what it answers.
 */
public final class PromptCache {

    /** The most breakpoints the Messages API accepts on one request. */
    public static final int MAX_BREAKPOINTS = 4;

    private final boolean system;
    private final boolean tools;
    private final List<Integer> throughMessages;

    private PromptCache(Builder builder) {
        this.system = builder.system;
        this.tools = builder.tools;
        this.throughMessages = List.copyOf(builder.throughMessages);
        if (breakpoints() > MAX_BREAKPOINTS) {
            throw new IllegalArgumentException(
                    "a request can have at most "
                            + MAX_BREAKPOINTS
                            + " cache breakpoints, got "
                            + breakpoints()
                            + "; mark fewer parts");
        }
    }

    /** Whether the system prompt is cached. */
    public boolean system() {
        return system;
    }

    /** Whether the tool list is cached. */
    public boolean tools() {
        return tools;
    }

    /** The indices of the messages that end a cached part, in ascending order. */
    public List<Integer> throughMessages() {
        return throughMessages;
    }

    /** How many parts are marked; each costs one of the vendor's {@value #MAX_BREAKPOINTS}. */
    public int breakpoints() {
        return (system ? 1 : 0) + (tools ? 1 : 0) + throughMessages.size();
    }

    /** A builder that asks for nothing until told to. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PromptCache that)) return false;
        return system == that.system
                && tools == that.tools
                && throughMessages.equals(that.throughMessages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(system, tools, throughMessages);
    }

    @Override
    public String toString() {
        return "PromptCache{system="
                + system
                + ", tools="
                + tools
                + ", throughMessages="
                + throughMessages
                + '}';
    }

    /** Builds a {@link PromptCache}. */
    public static final class Builder {
        private boolean system;
        private boolean tools;
        private final TreeSet<Integer> throughMessages = new TreeSet<>();

        private Builder() {}

        /** Caches the system prompt. */
        public Builder system() {
            this.system = true;
            return this;
        }

        /** Caches the tool list. */
        public Builder tools() {
            this.tools = true;
            return this;
        }

        /**
         * Caches everything up to the end of one message.
         *
         * @param index the message's position in the request, from 0
         */
        public Builder throughMessage(int index) {
            if (index < 0) {
                throw new IllegalArgumentException("a message index cannot be negative: " + index);
            }
            throughMessages.add(index);
            return this;
        }

        /**
         * Builds it.
         *
         * @throws IllegalArgumentException when more than {@value #MAX_BREAKPOINTS} parts are
         *     marked
         */
        public PromptCache build() {
            return new PromptCache(this);
        }
    }
}
