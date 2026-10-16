package io.github.llm4j.spring;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * No default-build test may talk to a host other than this machine (V14.1). HTTP clients ask the default {@link ProxySelector} where to connect; this one
 * records any request for a non-loopback host, and the test that made it fails.
 */
public final class NoNetwork implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    private static final List<String> OUTSIDE = new CopyOnWriteArrayList<>();
    private static boolean installed;

    @Override
    public void beforeAll(ExtensionContext context) {
        synchronized (NoNetwork.class) {
            if (installed) return;
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    String host = uri == null ? null : uri.getHost();
                    if (host != null && !host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("::1") && !host.equals("[::1]")) OUTSIDE.add(uri.toString());
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
            });
            installed = true;
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        OUTSIDE.clear();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (!OUTSIDE.isEmpty()) {
            List<String> seen = List.copyOf(OUTSIDE);
            OUTSIDE.clear();
            throw new AssertionError("a test reached outside this machine: " + seen);
        }
    }
}
