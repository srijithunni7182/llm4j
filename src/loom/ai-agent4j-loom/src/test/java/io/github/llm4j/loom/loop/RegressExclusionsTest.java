package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R20, R29 of spec 14: a run that cannot be compared is left out, said, and never guessed. */
class RegressExclusionsTest {

    @TempDir Path dir;

    private static final ObjectMapper M = new ObjectMapper();

    private Path run(Path store, Path script, String id, String edit) throws Exception {
        var body = M.createObjectNode();
        body.putObject("input").put("message", "m " + id);
        body.putArray("humans").add("yes");
        var models = body.putObject("models");
        models.putObject("Intent").put("kind", "order_status").put("urgent", "no");
        models.put("Replier", "On its way.");
        Path r = Runs.run(store, id, script, body, Map.of("message", "m " + id));
        if (edit != null) {
            ObjectNode spec = (ObjectNode) M.readTree(r.resolve("run.json").toFile());
            switch (edit) {
                case "workflow" -> spec.put("workflow", "Other");
                case "nohash" -> spec.remove("scriptHash");
                case "oldhash" -> spec.put("scriptHash", "f".repeat(64));
                default -> throw new IllegalStateException(edit);
            }
            M.writeValue(r.resolve("run.json").toFile(), spec);
        }
        return r;
    }

    @Test
    void eachWayARunIsLeftOutIsSaidInTheReportInEveryFormat() throws Exception {
        Path script = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = dir.resolve("store");
        run(store, script, "ok", null);
        run(store, script, "other", "workflow");
        run(store, script, "nohash", "nohash");
        run(store, script, "oldhash", "oldhash");
        Baselines b = Baselines.of(script, null, null, dir.resolve("tmp"));
        Regress.Report r =
                Regress.run(
                        new Regress.Request(
                                script,
                                b,
                                store,
                                null,
                                200,
                                Sampler.Strategy.RANDOM,
                                1,
                                "none",
                                null,
                                null,
                                30));
        assertThat(r.comparisons()).extracting(Regress.Comparison::run).containsExactly("ok");
        assertThat(r.excluded())
                .extracting(Regress.Excluded::run)
                .containsExactlyInAnyOrder("other", "nohash", "oldhash");
        String why = r.excluded().stream().map(Regress.Excluded::why).reduce("", String::concat);
        assertThat(why)
                .contains("which the candidate does not have")
                .contains("recorded no script version")
                .contains("cannot be recovered");
        for (String text :
                new String[] {
                    RegressReport.text(r, true, null),
                    RegressReport.markdown(r),
                    RegressReport.html(r),
                    RegressReport.json(r, true, null)
                }) assertThat(text).contains("recorded no script version");
        for (String text :
                new String[] {
                    RegressReport.text(r, true, null),
                    RegressReport.markdown(r),
                    RegressReport.html(r),
                    RegressReport.json(r, true, null),
                    RegressReport.junit(r)
                }) {
            assertThat(text).isNotBlank();
            Files.writeString(dir.resolve("out"), text); // each format is whole text
        }
    }
}
