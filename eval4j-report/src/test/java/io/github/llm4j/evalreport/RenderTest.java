package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.evalreport.cli.Main;
import io.github.llm4j.evalreport.render.DataEmbed;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderTest {

    static Path store(Path tmp) throws Exception {
        Path src = Path.of("src/test/resources/examples/minimal");
        for (String run : new String[] {"previous", "candidate"}) {
            Path dst = tmp.resolve("runs").resolve(run);
            Files.createDirectories(dst);
            try (var files = Files.list(src.resolve(run))) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(
                            f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return tmp;
    }

    @Test
    void cliRendersEveryOutputAndMakesNoNetworkReference(@TempDir Path tmp) throws Exception {
        Path root = store(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code =
                Main.run(
                        new String[] {"render", root.toString()},
                        new PrintStream(out),
                        new PrintStream(out));
        assertThat(code).as(out.toString()).isZero();
        Path r = root.resolve("report");
        for (String f :
                new String[] {
                    "index.html", "summary.md", "junit.xml", "evaluations.csv", "compare.md"
                }) {
            assertThat(r.resolve(f)).exists();
        }
        String html = Files.readString(r.resolve("index.html"));
        // RPT / security: nothing may be fetched from the network
        assertThat(html).doesNotContainPattern(Pattern.compile("(src|href)=\"https?://"));
        assertThat(html)
                .doesNotContain("fonts.googleapis")
                .doesNotContain("@import")
                .doesNotContain("fetch(")
                .doesNotContain("XMLHttpRequest");
        assertThat(html).contains("eval4j-data");
        assertThat(Files.readString(r.resolve("summary.md")))
                .contains("does not make it")
                .doesNotContain("PASS ")
                .doesNotContain("release-ready");
    }

    @Test
    void embeddedJsonCannotCloseTheScriptElement() {
        String hostile = "{\"t\":\"</script><script>alert(1)</script><!--  \"}";
        String esc = DataEmbed.escape(hostile);
        assertThat(esc).doesNotContain("</script").doesNotContain("<!--").doesNotContain("<");
    }

    @Test
    void exitCodesAreStable(@TempDir Path tmp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        assertThat(Main.run(new String[] {}, new PrintStream(o), new PrintStream(o))).isEqualTo(2);
        assertThat(
                        Main.run(
                                new String[] {"render", tmp.toString()},
                                new PrintStream(o),
                                new PrintStream(o)))
                .isEqualTo(3);
    }
}
