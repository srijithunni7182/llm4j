package io.github.llm4j.tools.openapi;

import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.tools.ToolRefusal;
import java.util.ArrayList;
import java.util.List;

/**
 * The credential a tool puts on each request (phase 36a, D8): a key in a header or in the query,
 * given as a value, or as a {@link CredentialSource} the host hands over and which is asked for on
 * every request, so whatever refresh the host does is picked up. A source's value is never kept.
 *
 * <p>A credential from a source is somebody's own token: it is sent only over https, or to this
 * machine.
 *
 * @param header the header it goes in, or null
 * @param query the query parameter it goes in, or null
 * @param value the credential itself, or null when {@code source} supplies it
 * @param source where each request asks for it, or null
 */
record Credential(String header, String query, String value, CredentialSource source) {

    /** No credential. */
    static final Credential NONE = new Credential(null, null, null, null);

    /** Whether there is a credential to send. */
    boolean present() {
        return header != null || query != null;
    }

    /** What one request carries, and what must never be shown. */
    record Held(String name, String secret, boolean inQuery, List<String> toHide) {}

    /**
     * The credential for one request.
     *
     * @param secureTransport whether the request goes over https or to this machine
     * @throws ToolRefusal when a source's credential would go over plain http, or cannot be had; the
     *     message names the credential, never its value
     */
    Held hold(boolean secureTransport) {
        String secret = value;
        if (source != null) {
            if (!secureTransport)
                throw new ToolRefusal(
                        "a credential of the person running this workflow is only sent over https");
            try {
                secret = source.get();
            } catch (SecretException e) {
                throw new ToolRefusal("credential unavailable: " + e.getMessage());
            } catch (RuntimeException e) {
                // the cause's message is the host's own, and may hold anything
                throw new ToolRefusal(
                        "the credential could not be obtained (" + e.getClass().getSimpleName() + ")");
            }
            if (secret == null || secret.isBlank())
                throw new ToolRefusal("there is no credential for this tool");
        }
        List<String> hide = new ArrayList<>();
        hide.add(secret);
        int space = secret.indexOf(' ');
        if (space > 0) hide.add(secret.substring(space + 1)); // "Bearer <token>": the token alone
        return new Held(header != null ? header : query, secret, header == null, hide);
    }

    /** Tells the source, if there is one, that the server refused what it gave. */
    void rejected() {
        if (source != null) source.rejected();
    }

    /** Whether asking again could give a different value, so a refusal is worth repeating once. */
    boolean canRefresh() {
        return source != null;
    }
}
