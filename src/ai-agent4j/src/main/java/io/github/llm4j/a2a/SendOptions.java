package io.github.llm4j.a2a;

import java.util.List;

/**
 * How a send is to be made.
 *
 * @param returnImmediately that the caller does not want the call held until the task finishes or
 *     pauses; llm4j always sets it, because it never waits
 * @param acceptedOutputMediaTypes the media types the caller can take back; may be empty
 * @param historyLength how many history messages to return, or null for the remote's default
 */
public record SendOptions(
        boolean returnImmediately, List<String> acceptedOutputMediaTypes, Integer historyLength) {
    /** Copies the list. */
    public SendOptions {
        acceptedOutputMediaTypes =
                acceptedOutputMediaTypes == null
                        ? List.of()
                        : List.copyOf(acceptedOutputMediaTypes);
    }

    /** Options for a send that must not be held. */
    public static SendOptions immediate() {
        return new SendOptions(true, List.of(), null);
    }
}
