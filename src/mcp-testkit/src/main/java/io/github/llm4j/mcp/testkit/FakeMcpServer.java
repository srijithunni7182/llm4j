package io.github.llm4j.mcp.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A real MCP server over stdio for the tests: a separate JVM, real newline-delimited framing, real
 * stdin and stdout, and a real exit. It speaks the 2025-11-25 revision (the {@code initialize}
 * handshake) and logs every line it reads and writes to a file, so a test can check each against
 * the official schema.
 *
 * <p>Arguments: {@code --log FILE}, {@code --page N} (tools per {@code tools/list} page), {@code
 * --endless} (every page has a next page), {@code --fail-list} ({@code tools/list} answers with an
 * error), {@code --answer VERSION} (the protocol version it
 * answers with), {@code --stderr TEXT} (written to the error stream at start), {@code --flood
 * BYTES} (that much written to the error stream), then {@code --} and anything after it, which the
 * {@code argv} tool reports.
 */
public final class FakeMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeMcpServer() {}

    /**
     * The command line that starts this server as a separate JVM on the JDK running the test, with
     * the test's own class path, and {@code args} as its arguments.
     */
    public static List<String> command(String... args) {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                FakeMcpServer.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    public static void main(String[] args) throws Exception {
        Path log = null;
        int page = 0;
        boolean endless = false;
        boolean failList = false;
        String answer = "2025-11-25";
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--log" -> log = Path.of(args[++i]);
                case "--page" -> page = Integer.parseInt(args[++i]);
                case "--endless" -> endless = true;
                case "--fail-list" -> failList = true;
                case "--answer" -> answer = args[++i];
                case "--stderr" -> System.err.println(args[++i]);
                case "--flood" -> flood(Integer.parseInt(args[++i]));
                case "--" -> {
                    for (int j = i + 1; j < args.length; j++) rest.add(args[j]);
                    i = args.length;
                }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            record(log, "<", line);
            JsonNode message = JSON.readTree(line);
            if (!message.has("id")) continue; // a notification
            ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
            reply.set("id", message.get("id"));
            String method = message.get("method").asText();
            JsonNode params = message.path("params");
            switch (method) {
                case "initialize" -> reply.set("result", initialize(answer));
                case "tools/list" -> {
                    if (failList)
                        reply.set(
                                "error",
                                JSON.createObjectNode()
                                        .put("code", -32603)
                                        .put("message", "the tool list is unavailable"));
                    else reply.set("result", list(params, page, endless));
                }
                case "tools/call" -> {
                    ObjectNode result = call(params, rest);
                    if (result == null) continue; // a tool that never answers
                    reply.set("result", result);
                }
                default -> reply.set(
                        "error",
                        JSON.createObjectNode()
                                .put("code", -32601)
                                .put("message", "no such method " + method));
            }
            String text = JSON.writeValueAsString(reply);
            record(log, ">", text);
            out.println(text);
        }
    }

    private static ObjectNode initialize(String answer) {
        ObjectNode result = JSON.createObjectNode().put("protocolVersion", answer);
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "fake-mcp").put("version", "1.0.0");
        return result;
    }

    private static ObjectNode list(JsonNode params, int page, boolean endless) {
        List<String> names = List.of("echo", "boom", "sleep", "die", "env", "argv", "read");
        int from = params.has("cursor") ? Integer.parseInt(params.get("cursor").asText()) : 0;
        int to = page <= 0 ? names.size() : Math.min(names.size(), from + page);
        ArrayNode tools = JSON.createArrayNode();
        for (int i = from; i < to; i++) {
            ObjectNode tool = tools.addObject().put("name", names.get(i));
            tool.put("description", "the " + names.get(i) + " tool");
            ObjectNode schema = tool.putObject("inputSchema").put("type", "object");
            schema.putObject("properties");
            if (names.get(i).equals("read"))
                tool.putObject("annotations").put("readOnlyHint", true);
        }
        ObjectNode result = JSON.createObjectNode();
        result.set("tools", tools);
        if (endless) result.put("nextCursor", String.valueOf(to % names.size()));
        else if (to < names.size()) result.put("nextCursor", String.valueOf(to));
        return result;
    }

    private static ObjectNode call(JsonNode params, List<String> rest) {
        String name = params.get("name").asText();
        JsonNode arguments = params.path("arguments");
        ObjectNode result = JSON.createObjectNode();
        ArrayNode content = result.putArray("content");
        switch (name) {
            case "boom" -> {
                content.addObject().put("type", "text").put("text", "the disk is full");
                result.put("isError", true);
            }
            case "sleep" -> {
                try {
                    Thread.sleep(arguments.path("ms").asLong(60_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
            case "die" -> Runtime.getRuntime().halt(3);
            case "env" -> {
                String value = System.getenv(arguments.path("name").asText());
                content.addObject()
                        .put("type", "text")
                        .put("text", value == null ? "<unset>" : value);
            }
            case "argv" -> content.addObject()
                    .put("type", "text")
                    .put("text", String.join("␟", rest));
            default -> content.addObject()
                    .put("type", "text")
                    .put("text", "echo: " + arguments.path("message").asText());
        }
        return result;
    }

    private static void flood(int bytes) {
        byte[] chunk = "x".repeat(1024).getBytes(StandardCharsets.UTF_8);
        for (int written = 0; written < bytes; written += chunk.length)
            System.err.write(chunk, 0, chunk.length);
        System.err.println();
        System.err.flush();
    }

    private static void record(Path log, String direction, String line) throws IOException {
        if (log == null) return;
        Files.writeString(
                log, direction + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
