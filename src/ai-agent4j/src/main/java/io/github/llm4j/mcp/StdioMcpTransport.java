package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local MCP server started as a program and spoken to over its standard input and output, one
 * JSON message per line.
 *
 * <p>Two ways to build one. {@link #StdioMcpTransport(List, Map)} is the original: the server gets
 * the whole environment of this process, and its error stream goes to ours. {@link #scrubbed} is
 * the safe one: the server gets only the variables it is given, its error stream is captured,
 * redacted and bounded, and nothing is inherited by omission.
 */
public class StdioMcpTransport implements McpTransport {
    private static final Logger logger = LoggerFactory.getLogger(StdioMcpTransport.class);

    /** How much of the server's error stream is kept: the most recent part. */
    public static final int STDERR_TAIL_CHARS = 8192;

    private static final int READ_CHUNK = 4096;
    private static final int LINE_LIMIT = 16384;
    private static final int OVERLAP = 512;
    private static final long STOP_WAIT_SECONDS = 2;

    private final List<String> command;
    private final Map<String, String> env;
    private final boolean scrub;
    private final Set<String> inherit;
    private final UnaryOperator<String> redactor;
    private final ObjectMapper objectMapper;
    private final StringBuilder stderrTail = new StringBuilder();

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private Thread errorThread;

    private Consumer<JsonRpcMessage> messageHandler;
    private Consumer<String> closeHandler;
    private volatile boolean isRunning = false;
    private volatile boolean closedByUs = false;

    /**
     * The server gets this process's whole environment plus {@code env}, and its error stream is
     * shared with ours. Kept for existing callers; prefer {@link #scrubbed}.
     *
     * @param command the program and its arguments, each one argument
     * @param env variables added to the inherited environment, or null
     */
    public StdioMcpTransport(List<String> command, Map<String, String> env) {
        this(command, env, false, Set.of(), s -> s);
    }

    private StdioMcpTransport(
            List<String> command,
            Map<String, String> env,
            boolean scrub,
            Set<String> inherit,
            UnaryOperator<String> redactor) {
        this.command = List.copyOf(Objects.requireNonNull(command, "command"));
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.scrub = scrub;
        this.inherit = Set.copyOf(inherit);
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * A server started with a scrubbed environment: nothing is inherited from this process except
     * the variables named in {@code inherit}; {@code env} is added on top.
     *
     * @param command the program and its arguments, each one argument (never split)
     * @param env the variables the server is given, such as its credentials
     * @param inherit names of variables copied from this process (for example {@code PATH})
     * @param redactor removes secrets from the error stream before it is kept
     * @return the transport, not yet connected
     */
    public static StdioMcpTransport scrubbed(
            List<String> command,
            Map<String, String> env,
            Set<String> inherit,
            UnaryOperator<String> redactor) {
        return new StdioMcpTransport(command, env, true, inherit, redactor);
    }

    @Override
    public void connect() throws IOException {
        logger.info("Starting MCP server: {}", command.get(0));
        ProcessBuilder pb = new ProcessBuilder(command);
        if (scrub) {
            pb.environment().clear();
            for (String name : inherit) {
                String value = System.getenv(name);
                if (value != null) pb.environment().put(name, value);
            }
        }
        pb.environment().putAll(env);
        if (scrub) pb.redirectError(ProcessBuilder.Redirect.PIPE);
        else pb.redirectError(ProcessBuilder.Redirect.INHERIT);

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
        if (scrub) {
            this.errorThread = new Thread(this::drainErrors, "mcp-stdio-stderr");
            this.errorThread.setDaemon(true);
            this.errorThread.start();
        }
    }

    /**
     * The most recent part of the server's error stream, redacted: for a trace or {@code weave why}
     * when a call failed. Empty for a transport built with the public constructor.
     */
    public String stderrTail() {
        synchronized (stderrTail) {
            return stderrTail.toString();
        }
    }

    private void drainErrors() {
        StringBuilder pending = new StringBuilder();
        char[] chunk = new char[READ_CHUNK];
        try (Reader in = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                pending.append(chunk, 0, n);
                int newline;
                while ((newline = pending.indexOf("\n")) >= 0) {
                    keep(pending.substring(0, newline + 1));
                    pending.delete(0, newline + 1);
                }
                // a line that never ends is flushed in pieces, keeping an overlap so a secret
                // straddling the cut is still seen whole by the redactor
                if (pending.length() > LINE_LIMIT) {
                    int cut = pending.length() - OVERLAP;
                    keep(pending.substring(0, cut));
                    pending.delete(0, cut);
                }
            }
        } catch (IOException e) {
            logger.debug("MCP server error stream closed: {}", e.getClass().getSimpleName());
        }
        if (pending.length() > 0) keep(pending.toString());
    }

    private void keep(String text) {
        String safe = redactor.apply(text);
        synchronized (stderrTail) {
            stderrTail.append(safe);
            int excess = stderrTail.length() - STDERR_TAIL_CHARS;
            if (excess > 0) stderrTail.delete(0, excess);
        }
    }

    private void readLoop() {
        try {
            String line;
            while (isRunning && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                logger.debug("Received an MCP message of {} characters", line.length());
                try {
                    // We need to parse polymorphically
                    JsonRpcMessage message = objectMapper.readValue(line, JsonRpcMessage.class);
                    if (messageHandler != null) {
                        messageHandler.accept(message);
                    }
                } catch (Exception e) {
                    // the line is the server's output and may hold anything: say only that it
                    // could not be read
                    logger.error(
                            "Failed to parse a JSON-RPC message of {} characters", line.length());
                }
            }
        } catch (IOException e) {
            if (isRunning) {
                logger.error("Error reading from MCP server process", e);
            }
        }
        if (!closedByUs && closeHandler != null) closeHandler.accept("the server process exited");
    }

    @Override
    public void sendMessage(JsonRpcMessage message) throws IOException {
        if (!isRunning || writer == null) {
            throw new IOException("Transport is not connected");
        }
        String json = objectMapper.writeValueAsString(message);

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
    public void onClose(Consumer<String> handler) {
        this.closeHandler = handler;
    }

    @Override
    public void close() throws Exception {
        closedByUs = true;
        isRunning = false;
        if (process != null) {
            process.destroy();
            if (!process.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
