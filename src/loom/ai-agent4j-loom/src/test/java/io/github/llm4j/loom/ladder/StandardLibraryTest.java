package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.Interpreter;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.execution.LibraryImports;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The measures that ship with Loom are ordinary Loom, and each does what its comment says. */
@Tag("L2")
class StandardLibraryTest {

    @TempDir Path dir;

    private LoomScript load(String source) throws IOException {
        Path file = dir.resolve("main.loom");
        Files.writeString(file, source);
        return new LoomLoader().load(file.toString());
    }

    private static V.Cs history(Rec... recs) {
        return HistoryBuilder.history(LedgerFold.cases(List.of(recs)), "all", 1);
    }

    private static Rec[] answered(
            String id, long sec, Map<String, Object> proposed, Map<String, Object> responded) {
        return new Rec[] {
            new Rec(
                    id + "#case#1",
                    "D",
                    Rec.CASE,
                    LedgerFoldTest.at(sec),
                    id,
                    1,
                    Rec.map(
                            "scope",
                            "all",
                            "level",
                            "x",
                            "epoch",
                            1,
                            "identity",
                            "i",
                            "fields",
                            Map.of())),
            new Rec(
                    id + "#proposed#1",
                    "D",
                    Rec.PROPOSED,
                    LedgerFoldTest.at(sec + 1),
                    id,
                    1,
                    proposed),
            new Rec(
                    id + "#responded#1",
                    "D",
                    Rec.RESPONDED,
                    LedgerFoldTest.at(sec + 2),
                    id,
                    1,
                    responded)
        };
    }

    private static Rec[] join(Rec[]... parts) {
        return java.util.Arrays.stream(parts).flatMap(java.util.Arrays::stream).toArray(Rec[]::new);
    }

    private double eval(LoomScript s, String expr, V.Cs h) {
        Map<String, Interpreter.Measure> ms = new LinkedHashMap<>();
        s.getMeasures()
                .forEach(
                        m ->
                                ms.put(
                                        m.getName(),
                                        new Interpreter.Measure(
                                                m.getName(), m.getParam(), m.getBody())));
        V v =
                Interpreter.eval(
                        io.github.llm4j.loom.autonomy.expr.ExprParser.parse(expr),
                        new Interpreter.Context(Map.of("history", h), ms, 0));
        assertThat(v).isInstanceOf(V.Num.class);
        return ((V.Num) v).value();
    }

    @Test
    void importingTheLibraryBringsItsMeasuresAndNothingNeedsAFile() throws IOException {
        LoomScript s = load("import \"loom/autonomy\"\nagent A { model: \"m\" system: \"s\" }\n");
        assertThat(s.getMeasures())
                .extracting(m -> m.getName())
                .contains(
                        "accepted",
                        "edited",
                        "rejected",
                        "agrees",
                        "edit_size",
                        "untouched",
                        "reversed");
        assertThat(LibraryImports.isLibrary("loom/autonomy")).isTrue();
        assertThat(LibraryImports.isLibrary("loom/autonomy.loom")).isFalse();
        assertThat(LibraryImports.isLibrary("./loom/autonomy")).isFalse();
        assertThat(LibraryImports.isLibrary("other")).isFalse();
        assertThatThrownBy(() -> LibraryImports.load("loom/nothing"))
                .hasMessageContaining("There is no standard library called loom/nothing");
        assertThat(
                        new ScriptValidator()
                                        .validate(
                                                s,
                                                new ScriptValidator.Context()
                                                        .registeredTools(Set.of()))
                                        .stream()
                                        .filter(
                                                p ->
                                                        p.severity()
                                                                == ScriptValidator.Severity.ERROR))
                .isEmpty();
    }

    @Test
    void whatThePersonDidAndWhetherTheyAgreed() throws IOException {
        LoomScript s = load("import \"loom/autonomy\"\n");
        V.Cs h =
                history(
                        join(
                                answered(
                                        "a",
                                        0,
                                        Rec.map("choice", "approve"),
                                        Rec.map("kind", "accept", "choice", "approve", "by", "p")),
                                answered(
                                        "b",
                                        10,
                                        Rec.map("choice", "approve"),
                                        Rec.map("kind", "reject", "choice", "reject", "by", "p")),
                                answered(
                                        "c",
                                        20,
                                        Rec.map("choice", "approve"),
                                        Rec.map("kind", "edit", "choice", "approve", "by", "p"))));
        assertThat(eval(s, "history.count(accepted)", h)).isEqualTo(1);
        assertThat(eval(s, "history.count(rejected)", h)).isEqualTo(1);
        assertThat(eval(s, "history.count(edited)", h)).isEqualTo(1);
        assertThat(eval(s, "history.count(agrees)", h)).isEqualTo(2);
    }

    @Test
    void editSizeIsZeroForAnAcceptAndGrowsWithTheEdit() throws IOException {
        LoomScript s = load("import \"loom/autonomy\"\n");
        V.Cs h =
                history(
                        join(
                                answered(
                                        "a",
                                        0,
                                        Rec.map("value", "Hello world"),
                                        Rec.map(
                                                "kind",
                                                "accept",
                                                "final",
                                                "Hello world",
                                                "by",
                                                "p")),
                                answered(
                                        "b",
                                        10,
                                        Rec.map("value", "Hello wrld"),
                                        Rec.map("kind", "edit", "final", "Hello world", "by", "p")),
                                answered(
                                        "c",
                                        20,
                                        Rec.map("value", "Hello world"),
                                        Rec.map("kind", "reject", "final", "", "by", "p"))));
        assertThat(eval(s, "history.min(edit_size)", h)).isEqualTo(0);
        assertThat(eval(s, "history.max(edit_size)", h)).isEqualTo(1);
        assertThat(eval(s, "history.median(edit_size)", h)).isBetween(0.05, 0.15);
        assertThat(eval(s, "history.count(untouched)", h)).isEqualTo(1);
    }

    @Test
    void reversedIsTrueOnlyForCasesWithThatOutcomeAndUnknownForTheRest() throws IOException {
        LoomScript s = load("import \"loom/autonomy\"\n");
        Rec[] a =
                answered(
                        "a",
                        0,
                        Rec.map("choice", "approve"),
                        Rec.map("kind", "accept", "choice", "approve", "by", "agent"));
        Rec[] b =
                answered(
                        "b",
                        10,
                        Rec.map("choice", "approve"),
                        Rec.map("kind", "accept", "choice", "approve", "by", "agent"));
        Rec reverted =
                new Rec(
                        "a#outcome#reversed#0",
                        "D",
                        Rec.OUTCOME,
                        LedgerFoldTest.at(100),
                        "a",
                        1,
                        Rec.map("result", "reversed"));
        Rec views =
                new Rec(
                        "b#outcome#views#0",
                        "D",
                        Rec.OUTCOME,
                        LedgerFoldTest.at(100),
                        "b",
                        1,
                        Rec.map("result", "views", "value", 10));
        V.Cs h = history(join(a, b, new Rec[] {reverted, views}));
        assertThat(eval(s, "history.count(reversed)", h)).isEqualTo(1);
        assertThat(eval(s, "history.rate(reversed).n", h))
                .isEqualTo(
                        1); // the case with an unrelated outcome is not counted as "not reversed"
    }
}
