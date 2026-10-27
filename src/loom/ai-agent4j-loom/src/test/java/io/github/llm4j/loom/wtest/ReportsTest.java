package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * R30 of spec 11 and the hostile-text principle: a name or a failure message may hold markup,
 * control characters, very long lines and right-to-left text, and no report format breaks.
 */
class ReportsTest {

    static final String HOSTILE =
            "<script>alert(\"x\")</script> & ' ]]> \u0000\u0007 end ‮evil‬ " + "y".repeat(5000);

    static Reports.Run run() {
        var ok =
                new TestRunner.CaseResult(
                        "t.yaml",
                        "fine & dandy",
                        true,
                        12,
                        List.of(),
                        List.of(),
                        List.of("smoke"),
                        List.of());
        var bad =
                new TestRunner.CaseResult(
                        "t.yaml",
                        "name <b>with</b> \"quotes\" # hash\nnewline",
                        false,
                        7,
                        List.of("route: " + HOSTILE, "calls.X: expected 1, got 2"),
                        List.of("note \u0001"),
                        List.of(),
                        List.of());
        return new Reports.Run("main.loom", List.of(ok, bad), null, 1234, null);
    }

    static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
    }

    @Test
    void theJunitFileIsValidXmlWhateverTheTextHolds() throws Exception {
        String xml = Reports.junit(run());
        Document d = parse(xml);
        Element suite = (Element) d.getElementsByTagName("testsuite").item(0);
        assertThat(suite.getAttribute("tests")).isEqualTo("2");
        assertThat(suite.getAttribute("failures")).isEqualTo("1");
        assertThat(suite.getAttribute("time")).isEqualTo("1.234");
        var cases = d.getElementsByTagName("testcase");
        assertThat(cases.getLength()).isEqualTo(2);
        Element bad = (Element) cases.item(1);
        assertThat(bad.getAttribute("name")).contains("<b>with</b>").contains("\"quotes\"");
        Element failure = (Element) bad.getElementsByTagName("failure").item(0);
        assertThat(failure.getTextContent())
                .contains("<script>alert(\"x\")</script> & ' ]]>")
                .contains("calls.X");
        assertThat(failure.getTextContent()).doesNotContain("\u0000").doesNotContain("\u0007");
        assertThat(((Element) cases.item(0)).getElementsByTagName("failure").getLength()).isZero();
    }

    @Test
    void xmlEscapingDropsWhatXmlCannotHold() {
        assertThat(Reports.xml("a\u0000b\u0008c￿d")).isEqualTo("abcd");
        assertThat(Reports.xml("<&>\"'")).isEqualTo("&lt;&amp;&gt;&quot;&apos;");
        assertThat(Reports.xml("tab\tline\nreturn\r")).isEqualTo("tab\tline\nreturn\r");
        assertThat(Reports.xml("emoji 😀")).isEqualTo("emoji 😀");
        assertThat(Reports.xml("lone \uD83D surrogate")).doesNotContain("\uD83D");
    }

    @Test
    void theJsonReportIsVersionedAndCarriesEveryField() throws Exception {
        JsonNode j = new ObjectMapper().readTree(Reports.json(run()));
        assertThat(j.get("version").asInt()).isEqualTo(Reports.JSON_VERSION);
        assertThat(j.get("script").asText()).isEqualTo("main.loom");
        assertThat(j.get("summary").get("passed").asInt()).isEqualTo(1);
        assertThat(j.get("summary").get("failed").asInt()).isEqualTo(1);
        assertThat(j.get("summary").get("millis").asLong()).isEqualTo(1234);
        JsonNode bad = j.get("cases").get(1);
        assertThat(bad.get("passed").asBoolean()).isFalse();
        assertThat(bad.get("failures")).hasSize(2);
        assertThat(bad.get("failures").get(0).asText()).contains("<script>").contains("yyyy");
        assertThat(j.get("cases").get(0).get("tags").get(0).asText()).isEqualTo("smoke");
        assertThat(j.has("coverage")).isFalse();
    }

    @Test
    void theTapReportKeepsOneLinePerCaseWhateverTheNameHolds() {
        String tap = Reports.tap(run());
        assertThat(tap).startsWith("TAP version 13\n1..2\n");
        assertThat(tap.lines().filter(l -> l.startsWith("ok ") || l.startsWith("not ok ")).count())
                .isEqualTo(2);
        assertThat(tap)
                .contains("ok 1 - fine & dandy")
                .contains("not ok 2 - name <b>with</b> \"quotes\" \\# hash newline");
        assertThat(tap.lines().noneMatch(l -> l.contains("\r"))).isTrue();
    }

    @Test
    void theTextReportHasOneLinePerCaseAndASummary() {
        String text = Reports.text(run());
        assertThat(text).contains("ok    fine & dandy  (12 ms)").contains("FAIL  name <b>with</b>");
        assertThat(text).endsWith("1 passed, 1 failed in 1.2 s\n");
    }
}
