package io.github.llm4j.tools;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Properties;
import javax.net.ssl.SSLException;

/** Sends over SMTP with TLS as configured. Partial delivery is off: a refused recipient means nobody gets it. */
public final class SmtpSender implements EmailSender {

    /** How the connection is secured. */
    public enum Security { STARTTLS, SSL, NONE }

    private final String host;
    private final int port;
    private final Security security;
    private final String username;
    private final String password;
    private final Duration timeout;

    public SmtpSender(String host, int port, Security security, String username, String password, Duration timeout) {
        this.host = host;
        this.port = port;
        this.security = security;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
    }

    @Override
    public void send(EmailMessage message) {
        Session session = Session.getInstance(properties());
        try (Transport transport = session.getTransport("smtp")) {
            try {
                transport.connect(host, port, username, password);
            } catch (AuthenticationFailedException e) {
                throw new EmailFailure(Stage.AUTH, "authentication failed");
            } catch (MessagingException e) {
                throw new EmailFailure(Stage.CONNECT, connectReason(e));
            }
            MimeMessage mime = MimeSupport.build(session, message);
            try {
                transport.sendMessage(mime, mime.getAllRecipients());
            } catch (SendFailedException e) {
                throw failure(e);
            } catch (MessagingException e) {
                throw new EmailFailure(Stage.LOST, "the connection was lost while sending; delivery is unknown");
            }
        } catch (EmailFailure e) {
            throw e;
        } catch (MessagingException e) {
            throw new EmailFailure(Stage.LOST, "the mail could not be completed: " + e.getClass().getSimpleName());
        }
    }

    private Properties properties() {
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.connectiontimeout", String.valueOf(timeout.toMillis()));
        p.put("mail.smtp.timeout", String.valueOf(timeout.toMillis()));
        p.put("mail.smtp.writetimeout", String.valueOf(timeout.toMillis()));
        p.put("mail.smtp.sendpartial", "false");
        if (username != null) p.put("mail.smtp.auth", "true");
        switch (security) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> { /* only reachable for loopback or allow_insecure */ }
        }
        return p;
    }

    private static String connectReason(MessagingException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) return "couldn't find the mail server";
            if (t instanceof ConnectException) return "couldn't connect to the mail server";
            if (t instanceof SSLException) return "the TLS connection to the mail server failed";
            String m = t.getMessage();
            if (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("starttls")) return "the mail server doesn't offer STARTTLS, which is required";
        }
        return "couldn't connect to the mail server";
    }

    /**
     * Which stage a refusal came at: the sender or a recipient (nothing was accepted), or the message itself
     * after its data (a temporary or permanent failure).
     */
    private static EmailFailure failure(SendFailedException e) {
        if (e instanceof SMTPSendFailedException s && isDataStage(s.getCommand())) {
            // No reply code at all means the server went away before answering: the message may have been taken.
            if (s.getReturnCode() < 100) return new EmailFailure(Stage.LOST, "the connection was lost while sending; delivery is unknown");
            boolean temporary = s.getReturnCode() >= 400 && s.getReturnCode() < 500;
            return new EmailFailure(temporary ? Stage.TRANSIENT : Stage.REJECTED,
                    temporary ? "the server asked to try again later (" + s.getReturnCode() + ")"
                            : "the server refused the message (" + s.getReturnCode() + ")");
        }
        return new EmailFailure(Stage.RECIPIENT, "the server refused the sender or a recipient");
    }

    private static boolean isDataStage(String command) {
        if (command == null) return false;
        String c = command.toUpperCase(java.util.Locale.ROOT);
        return !(c.startsWith("MAIL") || c.startsWith("RCPT"));
    }
}
