package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** why and the narrator describe a remote step and an ask, and never repeat what a remote said beyond a short clip. */
class RemoteExplainTest {

    @Test
    void whyDescribesWhatARemoteReturnedBySizeOnly() {
        var entry = new RunJournal.Entry("remote", Map.of("text", "SECRET-ISH TEXT FROM OUTSIDE", "data", List.of(Map.of("a", 1)), "files", List.of()));
        String summary = Why.summary(entry);
        assertThat(summary).contains("the remote agent replied").contains("28 chars").contains("1 data part").contains("0 files").doesNotContain("SECRET-ISH");
        assertThat(Why.summary(new RunJournal.Entry("remote_failed", "rejected: not our product"))).startsWith("the remote agent failed");
        assertThat(Why.summary(new RunJournal.Entry("ask", "USD"))).startsWith("answered");
        assertThat(Why.summary(new RunJournal.Entry("ask_failed", "no answer"))).startsWith("the question was not answered");
    }
}
