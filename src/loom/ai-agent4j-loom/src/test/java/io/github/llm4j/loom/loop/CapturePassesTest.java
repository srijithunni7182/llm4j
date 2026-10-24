package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.travel.GeneratedWorkflowsTest.Generator;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.wtest.LoomRun;
import io.github.llm4j.loom.wtest.LoomTest;
import io.github.llm4j.loom.wtest.TestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R1 as a property: for random workflows run with scripted models, the case captured from the run
 * passes. The corpus is the generator of phase 13; a failing seed prints its script.
 */
class CapturePassesTest {

    @TempDir Path dir;

    private static int seeds() {
        return Integer.getInteger("loom.capture.seeds", 150);
    }

    @Test
    void everyCapturedRunPassesAgainstTheScriptThatRanIt() throws Exception {
        int captured = 0;
        int withAnswers = 0;
        for (int seed = 0; seed < seeds(); seed++) {
            Generator g = new Generator(seed);
            String source = g.script();
            Path project = Files.createDirectories(dir.resolve("p" + seed));
            Path script = project.resolve("main.loom");
            Files.writeString(script, source);
            LoomRun run = g.answers(LoomTest.of(script)).run("Main", Map.of("topic", "t"));
            Path runDir = Files.createDirectories(dir.resolve("store/runs/g" + seed));
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("script", script.toString());
            spec.put("workflow", "Main");
            spec.put("inputs", Map.of("topic", "t"));
            spec.put("scriptHash", ScriptHash.of(script));
            ObjectMapper json = new ObjectMapper();
            json.writeValue(runDir.resolve("run.json").toFile(), spec);
            json.writeValue(runDir.resolve("journal.json").toFile(), run.journal().all());
            String why = "seed " + seed + "\n" + source;
            Capture.Captured c;
            try {
                c = Capture.capture(StoreReader.open(runDir), Capture.Options.defaults(), true);
            } catch (Exception e) {
                throw new AssertionError(why, e);
            }
            var suite = TestRunner.load(script, List.of(c.file()));
            assertThat(suite.problems()).as(why).isEmpty();
            var results = TestRunner.run(suite, null, false, false);
            assertThat(results.get(0).failures()).as(why).isEmpty();
            captured++;
            if (Files.readString(c.file()).contains("models:")) withAnswers++;
        }
        assertThat(captured).isEqualTo(seeds());
        // the corpus really does hold recorded answers to script back
        assertThat(withAnswers).isGreaterThan(seeds() * 3 / 4);
    }
}
