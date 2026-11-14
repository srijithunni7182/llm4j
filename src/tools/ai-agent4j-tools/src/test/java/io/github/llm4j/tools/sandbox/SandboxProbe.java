package io.github.llm4j.tools.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/** A tiny program the sandbox backends run in tests, so no program outside the JDK is needed. */
public final class SandboxProbe {

    private SandboxProbe() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "env" -> System.out.println(new TreeSet<>(System.getenv().keySet()));
            case "var" -> System.out.println(System.getenv(args[1]));
            case "big" -> {
                StringBuilder b = new StringBuilder("START");
                b.append("x".repeat(Integer.parseInt(args[1])));
                b.append("END");
                System.out.print(b);
                System.err.print("err");
            }
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "stdin" -> System.out.print(new String(System.in.readAllBytes(), StandardCharsets.UTF_8).toUpperCase());
            case "cwd" -> System.out.println(Path.of("").toAbsolutePath());
            case "write" -> Files.writeString(Path.of(args[1]), "written");
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
