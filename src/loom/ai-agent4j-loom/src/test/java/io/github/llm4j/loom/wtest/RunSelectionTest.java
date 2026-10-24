package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R1, R25, R29 of spec 11: tags select cases, the order can be shuffled and repeated from a seed,
 * and cases that run side by side share nothing and give the results a serial run gives.
 */
class RunSelectionTest {

    @TempDir Path dir;

    String yamlOf(int n) {
        return yaml(n);
    }

    static String yaml(int n) {
        StringBuilder b = new StringBuilder("script: ../main.loom\nworkflow: Main\ncases:\n");
        for (int i = 1; i <= n; i++) {
            b.append("  - name: case ").append(String.format("%02d", i)).append('\n');
            b.append("    tags: [ ")
                    .append(i % 3 == 0 ? "slow" : "fast")
                    .append(i % 2 == 0 ? ", even" : "")
                    .append(" ]\n");
            b.append("    input: { message: m").append(i).append(" }\n");
            b.append(
                            "    models:\n      Intent: { kind: order_status, urgent: 'no' }\n      Replier: 'reply ")
                    .append(i)
                    .append("'\n");
            b.append("    expect:\n      result: 'reply ")
                    .append(i)
                    .append("'\n      calls: { Replier: 1, Intent: 1 }\n");
        }
        return b.toString();
    }

    TestRunner.Suite suite(int n) throws Exception {
        TestRunner.Suite s = Wtest.load(dir, Wtest.TRIAGE, yaml(n));
        assertThat(s.problems()).isEmpty();
        return s;
    }

    static List<String> names(List<TestRunner.CaseResult> r) {
        return r.stream().map(TestRunner.CaseResult::name).toList();
    }

    @Test
    void tagsSelectAndExcludeCases() throws Exception {
        TestRunner.Suite s = suite(12);
        var slow =
                new TestRunner.Options(
                        null, false, false, List.of("slow"), List.of(), 1, false, null);
        assertThat(names(TestRunner.run(s, slow)))
                .containsExactly("case 03", "case 06", "case 09", "case 12");
        var notEven =
                new TestRunner.Options(
                        null, false, false, List.of(), List.of("even"), 1, false, null);
        assertThat(TestRunner.run(s, notEven)).hasSize(6);
        var both =
                new TestRunner.Options(
                        null, false, false, List.of("slow"), List.of("even"), 1, false, null);
        assertThat(names(TestRunner.run(s, both))).containsExactly("case 03", "case 09");
        var either =
                new TestRunner.Options(
                        null, false, false, List.of("slow", "even"), List.of(), 1, false, null);
        assertThat(TestRunner.run(s, either)).hasSize(8);
    }

    @Test
    void aSeedRepeatsTheOrderAndDifferentSeedsDiffer() throws Exception {
        TestRunner.Suite s = suite(12);
        var a = new TestRunner.Options(null, false, false, List.of(), List.of(), 1, true, 7L);
        var b = new TestRunner.Options(null, false, false, List.of(), List.of(), 1, true, 8L);
        List<String> first = names(TestRunner.run(s, a));
        assertThat(names(TestRunner.run(s, a))).isEqualTo(first);
        assertThat(names(TestRunner.run(s, b))).isNotEqualTo(first);
        assertThat(first)
                .containsExactlyInAnyOrderElementsOf(
                        names(TestRunner.run(s, TestRunner.Options.defaults())));
        assertThat(first).isNotEqualTo(names(TestRunner.run(s, TestRunner.Options.defaults())));
    }

    @Test
    void casesRunningAtOnceShareNothingAndMatchASerialRun() throws Exception {
        TestRunner.Suite s = suite(24);
        List<TestRunner.CaseResult> serial = TestRunner.run(s, TestRunner.Options.defaults());
        var parallel =
                new TestRunner.Options(null, false, false, List.of(), List.of(), 8, false, null);
        List<TestRunner.CaseResult> many = TestRunner.run(s, parallel);
        assertThat(names(many)).isEqualTo(names(serial));
        assertThat(many).allMatch(TestRunner.CaseResult::passed);
        for (int i = 0; i < serial.size(); i++) {
            assertThat(many.get(i).steps())
                    .as(serial.get(i).name())
                    .isEqualTo(serial.get(i).steps());
            assertThat(many.get(i).failures()).isEqualTo(serial.get(i).failures());
        }
    }

    @Test
    void aFailureInOneCaseDoesNotChangeAnother() throws Exception {
        String text = yaml(6).replace("result: 'reply 3'", "result: 'wrong'");
        TestRunner.Suite s = Wtest.load(dir, Wtest.TRIAGE, text);
        var parallel =
                new TestRunner.Options(null, false, false, List.of(), List.of(), 6, false, null);
        List<TestRunner.CaseResult> r = TestRunner.run(s, parallel);
        List<String> failed = new ArrayList<>();
        r.stream().filter(x -> !x.passed()).forEach(x -> failed.add(x.name()));
        assertThat(failed).containsExactly("case 03");
    }

    @Test
    void failFastWithSeveralWorkersStopsStartingNewCases() throws Exception {
        String text = yaml(30).replace("result: 'reply 1'", "result: 'wrong'");
        TestRunner.Suite s = Wtest.load(dir, Wtest.TRIAGE, text);
        var parallel =
                new TestRunner.Options(null, false, true, List.of(), List.of(), 2, false, null);
        List<TestRunner.CaseResult> r = TestRunner.run(s, parallel);
        assertThat(r).isNotEmpty();
        assertThat(r.size()).isLessThan(30);
        assertThat(r.get(0).passed()).isFalse();
    }
}
