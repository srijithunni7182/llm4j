package io.github.llm4j.tools.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LazyLoadingTest {

    private static List<String> classLoadLines(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-verbose:class", "-cp", System.getProperty("java.class.path"),
                LazyLoadProbe.class.getName(), mode).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) lines.add(line);
        }
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(lines).as("the probe ran to the end").anyMatch(l -> l.contains("PROBE-DONE"));
        return lines;
    }

    private static boolean loadedMailLibrary(List<String> lines) {
        return lines.stream().anyMatch(l -> l.contains("jakarta.mail.") || l.contains("org.eclipse.angus.mail"));
    }

    @Test
    @Tag("V5.9")
    void aScriptWithoutAnEmailToolNeverLoadsTheMailLibrary() throws Exception {
        assertThat(loadedMailLibrary(classLoadLines("plain"))).isFalse();
    }

    @Test
    @Tag("V5.9")
    void theProbeWouldNoticeIfItWasLoaded() throws Exception {
        assertThat(loadedMailLibrary(classLoadLines("send"))).as("sending mail does load it").isTrue();
    }
}
