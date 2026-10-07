package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** weave check --no-env lists the names a script needs; what the "not set yet" stand-in would break (an address, a number) is not reported as an error on top of that. */
class NoEnvStandInTest {

    @TempDir Path dir;

    private String check(Path script, String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            String[] args = new String[3 + extra.length];
            args[0] = "check";
            args[1] = script.toString();
            args[2] = "--format";
            System.arraycopy(extra, 0, args, 3, extra.length);
            WeaveCLI.commandLine().execute(args);
            return out.toString();
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void anAddressTakenFromAVariableThatIsNotSetIsListedAsNotSetNotReportedAsABadAddress() throws Exception {
        Path script = Files.writeString(dir.resolve("main.loom"), """
                agent N { model: "ollama/llama3" system: "s" tools: [Mail] }
                tool Mail { use: email  host: env.SMTP_HOST  username: env.SMTP_USER  password: env.SMTP_PASSWORD  from: "d@example.com"  to: env.DIGEST_TO }
                workflow Main() { delegate "send" to N -> sent_text
                    note "{sent_text}" }
                """);

        String json = check(script, "json", "--no-env");

        assertThat(json).contains("\"ok\" : true").contains("DIGEST_TO").contains("SMTP_HOST").contains("SMTP_PASSWORD").contains("SMTP_USER").doesNotContain("not-set-yet");
    }

    @Test
    void withoutNoEnvAnUnsetNameIsStillAnErrorSoACheckOnTheTargetMachineMeansSomething() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("DIGEST_TO") == null && System.getenv("SMTP_HOST") == null);
        Path script = Files.writeString(dir.resolve("main.loom"), """
                agent N { model: "ollama/llama3" system: "s" tools: [Mail] }
                tool Mail { use: email  host: env.SMTP_HOST  username: env.SMTP_USER  password: env.SMTP_PASSWORD  from: "d@example.com"  to: env.DIGEST_TO }
                workflow Main() { delegate "send" to N -> sent_text
                    note "{sent_text}" }
                """);

        assertThat(check(script, "json")).contains("\"ok\" : false").contains("environment variable SMTP_HOST is not set");
    }
}
