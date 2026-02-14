package io.github.llm4j.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class McpClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(McpClient.class);
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    private final McpTransport transport;
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final Map<Object, CompletableFuture<JsonRpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    private boolean initialized = false;
    private Map<String, Object> serverCapabilities;
    private Map<String, Object> serverInfo;

    public McpClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onMessage(this::handleMessage);
    }

    public void initialize() throws Exception {
        transport.connect();

        // 1. Send initialize request
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Collections.emptyMap()); // Client capabilities
        params.put("clientInfo", Map.of("name", "ai-agent4j", "version", "0.1.0"));

        JsonRpcResponse response = sendRequest("initialize", params);

        if (response.isError()) {
            throw new RuntimeException("MCP Initialization failed: " + response.getError().getMessage());
        }

        // Parse result
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        this.serverInfo = (Map<String, Object>) result.get("serverInfo");
        this.serverCapabilities = (Map<String, Object>) result.get("capabilities");

        logger.info("MCP Server Initialized: {}", serverInfo);

        // 2. Send initialized notification
        sendNotification("notifications/initialized", Collections.emptyMap());
        this.initialized = true;
    }

    public List<Map<String, Object>> listTools() throws Exception {
        ensureInitialized();
        JsonRpcResponse response = sendRequest("tools/list", Collections.emptyMap());
        if (response.isError()) {
            throw new RuntimeException("Failed to list tools: " + response.getError().getMessage());
        }

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        return (List<Map<String, Object>>) result.get("tools");
    }

    public Object callTool(String name, Map<String, Object> arguments) throws Exception {
        ensureInitialized();
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("arguments", arguments);

        JsonRpcResponse response = sendRequest("tools/call", params);
        if (response.isError()) {
            throw new RuntimeException("Tool execution failed: " + response.getError().getMessage());
        }

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        // Result usually contains "content" list. We might want to return that directly
        // or process it.
        // For simplicity, returning the raw result map for now, adapter can handle
        // extraction.
        return result;
    }

    private JsonRpcResponse sendRequest(String method, Object params) throws Exception {
        long id = requestIdCounter.incrementAndGet();
        JsonRpcRequest request = new JsonRpcRequest(method, params, id);

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            transport.sendMessage(request);
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new RuntimeException("MCP Request timed out: " + method);
        } catch (Exception e) {
            pendingRequests.remove(id);
            throw e;
        }
    }

    private void sendNotification(String method, Object params) throws IOException {
        JsonRpcNotification notification = new JsonRpcNotification(method, params);
        transport.sendMessage(notification);
    }

    private void handleMessage(JsonRpcMessage message) {
        if (message instanceof JsonRpcResponse) {
            JsonRpcResponse response = (JsonRpcResponse) message;
            Object id = response.getId();
            // id might be Integer in JSON but Long in our counter, handle conversion
            // carefully if needed.
            // Jackson might deserialize number as Integer or Long.
            // Let's assume standard behavior or try to match.
            // Ideally we normalize ID to Long or String. Here we cast/check.

            CompletableFuture<JsonRpcResponse> future = null;

            // Try explicit lookup, if id is Number, convert to Long
            if (id instanceof Number) {
                future = pendingRequests.remove(((Number) id).longValue());
            } else {
                future = pendingRequests.remove(id);
            }

            if (future != null) {
                future.complete(response);
            } else {
                logger.warn("Received response for unknown Request ID: {}", id);
            }
        } else if (message instanceof JsonRpcNotification) {
            JsonRpcNotification notification = (JsonRpcNotification) message;
            logger.info("Received notification: {}", notification.getMethod());
            // Log or handle specific notifications
        } else if (message instanceof JsonRpcRequest) {
            // Server sending request to client (e.g. sampling). Not implemented yet.
            logger.warn("Received request from server, not implemented: {}", ((JsonRpcRequest) message).getMethod());
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("McpClient is not initialized");
        }
    }

    @Override
    public void close() throws Exception {
        transport.close();
    }
}
