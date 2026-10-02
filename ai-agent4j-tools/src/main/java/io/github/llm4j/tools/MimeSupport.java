package io.github.llm4j.tools;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds a MIME message from an {@link EmailMessage}. The only place (with its callers) that touches the mail library. */
final class MimeSupport {

    private MimeSupport() {}

    static MimeMessage build(Session session, EmailMessage m) throws MessagingException {
        MimeMessage mime = new MimeMessage(session);
        mime.setFrom(address(m.from()));
        mime.setRecipients(Message.RecipientType.TO, addresses(m.to()));
        if (!m.cc().isEmpty()) mime.setRecipients(Message.RecipientType.CC, addresses(m.cc()));
        if (!m.bcc().isEmpty()) mime.setRecipients(Message.RecipientType.BCC, addresses(m.bcc()));
        mime.setSubject(m.subject(), StandardCharsets.UTF_8.name());
        String type = m.html() ? "html" : "plain";
        if (m.attachments().isEmpty()) {
            mime.setText(m.body(), StandardCharsets.UTF_8.name(), type);
        } else {
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(m.body(), StandardCharsets.UTF_8.name(), type);
            multipart.addBodyPart(text);
            for (var path : m.attachments()) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new FileDataSource(path.toFile())));
                part.setFileName(path.getFileName().toString());
                multipart.addBodyPart(part);
            }
            mime.setContent(multipart);
        }
        mime.saveChanges();
        return mime;
    }

    private static InternetAddress[] addresses(List<MailAddress> list) throws MessagingException {
        InternetAddress[] out = new InternetAddress[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = address(list.get(i));
        return out;
    }

    private static InternetAddress address(MailAddress a) throws MessagingException {
        try {
            return new InternetAddress(a.address(), a.display(), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("can't encode " + a.address(), e);
        }
    }
}
