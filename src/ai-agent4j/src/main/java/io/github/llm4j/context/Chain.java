package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Applies strategies in order, stopping as soon as the target is reached. */
final class Chain implements ContextCompactor {

    private final List<ContextCompactor> stages;
    private final String name;

    Chain(List<ContextCompactor> stages) {
        if (stages.isEmpty())
            throw new IllegalArgumentException("chain needs at least one strategy");
        for (ContextCompactor c : stages)
            Objects.requireNonNull(c, "a strategy in the chain is null");
        this.stages = List.copyOf(stages);
        this.name =
                "chain("
                        + this.stages.stream()
                                .map(ContextCompactor::name)
                                .collect(Collectors.joining(", "))
                        + ")";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CompactionResult compact(List<Message> messages, CompactionRequest request) {
        if (request.sizeOf(messages) <= request.targetTokens()) {
            return CompactionResult.unchanged(
                    name,
                    messages,
                    request,
                    CompactionResult.Outcome.SKIPPED,
                    "already within the target");
        }
        List<Message> current = messages;
        CompactionUsage spent = null;
        for (ContextCompactor stage : stages) {
            if (request.sizeOf(current) <= request.targetTokens()) break;
            CompactionResult step = stage.compact(current, request);
            current = step.messages();
            spent = CompactionUsage.sum(spent, step.summarizerUsage());
        }
        CompactionResult result = CompactionResult.of(name, messages, current, request);
        return spent == null ? result : result.withUsage(spent);
    }
}
