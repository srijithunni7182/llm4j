package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.context.CompactionRequest;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.context.ProviderContext;
import io.github.llm4j.model.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R19, R20, R21: a .loct file maps a name to a class. */
class LoctLoaderTest {

    @TempDir Path dir;

    private static final String PKG = "io.github.llm4j.loom.context.Strategies$";

    private Path file(String name, String text) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, text);
        return f;
    }

    @Test
    void aValidFileMapsNamesToClasses() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        List<CompactorRegistry.Problem> problems =
                r.load(
                        file(
                                "s.loct",
                                "# my strategies\n\nkeep_newest = "
                                        + PKG
                                        + "KeepNewest   # plain\nwith_options="
                                        + PKG
                                        + "WithOptions\n"));
        assertThat(problems).isEmpty();
        assertThat(r.names()).containsExactly("keep_newest", "with_options");
        assertThat(r.entries()).extracting(CompactorRegistry.Entry::line).containsExactly(3, 4);
        assertThat(r.verify()).isEmpty();
    }

    @Test
    void aPlainStrategyIsBuiltAndWorks() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        r.load(file("s.loct", "keep_newest = " + PKG + "KeepNewest\n"));
        ContextCompactor c = r.create("keep_newest", Map.of(), ProviderContext.none());
        List<Message> convo = new java.util.ArrayList<>();
        convo.add(Message.user("task"));
        for (int i = 0; i < 20; i++) convo.add(Message.user("m" + i + " " + "x".repeat(200)));
        var result = c.compact(convo, CompactionRequest.builder(100).keepRecent(2).build());
        assertThat(result.messages().size()).isLessThan(convo.size());
    }

    @Test
    void aPlainStrategyTakesNoOptions() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        r.load(file("s.loct", "keep_newest = " + PKG + "KeepNewest\n"));
        assertThatThrownBy(() -> r.create("keep_newest", Map.of("x", "1"), ProviderContext.none()))
                .hasMessageContaining("takes no options")
                .hasMessageContaining("x");
    }

    @Test
    void aProviderGetsTheOptionsAndTheContext() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        r.load(file("s.loct", "with_options = " + PKG + "WithOptions\n"));
        ProviderContext ctx = ProviderContext.none();
        r.create("with_options", Map.of("keep", "3"), ctx);
        assertThat(Strategies.WithOptions.lastOptions).containsEntry("keep", "3");
        assertThat(Strategies.WithOptions.lastContext).isSameAs(ctx);
        assertThatThrownBy(() -> r.create("with_options", Map.of("bad", "1"), ctx))
                .hasMessageContaining("no option 'bad'");
        assertThat(r.takesOptions("with_options")).hasValue(true);
        r.load(file("t.loct", "keep_newest = " + PKG + "KeepNewest\n"));
        assertThat(r.takesOptions("keep_newest")).hasValue(false);
    }

    @Test
    void aNameThatShadowsABuiltInIsRefused() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        var problems =
                r.load(
                        file(
                                "s.loct",
                                "drop_oldest = "
                                        + PKG
                                        + "KeepNewest\nsummarize = "
                                        + PKG
                                        + "KeepNewest\n"));
        assertThat(problems).hasSize(2).allSatisfy(p -> assertThat(p.error()).isTrue());
        assertThat(problems.get(0).toString())
                .contains("s.loct")
                .contains("line 1")
                .contains("built-in");
        assertThat(r.names()).isEmpty();
    }

    @Test
    void badLinesAreReportedWithTheirLine() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        var problems =
                r.load(
                        file(
                                "s.loct",
                                "no equals sign\nBad-Name = x.Y\nok = x.Y\nok = x.Z\n= x.Y\nempty =\n"));
        assertThat(problems)
                .extracting(CompactorRegistry.Problem::line)
                .containsExactly(1, 2, 4, 5, 6);
        assertThat(r.names()).containsExactly("ok");
    }

    @Test
    void aLaterFileReplacesAnEarlierMapping() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        r.load(file("a.loct", "keep_newest = " + PKG + "Throws\n"));
        r.load(file("b.loct", "keep_newest = " + PKG + "KeepNewest\n"));
        assertThat(r.entries())
                .singleElement()
                .extracting(CompactorRegistry.Entry::className)
                .isEqualTo(PKG + "KeepNewest");
    }

    @Test
    void verifyFindsMissingWrongAndUnconstructableClasses() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        r.load(
                file(
                        "s.loct",
                        "missing = com.nowhere.Gone\nwrong = "
                                + PKG
                                + "NotAStrategy\nnoctor = "
                                + PKG
                                + "NoDefaultConstructor\nfine = "
                                + PKG
                                + "KeepNewest\n"));
        var problems = r.verify();
        assertThat(problems).hasSize(3);
        assertThat(problems.get(0).error())
                .as("a class that is not on the class path may be built later")
                .isFalse();
        assertThat(problems.get(1).error()).isTrue();
        assertThat(problems.get(1).message()).contains("neither");
        assertThat(problems.get(2).message()).contains("constructor");
    }

    @Test
    void creatingAnUnmappedOrBrokenStrategyIsAnError() throws Exception {
        CompactorRegistry r = new CompactorRegistry();
        r.load(file("s.loct", "gone = com.nowhere.Gone\n"));
        assertThatThrownBy(() -> r.create("gone", Map.of(), null))
                .hasMessageContaining("cannot create")
                .hasMessageContaining("s.loct line 1");
        assertThatThrownBy(() -> r.create("other", Map.of(), null))
                .hasMessageContaining("no strategy named");
    }

    @Test
    void aHostRegistersStrategiesInCode() {
        CompactorRegistry r = new CompactorRegistry();
        ContextCompactor mine = new Strategies.KeepNewest();
        r.register("mine", mine);
        r.register("provided", new Strategies.WithOptions());
        assertThat(r.names()).containsExactly("mine", "provided");
        assertThat(r.create("mine", Map.of(), null)).isSameAs(mine);
        assertThat(r.create("provided", Map.of("a", "b"), null)).isNotNull();
        assertThatThrownBy(() -> r.register("drop_oldest", mine)).hasMessageContaining("built-in");
        assertThatThrownBy(() -> r.register("Bad Name", mine)).hasMessageContaining("not a usable");
    }

    @Test
    void anUnreadableFileIsAProblemNotAnException() {
        var problems = new CompactorRegistry().load(dir.resolve("absent.loct"));
        assertThat(problems)
                .singleElement()
                .extracting(CompactorRegistry.Problem::error)
                .isEqualTo(true);
    }
}
