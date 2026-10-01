package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * {@code tool Mail { use: email  host: "smtp.example.com"  username: env.U  password: env.P
 * from: "digest@example.com"  to: "team@example.com" }}: sends mail over SMTP. Recipients are fixed
 * ({@code to}) or limited to an allow-list ({@code allow_to}), so an agent can't mail just anyone.
 */
public final class EmailKind extends GenericKind {

    @Override
    public String name() {
        return "email";
    }

    @Override
    public Set<String> required() {
        return Set.of("from");
    }

    @Override
    public Set<String> optional() {
        return Set.of("host", "port", "security", "username", "password", "to", "allow_to", "cc", "bcc", "max_recipients",
                "max_per_run", "attachments", "outbox", "allow_insecure", "on_unknown", "timeout");
    }

    @Override
    public Set<String> secrets() {
        return Set.of("password");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        EmailTool.Config.parse(o, baseDir);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        EmailTool.Config c = EmailTool.Config.parse(o, baseDir);
        EmailSender sender = c.outbox() != null ? new OutboxSender(c.outbox())
                : new SmtpSender(c.host(), c.port(), c.security(), c.username(), c.password(), c.timeout());
        return new EmailTool(name, c, sender, redactor(o, o.string("username", "")), context, baseDir);
    }

    static Duration maxTimeout() {
        return Duration.ofMinutes(5);
    }
}
