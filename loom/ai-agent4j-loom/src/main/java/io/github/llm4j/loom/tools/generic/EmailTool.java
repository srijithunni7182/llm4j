package io.github.llm4j.loom.tools.generic;

import io.github.llm4j.loom.tools.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sends one email per call, to recipients the declaration allows. */
final class EmailTool extends GenericTool {

    private static final int MAX_SUBJECT = 200;
    private static final int MAX_BODY_BYTES = 200 * 1024;
    private static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    /** A declaration, parsed and checked. */
    record Config(String host, int port, SmtpSender.Security security, String username, String password, MailAddress from,
                  List<MailAddress> fixedTo, List<String> allowTo, List<MailAddress> cc, List<MailAddress> bcc,
                  int maxRecipients, int maxPerRun, boolean attachments, Path outbox, Duration timeout,
                  EffectPolicy.OnUnknown onUnknown) {

        static Config parse(Options o, Path baseDir) {
            Path outbox = null;
            if (o.has("outbox")) {
                try {
                    outbox = SafePaths.inside(baseDir, o.get("outbox"));
                } catch (IllegalArgumentException e) {
                    throw new OptionException("outbox: " + e.getMessage());
                }
            }
            String host = o.get("host");
            if (outbox == null && (host == null || host.isBlank())) throw new OptionException("host: is required (or give outbox: to write messages to files instead)");

            SmtpSender.Security security = SmtpSender.Security.valueOf(o.choice("security", "starttls", "starttls", "ssl", "none").toUpperCase(Locale.ROOT));
            boolean loopback = host != null && NetPolicy.isLoopbackName(host);
            if (outbox == null && security == SmtpSender.Security.NONE && !loopback && !o.bool("allow_insecure", false)) {
                throw new OptionException("security: none sends mail unencrypted; it is only allowed for localhost, or with allow_insecure: true");
            }
            int port = o.integer("port", security == SmtpSender.Security.SSL ? 465 : 587, 1, 65535);
            if (o.has("username") != o.has("password")) throw new OptionException("username and password go together");

            MailAddress from = address("from", o.require("from"));
            List<MailAddress> fixed = addresses("to", o.list("to"));
            List<String> allow = o.list("allow_to");
            if (fixed.isEmpty() == allow.isEmpty()) throw new OptionException("give exactly one of to: (fixed recipients) or allow_to: (addresses the agent may choose)");
            for (String pattern : allow) {
                if (!MailAddress.validPattern(pattern)) throw new OptionException("allow_to: " + pattern + " is not an address or a *@domain pattern");
            }
            List<MailAddress> cc = addresses("cc", o.list("cc"));
            List<MailAddress> bcc = addresses("bcc", o.list("bcc"));
            int maxRecipients = o.integer("max_recipients", 20, 1, 500);
            if (fixed.size() + cc.size() + bcc.size() > maxRecipients) throw new OptionException("max_recipients: " + maxRecipients + " is fewer than the fixed recipients");
            return new Config(host, port, security, o.get("username"), o.get("password"), from, fixed, allow, cc, bcc, maxRecipients,
                    o.integer("max_per_run", 20, 1, 10_000), o.bool("attachments", false), outbox,
                    o.duration("timeout", Duration.ofSeconds(30), EmailKind.maxTimeout()),
                    EffectPolicy.parse(o.choice("on_unknown", "skip", "skip", "retry")));
        }

        private static MailAddress address(String option, String text) {
            try {
                return MailAddress.parse(text);
            } catch (IllegalArgumentException e) {
                throw new OptionException(option + ": " + e.getMessage());
            }
        }

        private static List<MailAddress> addresses(String option, List<String> texts) {
            List<MailAddress> out = new ArrayList<>();
            for (String t : texts) out.add(address(option, t));
            return out;
        }
    }

    private final Config config;
    private final EmailSender sender;
    private final PathGuard guard;

    EmailTool(String name, Config config, EmailSender sender, Redactor redactor, EffectContext context, Path baseDir) {
        super(name, "email", description(config), redactor, context);
        this.config = config;
        this.sender = sender;
        this.guard = new PathGuard(baseDir, context.reservedPaths());
    }

    private static String description(Config c) {
        StringBuilder d = new StringBuilder("Sends an email");
        d.append(c.fixedTo().isEmpty() ? ". Arguments: to (required: one or more addresses separated by commas), "
                : " to a fixed list of recipients (you can't change it). Arguments: ");
        d.append("subject (required, one line, at most ").append(MAX_SUBJECT).append(" characters), body (required, plain text");
        d.append("; html: true sends it as HTML)");
        if (c.attachments()) d.append(", attach (optional: file paths, comma separated)");
        d.append(". Returns a confirmation or an Error.");
        return d.toString();
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return true;
    }

    @Override
    public EffectPolicy policy() {
        return new EffectPolicy(config.onUnknown(), false, config.maxPerRun());
    }

    @Override
    public String target(Map<String, Object> args) {
        try {
            return recipients(args).size() + " recipients";
        } catch (RuntimeException e) {
            return "recipients";
        }
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws IOException {
        String subject = text(args, "subject").strip(); // surrounding whitespace is not significant in a header
        String body = text(args, "body");
        if (subject.length() > MAX_SUBJECT) throw new ToolRefusal("subject is too long (the limit is " + MAX_SUBJECT + " characters)");
        for (int i = 0; i < subject.length(); i++) {
            char c = subject.charAt(i);
            if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029) throw new ToolRefusal("subject must be a single line");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) throw new ToolRefusal("body is too large (the limit is " + MAX_BODY_BYTES / 1024 + " KB)");

        List<MailAddress> to = chosenTo(args);
        int total = to.size() + config.cc().size() + config.bcc().size();
        if (total > config.maxRecipients()) throw new ToolRefusal("too many recipients (" + total + "; the limit is " + config.maxRecipients() + ")");

        boolean html = "true".equalsIgnoreCase(String.valueOf(args.getOrDefault("html", "false")));
        EmailMessage message = new EmailMessage(config.from(), to, config.cc(), config.bcc(), subject, body, html, attachments(args));
        try {
            sender.send(message);
        } catch (EmailSender.EmailFailure e) {
            if (e.stage() == EmailSender.Stage.LOST) {
                throw new UnknownOutcomeException("the connection was lost after the message was sent; delivery is unknown");
            }
            throw new ToolRefusal(e.getMessage());
        }
        return config.outbox() != null ? "Wrote the message for " + total + " recipient(s) to the outbox (nothing was sent)."
                : "Sent to " + total + " recipient(s).";
    }

    /** The recipients the agent chose, or the fixed list. */
    private List<MailAddress> chosenTo(Map<String, Object> args) {
        Object given = args.get("to");
        boolean none = given == null || (given instanceof String s && s.isBlank()) || (given instanceof List<?> l && l.isEmpty());
        if (!config.fixedTo().isEmpty()) {
            if (!none) throw new ToolRefusal("this tool sends to a fixed list of recipients; don't give to");
            return config.fixedTo();
        }
        if (none) throw new ToolRefusal("to is required");
        List<MailAddress> chosen = new ArrayList<>();
        for (String entry : splitAddresses(given)) {
            MailAddress a;
            try {
                a = MailAddress.parse(entry);
            } catch (IllegalArgumentException e) {
                throw new ToolRefusal("to: " + e.getMessage());
            }
            if (config.allowTo().stream().noneMatch(a::matches)) throw new ToolRefusal("that recipient is not allowed");
            chosen.add(a);
        }
        if (chosen.isEmpty()) throw new ToolRefusal("to is required");
        return chosen;
    }

    private List<MailAddress> recipients(Map<String, Object> args) {
        List<MailAddress> all = new ArrayList<>(config.fixedTo().isEmpty() ? parseLenient(args.get("to")) : config.fixedTo());
        all.addAll(config.cc());
        all.addAll(config.bcc());
        return all;
    }

    private static List<MailAddress> parseLenient(Object to) {
        List<MailAddress> out = new ArrayList<>();
        for (String s : splitAddresses(to)) {
            try {
                out.add(MailAddress.parse(s));
            } catch (IllegalArgumentException ignored) {
                // counted by the strict path; here only the number matters
            }
        }
        return out;
    }

    private static List<String> splitAddresses(Object to) {
        List<String> out = new ArrayList<>();
        if (to instanceof List<?> list) {
            for (Object item : list) out.add(String.valueOf(item));
        } else if (to != null) {
            for (String part : String.valueOf(to).split(",")) if (!part.isBlank()) out.add(part);
        }
        return out;
    }

    private List<Path> attachments(Map<String, Object> args) throws IOException {
        Object given = args.get("attach");
        if (given == null || (given instanceof String s && s.isBlank())) return List.of();
        if (!config.attachments()) throw new ToolRefusal("this tool doesn't send attachments");
        List<Path> files = new ArrayList<>();
        long total = 0;
        for (String entry : splitAddresses(given)) {
            Path file = guard.resolve(entry.strip());
            if (!Files.isRegularFile(file)) throw new ToolRefusal(entry.strip() + " doesn't exist or isn't a file");
            total += Files.size(file);
            if (total > MAX_ATTACHMENT_BYTES) throw new ToolRefusal("attachments are too large (the limit is " + MAX_ATTACHMENT_BYTES / (1024 * 1024) + " MB in total)");
            files.add(file);
        }
        return files;
    }
}
