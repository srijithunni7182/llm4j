package io.github.llm4j.model;

import io.github.llm4j.exception.InvalidRequestException;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which kinds of {@link Media} a model can take. {@link #NONE} unless it is recorded that it can:
 * an unknown model is never assumed to see, hear or read anything, so a request that carries media
 * fails before a call is paid for instead of being answered about content the model never got.
 */
public final class MediaCapabilities {

    /** Takes no media. */
    public static final MediaCapabilities NONE =
            new MediaCapabilities(EnumSet.noneOf(MediaKind.class));

    private final Set<MediaKind> kinds;

    private MediaCapabilities(Set<MediaKind> kinds) {
        this.kinds = Set.copyOf(kinds);
    }

    /** Takes these kinds. */
    public static MediaCapabilities of(MediaKind first, MediaKind... more) {
        EnumSet<MediaKind> all = EnumSet.of(first, more);
        return new MediaCapabilities(all);
    }

    /** The kinds it takes. */
    public Set<MediaKind> kinds() {
        return kinds;
    }

    /** Whether it takes this kind. */
    public boolean accepts(MediaKind kind) {
        return kinds.contains(kind);
    }

    /** What both take: the capabilities of something that may be served by either. */
    public MediaCapabilities and(MediaCapabilities other) {
        EnumSet<MediaKind> both = EnumSet.noneOf(MediaKind.class);
        both.addAll(kinds);
        both.retainAll(other.kinds);
        return new MediaCapabilities(both);
    }

    /**
     * Refuses a request that carries media this cannot take, before anything is sent.
     *
     * @param provider the provider's name, for the message
     * @param request the request about to be sent
     * @throws InvalidRequestException naming each kind that is refused and what is accepted
     */
    public void require(String provider, LLMRequest request) {
        Set<MediaKind> refused = EnumSet.noneOf(MediaKind.class);
        refused.addAll(request.mediaKinds());
        refused.removeAll(kinds);
        if (refused.isEmpty()) return;
        String model = request.getModel() == null ? "the default model" : request.getModel();
        throw new InvalidRequestException(
                provider
                        + " "
                        + model
                        + " does not accept "
                        + list(refused)
                        + "; "
                        + (kinds.isEmpty() ? "it takes no media" : "accepted: " + list(kinds))
                        + ". Send the request without it, or use a model that takes it");
    }

    private static String list(Set<MediaKind> some) {
        return some.stream().sorted().map(MediaKind::label).collect(Collectors.joining(", "));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MediaCapabilities that && kinds.equals(that.kinds);
    }

    @Override
    public int hashCode() {
        return kinds.hashCode();
    }

    @Override
    public String toString() {
        return "MediaCapabilities" + kinds;
    }
}
