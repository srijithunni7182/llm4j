package io.github.llm4j.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;

/** How {@link CdpBrowser} talks to the browser: {@link Cdp} on a real socket, or a script in tests. */
interface Channel extends AutoCloseable {

    /** Sends a command and waits for its answer. */
    JsonNode call(String method, ObjectNode params, String session, Duration timeout) throws IOException;

    /** Sends a command without waiting for it. */
    void post(String method, ObjectNode params, String session);

    @Override
    void close();
}
