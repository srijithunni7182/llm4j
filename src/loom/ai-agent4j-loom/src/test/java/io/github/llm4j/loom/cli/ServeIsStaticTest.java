package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.ServeClient;
import io.github.llm4j.loom.serve.Server;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R15 and R17 of spec 10: {@code weave serve} runs nothing and writes nothing. A script made of
 * things that act (a shell command, a file write, a mail, a task) is checked, graphed and reviewed,
 * and afterwards nothing has happened.
 */
class ServeIsStaticTest {

    @TempDir Path dir;

    static final String ACTING =
            """
            tool Danger  { use: shell  command: "touch %s/shell-ran"  allow: "touch *" }
            tool Writer  { use: file   root: "%s"  mode: readwrite  overwrite: true  allow: "*.txt" }
            tool Mailer  { use: email  outbox: "%s/outbox"  from: "a@example.com"  to: "b@example.com" }
            agent Doer { model: "gemini-2.5-flash"  system: "Do it."  tools: [Danger, Writer, Mailer] }
            workflow Main(x) {
                delegate "do {x}" to Doer -> r
                return r
            }
            """;

    private List<String> filesIn(Path p) throws Exception {
        try (Stream<Path> s = Files.walk(p)) {
            return s.map(f -> p.relativize(f).toString()).sorted().toList();
        }
    }

    @Test
    void checkGraphAndReviewDoNothingAScriptCouldDo() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(script, ACTING.formatted(dir, dir, dir));
        List<String> before = filesIn(dir);
        long children = ProcessHandle.current().children().count();
        try (ServeClient c =
                new ServeClient(new Server(new CliEngine(new ResultCache(1 << 24), "t"), 20000))) {
            ObjectNode init = ServeClient.params();
            init.putArray("roots").add(dir.toString());
            c.request("initialize", init);
            ObjectNode p = ServeClient.params().put("file", script.toString());
            for (String m : List.of("loom/check", "loom/graph", "loom/review")) {
                JsonNode r = c.request(m, p);
                assertThat(r.has("result")).as(m + ": " + r).isTrue();
            }
            ObjectNode full =
                    ServeClient.params().put("file", script.toString()).put("tier", "full");
            assertThat(c.request("loom/review", full).has("result")).isTrue();
        }
        assertThat(filesIn(dir)).as("no file was created or removed").isEqualTo(before);
        assertThat(Files.exists(dir.resolve("shell-ran"))).isFalse();
        assertThat(ProcessHandle.current().children().count())
                .as("no process was started")
                .isEqualTo(children);
    }
}
