package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R19 of spec 14: a classifier of the candidate's own script can judge whether two answers differ.
 */
class JudgesClassifierTest {

    @TempDir Path dir;

    private IntentClassifierDef def(String body) throws Exception {
        Path s = dir.resolve("j.loom");
        Files.writeString(
                s,
                "intentclassifier Judge {\n    model: \"m\"\n    "
                        + body
                        + "\n}\nagent A { model: \"m\"  system: \"x\" }\nworkflow Main(q) {\n    delegate \"{q}\" to A -> r\n    return r\n}\n");
        return new LoomLoader().load(s.toString()).getIntentClassifiers().get(0);
    }

    private static final String YES =
            "{\"answers\": {\"same\": {\"answer\": \"yes\", \"confidence\": 0.95}}}";
    private static final String NO =
            "{\"answers\": {\"same\": {\"answer\": \"no\", \"confidence\": 0.95}}}";

    @Test
    void aYesAnswerMeansTheTwoAreTheSame() throws Exception {
        var models = new RegressLiveTest.FakeModels(YES);
        Spent spent = new Spent(10_000L, null);
        Judges.Judge j =
                Judges.classifier(
                        models.createClient("m"),
                        def("ask same: \"Do they say the same?\""),
                        spent,
                        null);
        assertThat(j.differs("Sent today.", "Sent today!")).isFalse();
        assertThat(models.calls.get()).isEqualTo(1);
    }

    @Test
    void aNoAnswerMeansTheyDiffer() throws Exception {
        var models = new RegressLiveTest.FakeModels(NO);
        Judges.Judge j =
                Judges.classifier(
                        models.createClient("m"),
                        def("ask same: \"Do they say the same?\""),
                        new Spent(10_000L, null),
                        null);
        assertThat(j.differs("Sent today.", "Refunded.")).isTrue();
    }

    @Test
    void aClassifierThatCannotJudgeIsRefusedBeforeAnythingIsAsked() throws Exception {
        var models = new RegressLiveTest.FakeModels(YES);
        IntentClassifierDef other = def("choose kind: a | b");
        assertThatThrownBy(
                        () ->
                                Judges.classifier(
                                        models.createClient("m"), other, new Spent(1L, null), null))
                .hasMessageContaining("cannot judge");
        assertThat(models.calls.get()).isZero();
    }

    @Test
    void theCapStopsTheJudgeBeforeItAsks() throws Exception {
        var models = new RegressLiveTest.FakeModels(YES);
        Spent spent = new Spent(1L, null);
        spent.add(5, 1, null);
        Judges.Judge j =
                Judges.classifier(
                        models.createClient("m"), def("ask same: \"Same?\""), spent, null);
        assertThatThrownBy(() -> j.differs("a", "b")).isInstanceOf(Regress.CapReached.class);
        assertThat(models.calls.get()).isZero();
    }
}
