package io.github.llm4j.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for a browser's DevTools socket: a minimal WebSocket server (RFC 6455, text frames)
 * that answers each command the way Chrome does, so the start-up path runs without a browser.
 */
final class StandInDevTools implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    final ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    final List<String> methods = new CopyOnWriteArrayList<>();

    StandInDevTools() throws IOException {
        Thread t = new Thread(this::serve, "stand-in-devtools");
        t.setDaemon(true);
        t.start();
    }

    String url() {
        return "ws://127.0.0.1:" + server.getLocalPort() + "/devtools/browser/stand-in";
    }

    private void serve() {
        try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            BufferedReader head = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String key = null;
            for (String line = head.readLine(); line != null && !line.isEmpty(); line = head.readLine())
                if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).strip();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            while (true) {
                String text = readFrame(in);
                if (text == null) return;
                JsonNode m = JSON.readTree(text);
                String method = m.path("method").asText();
                methods.add(method);
                ObjectNode result = JSON.createObjectNode();
                if (method.equals("Target.createTarget")) result.put("targetId", "T1");
                if (method.equals("Target.attachToTarget")) result.put("sessionId", "S1");
                ObjectNode reply = JSON.createObjectNode().put("id", m.get("id").asInt());
                reply.set("result", result);
                writeFrame(out, reply.toString());
            }
        } catch (Exception closed) {
            // the client went away
        }
    }

    /** One client frame (masked), or null on close. */
    private static String readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0 || (b0 & 0x0f) == 8) return null;
        int b1 = in.read();
        long len = b1 & 0x7f;
        if (len == 126) len = (in.read() << 8) | in.read();
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] mask = in.readNBytes(4);
        byte[] data = in.readNBytes((int) len);
        for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeFrame(OutputStream out, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        if (data.length < 126) out.write(data.length);
        else {
            out.write(126);
            out.write(data.length >> 8);
            out.write(data.length & 0xff);
        }
        out.write(data);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
