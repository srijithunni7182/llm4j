package io.github.llm4j.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The only way out of a sandbox with {@code network: allow} (phase 35, D6): an HTTP CONNECT proxy
 * on the JDK, bound to the address the container's network can reach and nothing else. It tunnels
 * to a listed host and port only; it resolves the name once, refuses loopback, private, link-local
 * (cloud metadata) and other internal addresses with {@link NetPolicy}'s rules even for a listed
 * name, and connects to the address it resolved, so a second answer from DNS cannot redirect it. It
 * sees hosts and ports, never paths: it does not intercept TLS.
 */
public final class EgressProxy implements AutoCloseable {

    /** Longest request head read before it is refused. */
    static final int MAX_HEAD = 8 * 1024;

    /** Most tunnels open at once. */
    static final int MAX_TUNNELS = 64;

    /** How long a client may take to send its request, and an idle tunnel may wait. */
    static final int TIMEOUT_MILLIS = 30_000;

    /**
     * Opens the connection to an address the proxy has checked. A seam: tests send it elsewhere.
     */
    @FunctionalInterface
    public interface Connector {
        Connector DIRECT = (address, port) -> new Socket(address, port);

        Socket connect(InetAddress address, int port) throws IOException;
    }

    /**
     * The hosts (and ports) a sandbox may reach: {@code pypi.org}, {@code *.example.com}, {@code
     * host:8443}.
     */
    public static final class Hosts {
        private static final Pattern NAME =
                Pattern.compile(
                        "(\\*\\.)?[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+");
        private final Set<String> entries;

        private Hosts(Set<String> entries) {
            this.entries = Set.copyOf(entries);
        }

        /** What is wrong with a {@code hosts:} list, or null. */
        public static String problem(String list) {
            if (list == null || list.isBlank())
                return "hosts: needs at least one host, e.g. \"pypi.org, files.pythonhosted.org\"";
            for (String part : list.split(",")) {
                String entry = part.strip().toLowerCase(Locale.ROOT);
                String host = entry;
                int colon = entry.lastIndexOf(':');
                if (colon > 0) {
                    host = entry.substring(0, colon);
                    try {
                        int port = Integer.parseInt(entry.substring(colon + 1));
                        if (port < 1 || port > 65535)
                            return "hosts: " + entry + " has a port outside 1 to 65535";
                    } catch (NumberFormatException e) {
                        return "hosts: " + entry + " is not host or host:port";
                    }
                }
                if (!NAME.matcher(host).matches() || host.matches("[0-9.]+"))
                    return "hosts: "
                            + entry
                            + " is not a host name (an address, a scheme or a bare * is not allowed)";
            }
            return null;
        }

        /** The list, checked; port 443 where none is given. */
        public static Hosts parse(String list) {
            String problem = problem(list);
            if (problem != null) throw new IllegalArgumentException(problem);
            Set<String> out = new LinkedHashSet<>();
            for (String part : list.split(",")) {
                String entry = part.strip().toLowerCase(Locale.ROOT);
                out.add(entry.lastIndexOf(':') > 0 ? entry : entry + ":443");
            }
            return new Hosts(out);
        }

        /** Whether a name and port are on the list; a wildcard covers subdomains only. */
        public boolean allows(String host, int port) {
            String h = host.toLowerCase(Locale.ROOT);
            for (String entry : entries) {
                int colon = entry.lastIndexOf(':');
                String pattern = entry.substring(0, colon);
                if (Integer.parseInt(entry.substring(colon + 1)) != port) continue;
                if (pattern.startsWith("*.")
                        ? h.endsWith(pattern.substring(1)) && h.length() > pattern.length() - 1
                        : h.equals(pattern)) return true;
            }
            return false;
        }

        /** The entries, for the audit. */
        public String describe() {
            return String.join(", ", entries);
        }
    }

    private final ServerSocket server;
    private final Hosts hosts;
    private final NetPolicy.Resolver resolver;
    private final Connector connector;
    private final ExecutorService workers;
    private final AtomicInteger open = new AtomicInteger();

    private EgressProxy(
            ServerSocket server, Hosts hosts, NetPolicy.Resolver resolver, Connector connector) {
        this.server = server;
        this.hosts = hosts;
        this.resolver = resolver;
        this.connector = connector;
        this.workers =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "egress-proxy");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /** Starts a proxy listening on {@code bind}:{@code port} (0 for any free port). */
    public static EgressProxy start(
            InetAddress bind,
            int port,
            Hosts hosts,
            NetPolicy.Resolver resolver,
            Connector connector)
            throws IOException {
        ServerSocket server = new ServerSocket(port, MAX_TUNNELS, bind);
        EgressProxy proxy = new EgressProxy(server, hosts, resolver, connector);
        proxy.workers.execute(proxy::accept);
        return proxy;
    }

    /** The port it listens on. */
    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                if (open.incrementAndGet() > MAX_TUNNELS) {
                    open.decrementAndGet();
                    client.close();
                    continue;
                }
                workers.execute(() -> serve(client));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(TIMEOUT_MILLIS);
            String head = head(client.getInputStream());
            String[] request = head == null ? new String[0] : head.split("\r\n", 2)[0].split(" ");
            if (request.length != 3 || !request[0].equals("CONNECT")) {
                reply(
                        client,
                        "405 Method Not Allowed",
                        "only CONNECT (HTTPS) goes through this proxy");
                return;
            }
            String target = request[1];
            int colon = target.lastIndexOf(':');
            int port;
            try {
                port = Integer.parseInt(target.substring(colon + 1));
            } catch (RuntimeException e) {
                reply(client, "400 Bad Request", "CONNECT host:port");
                return;
            }
            String host = colon > 0 ? target.substring(0, colon) : target;
            if (!hosts.allows(host, port)) {
                reply(client, "403 Forbidden", "not on the sandbox's hosts list");
                return;
            }
            InetAddress address = checked(host);
            if (address == null) {
                reply(
                        client,
                        "403 Forbidden",
                        "the host resolves to an address a sandbox may not reach");
                return;
            }
            try (Socket remote = connector.connect(address, port)) {
                remote.setSoTimeout(TIMEOUT_MILLIS);
                reply(client, "200 Connection Established", null);
                pump(client, remote);
            } catch (IOException e) {
                reply(client, "502 Bad Gateway", "could not connect");
            }
        } catch (IOException ignored) {
            // the client went away; nothing to tell anyone
        } finally {
            open.decrementAndGet();
        }
    }

    /** The name resolved once; null when it does not resolve or any answer is internal. */
    private InetAddress checked(String host) {
        try {
            List<InetAddress> all = resolver.resolve(host);
            if (all.isEmpty()) return null;
            for (InetAddress a : all)
                if (NetPolicy.forbidden(NetPolicy.unmap(a), false) != null) return null;
            return all.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    private static String head(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < MAX_HEAD) {
            int b = in.read();
            if (b < 0) return null;
            buffer.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) return buffer.toString(StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static void reply(Socket client, String status, String why) throws IOException {
        OutputStream out = client.getOutputStream();
        String body = why == null ? "" : why + "\n";
        out.write(
                ("HTTP/1.1 " + status + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                        .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Copies both ways until either side closes. */
    private void pump(Socket a, Socket b) throws IOException {
        List<Thread> both = new ArrayList<>();
        both.add(copy(a, b));
        both.add(copy(b, a));
        for (Thread t : both) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Thread copy(Socket from, Socket to) {
        Thread t =
                new Thread(
                        () -> {
                            try {
                                from.getInputStream().transferTo(to.getOutputStream());
                            } catch (IOException ignored) {
                                // one side closed or timed out
                            } finally {
                                try {
                                    to.shutdownOutput();
                                } catch (IOException | UnsupportedOperationException ignored) {
                                    // already closed
                                }
                            }
                        },
                        "egress-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void close() throws IOException {
        server.close();
        workers.shutdownNow();
    }
}
