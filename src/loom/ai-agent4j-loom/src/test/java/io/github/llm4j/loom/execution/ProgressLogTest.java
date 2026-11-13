package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.ProgressDecl;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The journal's side of declared progress: types, bounds, counting after a restart, what is latest. */
class ProgressLogTest {

    private static ProgressDecl decl() {
        Map<String, SchemaDef> fields = new LinkedHashMap<>();
        fields.put("stage", new SchemaDef(SchemaDef.Type.STRING));
        fields.put("done", new SchemaDef(SchemaDef.Type.NUMBER));
        fields.put("ok", new SchemaDef(SchemaDef.Type.BOOLEAN));
        SchemaDef mood = new SchemaDef(SchemaDef.Type.ENUM);
        mood.setEnumValues(List.of("calm", "busy"));
        fields.put("mood", mood);
        return new ProgressDecl(fields, 1);
    }

    private static ProgressLog.Result write(ProgressLog log, RunJournal j, String step, Map<String, Object> f) {
        return log.write(j, step, decl(), f, 5L);
    }

    @Test
    void aNumberMayBeANumberOrTextThatReadsAsOneButNotBlankOrWords() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        assertThat(write(log, j, "a", Map.of("done", 3)).kept()).isTrue();
        assertThat(write(log, j, "b", Map.of("done", " 4.5 ")).kept()).isTrue();
        assertThatThrownBy(() -> write(log, j, "c", Map.of("done", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared number");
        assertThatThrownBy(() -> write(log, j, "d", Map.of("done", "many")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"many\"");
    }

    @Test
    void aBooleanMayBeABooleanOrTheWordTrueOrFalseAndNothingElse() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        assertThat(write(log, j, "a", Map.of("ok", true)).kept()).isTrue();
        assertThat(write(log, j, "b", Map.of("ok", "false")).kept()).isTrue();
        assertThat(write(log, j, "c", Map.of("ok", "true")).kept()).isTrue();
        assertThatThrownBy(() -> write(log, j, "d", Map.of("ok", "yes"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> write(log, j, "e", Map.of("ok", 1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEnumTakesOnlyItsValuesAndTheMessageNamesThem() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        assertThat(write(log, j, "a", Map.of("mood", "busy")).kept()).isTrue();
        assertThatThrownBy(() -> write(log, j, "b", Map.of("mood", "angry")))
                .hasMessageContaining("enum[\"calm\", \"busy\"]");
    }

    @Test
    void aStringTakesTextButNotANumber() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        assertThatThrownBy(() -> write(log, j, "a", Map.of("stage", 7))).hasMessageContaining("declared string");
    }

    @Test
    void aLongValueIsQuotedWithAnEllipsisInTheMessage() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        String longText = "x".repeat(41);
        String exactly = "y".repeat(40);
        assertThatThrownBy(() -> write(log, j, "a", Map.of("done", longText)))
                .hasMessageContaining("\"" + "x".repeat(40) + "...\"");
        assertThatThrownBy(() -> write(log, j, "b", Map.of("done", exactly)))
                .hasMessageContaining("\"" + exactly + "\"")
                .hasMessageNotContaining("...");
    }

    @Test
    void aReportOfExactlyTheLimitIsKeptAndOneCharacterMoreIsDroppedAndSaid() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        String name = "stage";
        int room = ProgressLog.MAX_CHARS - name.length();
        assertThat(write(log, j, "fits", Map.of(name, "z".repeat(room))).kept()).isTrue();
        ProgressLog.Result over = write(log, j, "over", Map.of(name, "z".repeat(room + 1)));
        assertThat(over.kept()).isFalse();
        assertThat(over.reason()).contains("2000").contains("2001");
        assertThat(j.get("over#progress").orElseThrow().kind()).isEqualTo(ProgressLog.DROPPED);
    }

    @Test
    void aRunKeepsAThousandReportsAndDropsTheNext() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        for (int i = 0; i < ProgressLog.MAX_REPORTS; i++) assertThat(write(log, j, "s" + i, Map.of("done", i)).kept()).as("report " + i).isTrue();
        ProgressLog.Result past = write(log, j, "last", Map.of("done", 1));
        assertThat(past.kept()).isFalse();
        assertThat(past.reason()).contains("1000");
    }

    @Test
    void onlyTheFirstHundredDroppedReportsAreWrittenDown() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        String big = "z".repeat(ProgressLog.MAX_CHARS);
        for (int i = 0; i < ProgressLog.MAX_DROPPED_RECORDS + 5; i++) write(log, j, "d" + i, Map.of("stage", big));
        long written = j.all().values().stream().filter(e -> ProgressLog.DROPPED.equals(e.kind())).count();
        assertThat(written).isEqualTo(ProgressLog.MAX_DROPPED_RECORDS);
    }

    @Test
    void aFreshLogCountsWhatAnEarlierRunKeptAndDroppedSoNumbersContinue() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog first = new ProgressLog();
        write(first, j, "a", Map.of("done", 1));
        write(first, j, "b", Map.of("done", 2));
        write(first, j, "big", Map.of("stage", "z".repeat(ProgressLog.MAX_CHARS)));

        ProgressLog after = new ProgressLog(); // the child ran again over its journal
        write(after, j, "c", Map.of("done", 3));

        Map<?, ?> value = (Map<?, ?>) j.get("c#progress").orElseThrow().value();
        assertThat(((Number) value.get("seq")).intValue()).isEqualTo(3); // numbers start at 1, so the third report is 3
        assertThat(ProgressLog.latest(j)).containsEntry("done", 3);
    }

    @Test
    void aReplayedReportChangesNothingAndTheLatestIsTheHighestSequence() {
        RunJournal j = RunJournal.inMemory();
        ProgressLog log = new ProgressLog();
        write(log, j, "a", Map.of("done", 1));
        write(log, j, "b", Map.of("done", 2));
        assertThat(write(log, j, "a", Map.of("done", 99)).reason()).isEqualTo("replayed");
        assertThat(ProgressLog.latest(j)).containsEntry("done", 2);
        assertThat(ProgressLog.latest(RunJournal.inMemory())).isEmpty();
    }
}
