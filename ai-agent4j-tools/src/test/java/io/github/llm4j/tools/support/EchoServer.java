package io.github.llm4j.tools.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A server that answers every request with a copy of what it received (request line, headers, body), in
 * the body or, for a path under /error, in a 500's body, so a test can check that nothing secret comes back.
 */
public final class EchoServer implements AutoCloseable {

    public final MockWebServer server = new MockWebServer();
    public final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    public EchoServer() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                requests.add(r);
                String echo = r.getRequestLine() + "\n" + r.getHeaders() + "\n" + r.getBody().clone().readUtf8();
                boolean error = r.getPath() != null && r.getPath().startsWith("/error");
                return new MockResponse().setResponseCode(error ? 500 : 200).setHeader("Content-Type", "text/plain").setBody(echo);
            }
        });
        server.start();
    }

    public String url(String path) {
        return server.url(path).toString();
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
