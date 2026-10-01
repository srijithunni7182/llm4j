package io.github.llm4j.loom.tools.generic;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Writes each message to a directory as an {@code .eml} file instead of sending it. For development and tests. */
public final class OutboxSender implements EmailSender {

    private final Path dir;
    private final AtomicInteger counter = new AtomicInteger();

    public OutboxSender(Path dir) {
        this.dir = dir;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            Files.createDirectories(dir);
            MimeMessage mime = MimeSupport.build(Session.getInstance(new Properties()), message);
            mime.setHeader("X-Loom-Envelope-To", message.recipients().stream().map(MailAddress::address).collect(Collectors.joining(", ")));
            Path file = dir.resolve(System.currentTimeMillis() + "-" + counter.incrementAndGet() + "-" + Integer.toHexString(System.identityHashCode(message)) + ".eml");
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                mime.writeTo(out, new String[] {"Bcc"}); // like a real send, the Bcc header is not written
            }
        } catch (IOException | jakarta.mail.MessagingException e) {
            throw new EmailFailure(Stage.CONNECT, "couldn't write the message to the outbox");
        }
    }
}
