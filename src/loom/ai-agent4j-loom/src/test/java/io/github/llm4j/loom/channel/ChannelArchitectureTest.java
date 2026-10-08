package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The channel is replaceable: only the Telegram class, its configuration and the factory that picks it may name Telegram (spec loom-remote-answers R6.1, R1.2). */
class ChannelArchitectureTest {

    static final Path MAIN = Path.of("src/main/java/io/github/llm4j/loom");

    @Test
    @Tag("RA-V6.3")
    void nothingButTheTelegramClassItsConfigurationAndTheFactoryNamesTelegram() throws IOException {
        Set<String> allowed = Set.of("TelegramChannel.java", "ChannelConfig.java", "Channels.java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/channel/") || f.toString().contains("/execution/") || f.toString().contains("/runtime/") || f.toString().contains("/autonomy/")
                            || f.getFileName().toString().equals("AnswerCommands.java") || f.getFileName().toString().equals("Runs.java"))
                    .filter(f -> !allowed.contains(f.getFileName().toString()))
                    .filter(f -> read(f).toLowerCase().contains("telegram"))
                    .map(f -> MAIN.relativize(f).toString()).toList();
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @Tag("RA-V1.2")
    void everyPlaceThatAsksAPersonPassesWhatItKnowsAboutTheQuestion() {
        for (String file : List.of("execution/Decider.java", "execution/ApprovalGate.java", "execution/Rewinder.java", "execution/HarnessExecutor.java")) {
            String source = read(MAIN.resolve(file));
            long calls = source.lines().filter(l -> l.contains("promptHuman(")).count();
            long withHints = source.lines().filter(l -> l.contains("promptHuman(") && l.contains("Hints")).count();
            assertThat(withHints).as(file + " calls promptHuman " + calls + " times").isEqualTo(calls);
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
