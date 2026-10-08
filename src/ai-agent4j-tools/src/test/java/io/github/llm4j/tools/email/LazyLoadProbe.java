package io.github.llm4j.tools.email;

import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Run in its own JVM by {@link LazyLoadingTest}: does a script's tool setup load the mail library? */
public final class LazyLoadProbe {

    private LazyLoadProbe() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("probe");
        Declared declared = new Declared(Map.of("HOOK", "https://hooks.example.com/abc/def"), dir);
        // Everything a script without an email tool does:
        declared.problems("tool Hook { use: webhook  url: env.HOOK }");
        declared.create("tool Notes { use: file  root: \".\" }", new RecordingEffects());
        declared.create("tool Api { use: http  base_url: \"https://api.example.com\" }", new RecordingEffects());
        // Declaring (validating) an email tool is still not using one:
        declared.problems("tool Mail { use: email  host: \"smtp.example.com\"  from: \"a@example.com\"  to: \"b@example.com\" }");
        if (args.length > 0 && args[0].equals("send")) {
            declared.create("tool Mail { use: email  outbox: \"o\"  from: \"a@example.com\"  to: \"b@example.com\" }", new RecordingEffects())
                    .execute(Map.of("subject", "s", "body", "b"));
        }
        System.out.println("PROBE-DONE");
    }
}
