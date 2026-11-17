package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.ratelimit.Sleeper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * How the Guide asks the editor to show something: open the graph, the review, the report, the
 * key form, or a drafted instruction. There is no network: a request is a file in {@code
 * .loom/guide/requests/}, the editor (which marks itself present with {@code
 * .loom/guide/editor.json}) watches that folder, acts, and writes the answer to {@code
 * .loom/guide/answers/}. When no editor is open the answer says so at once; a request the editor
 * does not answer in time is given up.
 */
interface EditorChannel {

    /** The editor's answer to one action, in words the Guide can pass on. */
    String send(String action, JsonNode args);

    /** The answer when no editor is listening. */
    String NOT_OPEN =
            "The editor is not open on this project, so nothing was shown. Ask the developer to open the folder in VS Code with the Loom extension, or name the file or command for them instead.";

    /** The mailbox the running editor watches for this project. */
    static EditorChannel mailbox(Path projectRoot, Clock clock, Sleeper sleeper) {
        return new Mailbox(projectRoot, clock, sleeper);
    }

    final class Mailbox implements EditorChannel {
        /** Showing something takes the editor a moment. */
        static final Duration SHOW = Duration.ofSeconds(20);
        /** A person may take a while with a form or an instruction; never longer than this. */
        static final Duration PERSON = Duration.ofMinutes(5);
        static final Duration POLL = Duration.ofMillis(200);
        private static final List<String> WAIT_FOR_PERSON = List.of("open_secret_form", "draft_builder_instruction", "start_observed_run");
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final SecureRandom RANDOM = new SecureRandom();

        private final Path folder;
        private final Clock clock;
        private final Sleeper sleeper;

        Mailbox(Path projectRoot, Clock clock, Sleeper sleeper) {
            this.folder = Objects.requireNonNull(projectRoot, "projectRoot").resolve(".loom").resolve("guide");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        }

        @Override
        public String send(String action, JsonNode args) {
            if (!Files.isRegularFile(folder.resolve("editor.json"))) return NOT_OPEN;
            byte[] idBytes = new byte[12];
            RANDOM.nextBytes(idBytes);
            String id = HexFormat.of().formatHex(idBytes);
            Path requests = folder.resolve("requests");
            Path answer = folder.resolve("answers").resolve(id + ".json");
            try {
                Files.createDirectories(requests);
                ObjectNode body = JSON.createObjectNode();
                body.put("id", id);
                body.put("action", action);
                body.put("at", clock.instant().toString());
                body.set("args", args == null ? JSON.createObjectNode() : args);
                Path tmp = requests.resolve(id + ".tmp");
                Files.writeString(tmp, JSON.writeValueAsString(body));
                // the editor sees only whole requests
                Files.move(tmp, requests.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE);
                Instant until = clock.instant().plus(WAIT_FOR_PERSON.contains(action) ? PERSON : SHOW);
                while (clock.instant().isBefore(until)) {
                    if (Files.isRegularFile(answer)) {
                        String result = JSON.readTree(answer.toFile()).path("result").asText("done");
                        Files.deleteIfExists(answer);
                        return result;
                    }
                    sleeper.sleep(POLL);
                }
                Files.deleteIfExists(requests.resolve(id + ".json"));
                return "The editor did not answer in time, so nothing was shown. It may be busy or closed; ask the developer.";
            } catch (IOException e) {
                return "Could not reach the editor: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Interrupted before the editor answered.";
            }
        }
    }
}
