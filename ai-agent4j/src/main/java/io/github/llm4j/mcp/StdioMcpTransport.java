package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StdioMcpTransport implements McpTransport {
    private static final Logger logger = LoggerFactory.getLogger(StdioMcpTransport.class);

    private final List<String> command;
    private final Map<String, String> env;
    private final ObjectMapper objectMapper;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;

    private Consumer<JsonRpcMessage> messageHandler;
    private volatile boolean isRunning = false;

    public StdioMcpTransport(List<String> command, Map<String, String> env) {
        this.command = command;
        this.env = env;
        this.objectMapper = new ObjectMapper(); // Or pass in
    }

    @Override
    public void connect() throws IOException {
        logger.info("Starting MCP server with command: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        this.process = pb.start();
        this.writer =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.isRunning = true;

        this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    private void readLoop() {
        try {
            String line;
            while (isRunning && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                logger.debug("Received MCP message: {}", line);
                try {
                    // We need to parse polymorphically
                    JsonRpcMessage message = objectMapper.readValue(line, JsonRpcMessage.class);
                    if (messageHandler != null) {
                        messageHandler.accept(message);
                    }
                } catch (Exception e) {
                    logger.error("Failed to parse JSON-RPC message: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (isRunning) {
                logger.error("Error reading from MCP server process", e);
            }
        }
    }

    @Override
    public void sendMessage(JsonRpcMessage message) throws IOException {
        if (!isRunning || writer == null) {
            throw new IOException("Transport is not connected");
        }
        String json = objectMapper.writeValueAsString(message);
        logger.debug("Sending MCP message: {}", json);

        synchronized (this) {
            writer.write(json);
            writer.write("\n");
            writer.flush();
        }
    }

    @Override
    public void onMessage(Consumer<JsonRpcMessage> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void close() throws Exception {
        isRunning = false;
        if (process != null) {
            process.destroy();
            // Try to wait?
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
