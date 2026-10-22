package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.travel.RunTravel;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run a host keeps in files is a run {@code weave} can read: same folder, same run.json, same
 * journal. V8.18.
 */
class HostRunsWeaveTest {

    @TempDir Path dir;

    @Tag("SB-V8.18")
    @Test
    void weaveReadsTheRunAHostStarted() throws Exception {
        Path script = dir.resolve("flow.loom");
        Files.writeString(
                script,
                """
                agent Writer { model: "test/model" system: "You are Writer." }
                workflow Main(topic) { delegate "write about {topic}" to Writer -> out }
                """);
        Path store = dir.resolve("store");
        LoomHost host =
                LoomHost.builder(
                                new LoomLoader().load(script.toString()),
                                new ToolRegistry(),
                                model ->
                                        new LLMClient() {
                                            @Override
                                            public LLMResponse chat(LLMRequest r) {
                                                return LLMResponse.builder()
                                                        .content(
                                                                "```json\n{\"thought\": \"t\", \"final_answer\": \"words\"}\n```")
                                                        .model(model)
                                                        .tokenUsage(10, 5, 15)
                                                        .build();
                                            }

                                            @Override
                                            public Stream<LLMResponse> chatStream(LLMRequest r) {
                                                return Stream.of(chat(r));
                                            }
                                        })
                        .persistence(Persistence.file(store))
                        .scriptRef(script.toAbsolutePath().toString())
                        .baseDir(dir)
                        .build();

        RunOutcome out =
                host.run(RunRequest.of("Main", Map.of("topic", "otters")).withRunId("host-run-1"));
        assertThat(out).isInstanceOf(RunOutcome.Completed.class);

        Path runDir = store.resolve("runs").resolve("host-run-1");
        RunSpec spec = RunSpec.read(runDir); // the file weave resume starts from
        assertThat(spec.workflow()).isEqualTo("Main");
        assertThat(spec.inputs()).containsEntry("topic", "otters");
        assertThat(spec.script()).isEqualTo(script.toAbsolutePath().toString());
        assertThat(Path.of(spec.store())).isEqualTo(store.toAbsolutePath().normalize());
        var timeline =
                RunTravel.timeline(
                        new FileRunJournal(
                                runDir.resolve("journal.json"))); // what weave timeline prints
        assertThat(timeline.steps()).isNotEmpty();
        assertThat(new FileRunJournal(runDir.resolve("journal.json")).all()).isNotEmpty();
    }
}
