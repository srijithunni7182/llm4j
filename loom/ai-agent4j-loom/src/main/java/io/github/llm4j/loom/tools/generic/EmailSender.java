package io.github.llm4j.loom.tools.generic;

/** Delivers a message. A seam: the SMTP implementation, the outbox, or a fake in a test. */
public interface EmailSender {

    /** Where a delivery failed, which says whether the message may have gone. */
    enum Stage {
        /** Couldn't connect, or the server refused TLS: nothing was sent. */
        CONNECT,
        /** The server refused the login: nothing was sent. */
        AUTH,
        /** The server refused a sender or recipient: nothing was sent. */
        RECIPIENT,
        /** The server answered with a temporary failure after the data: not accepted. */
        TRANSIENT,
        /** The server answered with a permanent failure after the data: not accepted. */
        REJECTED,
        /** The connection was lost after the data was sent: the message may or may not have been accepted. */
        LOST
    }

    /** A failed delivery; the message never carries a password. */
    final class EmailFailure extends RuntimeException {
        private final transient Stage stage;

        public EmailFailure(Stage stage, String message) {
            super(message);
            this.stage = stage;
        }

        public Stage stage() {
            return stage;
        }
    }

    /** @throws EmailFailure when it can't be delivered */
    void send(EmailMessage message) throws EmailFailure;
}
