package io.github.llm4j.loom.generic.email;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.loom.generic.support.Fuzz;
import io.github.llm4j.loom.generic.support.RecordingEffects;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F3: whatever an agent puts in a subject, name or address, the message has exactly the headers and recipients intended. */
class EmailFuzzTest {

    private static final Set<String> ALLOWED_HEADERS = Set.of("from", "to", "cc", "subject", "date", "message-id", "mime-version",
            "content-type", "content-transfer-encoding", "x-loom-envelope-to");
    private static final Pattern ANGLE = Pattern.compile("<([^<>]*)>");

    @TempDir
    Path dir;

    @Test
    @Tag("F3")
    void generatedSubjectsNamesAndAddressesNeverAddHeadersOrRecipients() throws Exception {
        Declared declared = new Declared(Map.of(), dir);
        Tool t = declared.create("tool Mail { use: email  outbox: \"out/\"  from: \"Loom <digest@example.com>\"  allow_to: \"*@example.com\"  max_per_run: 10000  max_recipients: 5 }",
                new RecordingEffects());
        Path out = dir.resolve("out");
        // Awkward but legal, and the characters that must never get through.
        String awkward = "abc XYZ:()é.-_'";
        String injecting = "\r\n\u2028\u2029\u0085\u0000<>,;@\"\\";

        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        Fuzz.run("email-headers", random -> {
            String subject = text(random, awkward, injecting, 1 + random.nextInt(25)) + random.nextInt();
            List<String> recipients = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int i = 1 + random.nextInt(2); i > 0; i--) {
                String local = random.nextInt(4) == 0 ? text(random, "abc.-_+", injecting, 1 + random.nextInt(6)) : "user" + random.nextInt(1000);
                String name = random.nextBoolean() ? text(random, awkward.replace("(", "").replace(")", ""), injecting, 1 + random.nextInt(8)).strip() + " " : "";
                String domain = random.nextInt(8) == 0 ? "example.com" + random(random, "\r\n,; ", 1) : "example.com";
                recipients.add(name.isEmpty() ? local + "@" + domain : name + "<" + local + "@" + domain + ">");
            }
            String to = String.join(", ", recipients);
            Set<String> before = names(out);

            String result;
            try {
                result = t.execute(Map.of("to", to, "subject", subject, "body", "body"));
            } catch (Exception e) {
                throw new AssertionError("threw: " + e, e);
            }

            Set<String> after = names(out);
            if (result.startsWith("Error:")) {
                refused.incrementAndGet();
                assertThat(after).as("an error must not write a message").isEqualTo(before);
                return;
            }
            sent.incrementAndGet();
            after.removeAll(before);
            assertThat(after).hasSize(1);
            Path file = out.resolve(after.iterator().next());
            assertOnlyIntendedHeaders(file);
            try (InputStream in = Files.newInputStream(file)) {
                MimeMessage m = new MimeMessage(Session.getInstance(new Properties()), in);
                assertThat(m.getSubject()).isEqualTo(subject.strip());
                Set<String> envelope = splitAddresses(m.getHeader("X-Loom-Envelope-To")[0]);
                Set<String> inHeader = new HashSet<>();
                for (var a : m.getRecipients(jakarta.mail.Message.RecipientType.TO)) {
                    inHeader.add(((jakarta.mail.internet.InternetAddress) a).getAddress().toLowerCase());
                }
                assertThat(inHeader).isEqualTo(envelope);
                Set<String> asked = new HashSet<>();
                for (String r : to.split(",")) {
                    if (r.isBlank()) continue; // the tool ignores empty entries between commas
                    Matcher am = ANGLE.matcher(r);
                    asked.add((am.find() ? am.group(1) : r).strip().toLowerCase());
                }
                assertThat(envelope).as("the recipients actually written are the ones asked for").isEqualTo(asked);
            }
        });
        // The generator must exercise both outcomes, or the properties above prove nothing.
        assertThat(sent.get()).as("generated cases that were sent").isGreaterThan(Fuzz.iterations() / 20);
        assertThat(refused.get()).as("generated cases that were refused").isGreaterThan(Fuzz.iterations() / 20);
    }

    private static void assertOnlyIntendedHeaders(Path file) throws Exception {
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        String head = raw.substring(0, raw.indexOf("\r\n\r\n") >= 0 ? raw.indexOf("\r\n\r\n") : raw.indexOf("\n\n"));
        for (String line : head.split("\r?\n")) {
            if (line.startsWith(" ") || line.startsWith("\t")) continue; // folded continuation
            int colon = line.indexOf(':');
            assertThat(colon).as("a header line: " + line).isGreaterThan(0);
            assertThat(ALLOWED_HEADERS).as("unexpected header: " + line).contains(line.substring(0, colon).toLowerCase());
        }
    }

    private static Set<String> splitAddresses(String header) {
        return java.util.Arrays.stream(header.split(",")).map(s -> s.strip().toLowerCase()).collect(Collectors.toSet());
    }

    private static Set<String> names(Path dir) {
        if (!Files.isDirectory(dir)) return new HashSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(HashSet::new));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mostly from the safe alphabet, with about one case in three given one character that must be refused. */
    private static String text(Random random, String safe, String injecting, int length) {
        StringBuilder sb = new StringBuilder(random(random, safe, length));
        if (random.nextInt(3) == 0) sb.insert(random.nextInt(sb.length() + 1), injecting.charAt(random.nextInt(injecting.length())));
        return sb.toString();
    }

    private static String random(Random random, String alphabet, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static final Comparator<String> UNUSED = Comparator.naturalOrder();
}
