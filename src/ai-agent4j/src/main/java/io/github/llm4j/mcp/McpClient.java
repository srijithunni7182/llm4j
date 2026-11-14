package io.github.llm4j.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A client for one MCP server: the {@code initialize} handshake, {@code tools/list} (all pages) and
 * {@code tools/call}.
 *
 * <p>A call whose result says {@code isError} is a failure ({@link McpToolException}); a request
 * that is not answered in time is an {@link McpTimeoutException}; a server that exits is an {@link
 * McpServerExitedException}. Built with {@link #restarting}, the client starts a fresh server for
 * the next call after one exits, up to a limit, and never repeats the call that was in flight: it
 * may have been carried out.
 *
 * <p>Everything a server sends, its tool descriptions, annotations and results, is untrusted.
 */
public class McpClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(McpClient.class);

    /** The protocol revision this client offers. */
    public static final String OFFERED_VERSION = "2025-11-25";

    /** The revisions of the {@code initialize} era the client will speak, newest first. */
    public static final List<String> KNOWN_VERSIONS =
            List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /** The longest a request waits when no other time is given. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** The most pages of {@code tools/list} that are read. */
    static final int MAX_PAGES = 50;

    private final Supplier<McpTransport> factory;
    private final int maxRestarts;
    private final Duration timeout;
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final Map<Object, CompletableFuture<JsonRpcResponse>> pendingRequests =
            new ConcurrentHashMap<>();

    private McpTransport transport;
    private volatile boolean initialized = false;
    private volatile boolean exited = false;
    private int restarts = 0;
    private String negotiatedVersion;
    private Map<String, Object> serverCapabilities;
    private Map<String, Object> serverInfo;

    /** A client with the default timeout, for a transport that is not restarted. */
    public McpClient(McpTransport transport) {
        this(transport, DEFAULT_TIMEOUT);
    }

    /**
     * @param transport the connection, not yet connected
     * @param timeout how long one request waits for its answer
     */
    public McpClient(McpTransport transport, Duration timeout) {
        this(Objects.requireNonNull(transport, "transport"), null, 0, timeout);
    }

    private McpClient(
            McpTransport transport,
            Supplier<McpTransport> factory,
            int maxRestarts,
            Duration timeout) {
        this.transport = transport;
        this.factory = factory;
        this.maxRestarts = maxRestarts;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        attach(transport);
    }

    /**
     * A client that starts a fresh server for the next call after one exits.
     *
     * @param factory makes a new, unconnected transport; called once now and once per restart
     * @param maxRestarts how many times a server is restarted before the client gives up
     * @param timeout how long one request waits for its answer
     * @return the client, not yet initialized
     */
    public static McpClient restarting(
            Supplier<McpTransport> factory, int maxRestarts, Duration timeout) {
        Objects.requireNonNull(factory, "factory");
        if (maxRestarts < 0) throw new IllegalArgumentException("maxRestarts cannot be negative");
        return new McpClient(factory.get(), factory, maxRestarts, timeout);
    }

    private void attach(McpTransport t) {
        t.onMessage(this::handleMessage);
        t.onClose(reason -> serverExited(t, reason));
    }

    /** Starts the server and completes the handshake. */
    public void initialize() throws Exception {
        transport.connect();
        handshake();
    }

    private void handshake() throws Exception {
        exited = false;
        initialized = false;
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", OFFERED_VERSION);
        params.put("capabilities", Collections.emptyMap()); // Client capabilities
        params.put("clientInfo", Map.of("name", "ai-agent4j", "version", "0.1.0"));

        JsonRpcResponse response = sendRequest("initialize", params, "initialize");

        if (response.isError()) {
            throw new McpException(
                    "MCP Initialization failed: " + response.getError().getMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        Object offered = result.get("protocolVersion");
        if (offered != null && !KNOWN_VERSIONS.contains(String.valueOf(offered))) {
            throw new McpException(
                    "the MCP server answered with protocol version "
                            + offered
                            + ", which this client does not speak (it speaks "
                            + String.join(", ", KNOWN_VERSIONS)
                            + ")");
        }
        this.negotiatedVersion = offered == null ? OFFERED_VERSION : String.valueOf(offered);
        this.serverInfo = castMap(result.get("serverInfo"));
        this.serverCapabilities = castMap(result.get("capabilities"));

        logger.info("MCP Server Initialized: {}", serverInfo);

        // 2. Send initialized notification
        sendNotification("notifications/initialized", Collections.emptyMap());
        this.initialized = true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }

    /** The protocol revision agreed with the server. */
    public String negotiatedVersion() {
        return negotiatedVersion;
    }

    /**
     * Every tool the server lists, across pages.
     *
     * @throws McpException when the server does not list, or never stops paging
     */
    public List<Map<String, Object>> listTools() throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 1; ; page++) {
            ensureReady();
            Map<String, Object> params = new HashMap<>();
            if (cursor != null) params.put("cursor", cursor);
            JsonRpcResponse response = sendRequest("tools/list", params, "tools/list");
            if (response.isError()) {
                throw new McpException("Failed to list tools: " + response.getError().getMessage());
            }
            Map<String, Object> result = castMap(response.getResult());
            Object tools = result == null ? null : result.get("tools");
            if (!(tools instanceof List<?> list)) {
                throw new McpException("the MCP server's tools/list answer has no list of tools");
            }
            for (Object tool : list) {
                Map<String, Object> toolMap = castMap(tool);
                if (toolMap != null) all.add(toolMap);
            }
            Object next = result.get("nextCursor");
            if (next == null || String.valueOf(next).isEmpty()) return all;
            cursor = String.valueOf(next);
            if (!cursors.add(cursor) || page >= MAX_PAGES) {
                throw new McpException(
                        "the MCP server's list of tools did not end: it kept offering pages (stopped after "
                                + page
                                + " pages)");
            }
        }
    }

    /**
     * Calls a tool.
     *
     * @return the result map the server sent (its {@code content} and, when present, {@code
     *     structuredContent})
     * @throws McpToolException when the result says {@code isError}
     */
    public Object callTool(String name, Map<String, Object> arguments) throws Exception {
        ensureReady();
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("arguments", arguments);

        JsonRpcResponse response = sendRequest("tools/call", params, "tools/call " + name);
        if (response.isError()) {
            throw new McpException("Tool execution failed: " + response.getError().getMessage());
        }

        Map<String, Object> result = castMap(response.getResult());
        if (result != null && Boolean.TRUE.equals(result.get("isError"))) {
            throw new McpToolException(errorText(result));
        }
        return result;
    }

    /** The text of an error result: its text content, joined, or a plain statement. */
    private static String errorText(Map<String, Object> result) {
        StringBuilder text = new StringBuilder();
        if (result.get("content") instanceof List<?> content) {
            for (Object item : content) {
                if (item instanceof Map<?, ?> m
                        && "text".equals(m.get("type"))
                        && m.get("text") != null) {
                    if (text.length() > 0) text.append('\n');
                    text.append(m.get("text"));
                }
            }
        }
        return text.length() == 0 ? "the tool reported an error and gave no text" : text.toString();
    }

    private JsonRpcResponse sendRequest(String method, Object params, String label)
            throws Exception {
        long id = requestIdCounter.incrementAndGet();
        JsonRpcRequest request = new JsonRpcRequest(method, params, id);

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            transport.sendMessage(request);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new McpTimeoutException(
                    "MCP request timed out after " + timeout.toMillis() + " ms: " + label);
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        } catch (IOException e) {
            pendingRequests.remove(id);
            if (exited) throw exitedDuring(label);
            throw e;
        }
    }

    private static McpServerExitedException exitedDuring(String label) {
        return new McpServerExitedException(
                "the MCP server exited during "
                        + label
                        + "; the call may have been carried out, so it is not repeated");
    }

    private void sendNotification(String method, Object params) throws IOException {
        JsonRpcNotification notification = new JsonRpcNotification(method, params);
        transport.sendMessage(notification);
    }

    private synchronized void ensureReady() throws Exception {
        if (exited) {
            if (factory == null) {
                throw new McpServerExitedException(
                        "the MCP server has exited and this client does not restart it");
            }
            if (restarts >= maxRestarts) {
                throw new McpServerExitedException(
                        "the MCP server exited and was restarted "
                                + restarts
                                + " time(s) already; it is not restarted again");
            }
            restarts++;
            logger.warn("Restarting the MCP server ({} of {})", restarts, maxRestarts);
            try {
                transport.close();
            } catch (Exception e) {
                logger.debug("Closing the exited MCP server: {}", e.getClass().getSimpleName());
            }
            transport = factory.get();
            attach(transport);
            transport.connect();
            handshake();
        }
        if (!initialized) {
            throw new IllegalStateException("McpClient is not initialized");
        }
    }

    private void serverExited(McpTransport from, String reason) {
        if (from != transport) return; // an earlier server, already replaced
        exited = true;
        logger.warn("MCP server connection ended: {}", reason);
        McpServerExitedException failure = exitedDuring("a request");
        pendingRequests.values().forEach(f -> f.completeExceptionally(failure));
        pendingRequests.clear();
    }

    private void handleMessage(JsonRpcMessage message) {
        if (message instanceof JsonRpcResponse) {
            JsonRpcResponse response = (JsonRpcResponse) message;
            Object id = response.getId();
            // JSON numbers come back as Integer or Long; the counter is a Long
            CompletableFuture<JsonRpcResponse> future =
                    id instanceof Number
                            ? pendingRequests.remove(((Number) id).longValue())
                            : pendingRequests.remove(id);

            if (future != null) {
                future.complete(response);
            } else {
                logger.warn("Received response for unknown Request ID: {}", id);
            }
        } else if (message instanceof JsonRpcNotification) {
            JsonRpcNotification notification = (JsonRpcNotification) message;
            logger.info("Received notification: {}", notification.getMethod());
        } else if (message instanceof JsonRpcRequest) {
            // Server sending request to client (e.g. sampling). Not implemented.
            logger.warn(
                    "Received request from server, not implemented: {}",
                    ((JsonRpcRequest) message).getMethod());
        }
    }

    @Override
    public void close() throws Exception {
        transport.close();
    }
}
