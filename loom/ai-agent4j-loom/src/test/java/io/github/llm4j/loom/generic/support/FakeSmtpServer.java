package io.github.llm4j.loom.generic.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny scripted SMTP server: it can refuse a login or a recipient, answer the data with a failure, or hang up. */
public final class FakeSmtpServer implements AutoCloseable {

    public enum AfterData { ACCEPT, TEMPORARY_FAILURE, PERMANENT_FAILURE, HANG_UP }

    public volatile boolean advertiseAuth = false;
    public volatile boolean authAccepts = true;
    public volatile int rejectRecipientNumber = -1; // 1-based; -1 never
    public volatile AfterData afterData = AfterData.ACCEPT;
    public final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger connections = new AtomicInteger();
    private final ServerSocket socket;
    private final Thread acceptor;

    public FakeSmtpServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-smtp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                connections.incrementAndGet();
                Thread t = new Thread(() -> serve(client), "fake-smtp-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client; BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {
            reply(out, "220 fake ESMTP");
            int recipients = 0;
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.toUpperCase();
                if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) {
                    reply(out, advertiseAuth ? "250-fake\r\n250-AUTH PLAIN\r\n250 8BITMIME" : "250 fake");
                } else if (cmd.startsWith("AUTH")) {
                    if (cmd.equals("AUTH PLAIN")) {
                        reply(out, "334 ");
                        in.readLine();
                    }
                    reply(out, authAccepts ? "235 2.7.0 ok" : "535 5.7.8 authentication failed");
                } else if (cmd.startsWith("MAIL")) {
                    reply(out, "250 ok");
                } else if (cmd.startsWith("RCPT")) {
                    recipients++;
                    reply(out, recipients == rejectRecipientNumber ? "550 5.1.1 no such user" : "250 ok");
                } else if (cmd.equals("DATA")) {
                    reply(out, "354 go ahead");
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) data.append(line).append("\n");
                    switch (afterData) {
                        case ACCEPT -> {
                            messages.add(data.toString());
                            reply(out, "250 queued");
                        }
                        case TEMPORARY_FAILURE -> reply(out, "451 4.3.0 try again later");
                        case PERMANENT_FAILURE -> reply(out, "554 5.6.0 message refused");
                        case HANG_UP -> {
                            return;
                        }
                    }
                } else if (cmd.startsWith("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else if (cmd.startsWith("RSET") || cmd.startsWith("NOOP")) {
                    reply(out, "250 ok");
                } else {
                    reply(out, "502 not implemented");
                }
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
