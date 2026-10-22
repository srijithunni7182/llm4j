package io.github.llm4j.loom.inspect.emit;

import java.io.IOException;

/**
 * One request to a Fabric server. Signing and TLS are the implementation's business; the link only
 * knows paths and bodies.
 */
public interface Transport {

    /**
     * @param retryAfterSeconds from a {@code Retry-After} header, or 0
     */
    record Reply(int status, String body, long retryAfterSeconds) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Throws {@link IOException} when the server cannot be reached; any answer, even an error
     * status, is a {@link Reply}.
     */
    Reply post(String path, String body) throws IOException;

    /**
     * As {@link #post}, for an answer that carries orders: an answer not signed by the server with
     * this process's key is an {@link IOException}.
     */
    default Reply postVerified(String path, String body) throws IOException {
        return post(path, body);
    }
}
