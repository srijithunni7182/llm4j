package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Removes whole exchanges from the oldest end of the unprotected middle until the target is
 * reached, leaving one marker.
 */
final class DropOldest implements ContextCompactor {

    static final String NAME = "drop_oldest";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CompactionResult compact(List<Message> messages, CompactionRequest request) {
        if (messages.isEmpty())
            return CompactionResult.unchanged(
                    NAME,
                    messages,
                    request,
                    CompactionResult.Outcome.SKIPPED,
                    "nothing to compact");
        long size = request.sizeOf(messages);
        if (size <= request.targetTokens()) {
            return CompactionResult.unchanged(
                    NAME,
                    messages,
                    request,
                    CompactionResult.Outcome.SKIPPED,
                    "already within the target");
        }
        Exchanges.Region region =
                Exchanges.region(messages, request.keepRecent(), request.protectTask());
        List<Exchanges.Unit> removable = new ArrayList<>();
        for (Exchanges.Unit u : region.middle())
            if (!holdsPinned(messages, u, request)) removable.add(u);
        if (removable.isEmpty()) {
            return CompactionResult.unchanged(
                    NAME,
                    messages,
                    request,
                    CompactionResult.Outcome.NO_GAIN,
                    "nothing can be removed");
        }

        // a first guess from per-message sizes, then checked against the real size of the candidate
        long[] saving = new long[removable.size()];
        for (int i = 0; i < saving.length; i++)
            saving[i] =
                    request.sizeOf(
                            messages.subList(removable.get(i).start(), removable.get(i).end()));
        int k = 1;
        long saved = saving[0];
        while (k < removable.size() && size - saved + 20 > request.targetTokens())
            saved += saving[k++];

        List<Message> candidate = without(messages, removable, k);
        while (request.sizeOf(candidate) > request.targetTokens() && k < removable.size())
            candidate = without(messages, removable, ++k);
        return CompactionResult.of(NAME, messages, candidate, request);
    }

    private static boolean holdsPinned(
            List<Message> messages, Exchanges.Unit u, CompactionRequest request) {
        for (int i = u.start(); i < u.end(); i++)
            if (request.pinned().test(messages.get(i))) return true;
        return false;
    }

    private static List<Message> without(
            List<Message> messages, List<Exchanges.Unit> removable, int k) {
        return Exchanges.replacing(
                messages, removable, k, marker(Exchanges.standingFor(messages, removable, k)));
    }

    private static Message marker(int dropped) {
        return Message.builder()
                .role(Message.Role.USER)
                .content("[" + dropped + " earlier messages were removed to save space.]")
                .providerData(Map.of(Messages.COMPACTION, NAME, Messages.DROPPED, dropped))
                .build();
    }
}
