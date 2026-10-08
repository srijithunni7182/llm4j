package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CheckMissingFileTest {

    @TempDir Path dir;

    @Test
    void aWrongPathSaysThereIsNoFileThereAndTheFullPath() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            CommandLine cli = WeaveCLI.commandLine();
            cli.setOut(new java.io.PrintWriter(out, true));
            assertThat(cli.execute("check", dir.resolve("src/src/main.loom").toString(), "--no-env", "--no-env-file")).isEqualTo(2);
        } finally {
            System.setOut(old);
        }
        assertThat(out.toString()).contains("there is no file at").contains("src/src/main.loom");
    }
}
