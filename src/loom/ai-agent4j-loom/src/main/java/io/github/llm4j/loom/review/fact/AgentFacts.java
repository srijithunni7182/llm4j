package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.review.model.Location;
import java.util.List;
import java.util.Locale;

/**
 * The {@code agent} entities. Facts that depend on tools and on steps are filled in by the
 * providers that run later.
 */
final class AgentFacts implements FactProvider {

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        String file = input.review().file();
        for (AgentDef a : input.review().script().getAgents()) {
            Entity e = out.add("agent", "agent:" + a.getName(), Location.at(file, a.getLine()));
            out.set(e, "name", a.getName());
            out.set(e, "line", (long) a.getLine());
            out.set(e, "model", ModelFacts.of(out, file, a.getModel(), a.getLine()));
            out.set(e, "approve", List.copyOf(a.getApprove()));
            out.set(e, "approveAll", a.isApproveAll());
            out.set(e, "hasSchema", a.getOutputSchema() != null);
            out.set(e, "hasContext", a.getContext() != null);
            out.set(e, "hasMemory", a.getMemory() != null);
            out.set(
                    e,
                    "hasMemoryLimit",
                    a.getMemory() != null && a.getMemory().getMaxTokens() != null);
            out.set(
                    e,
                    "guardPii",
                    word(
                            a.getGuard() == null ? null : a.getGuard().getPii(),
                            List.of("warn", "mask", "block")));
            out.set(
                    e,
                    "guardBias",
                    word(
                            a.getGuard() == null ? null : a.getGuard().getBias(),
                            List.of("warn", "block")));
            boolean file_ = a.getPrompt() != null && !a.getPrompt().isBlank();
            boolean inline = a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank();
            out.set(e, "promptSource", file_ ? "file" : inline ? "inline" : "none");
            out.set(
                    e,
                    "inlinePromptLines",
                    inline && !file_ ? (long) a.getSystemPrompt().split("\\R", -1).length : 0L);
            out.set(e, "streaming", a.isStreaming());
            out.set(e, "steerable", a.isSteerable());
        }
    }

    /**
     * A guard setting as one of the allowed words, {@code none} when absent, and unknown when it is
     * something else.
     */
    private static Object word(String value, List<String> allowed) {
        if (value == null || value.isBlank()) return "none";
        String v = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : Unknown.INSTANCE;
    }
}
