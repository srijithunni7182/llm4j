package io.github.llm4j.loom.cli;

import java.nio.file.Path;

/**
 * Who is acting, for records that say who approved: given, else git's user.email, else the OS user.
 */
final class Identity {

    private Identity() {}

    static String of(String given, Path dir) {
        if (given != null && !given.isBlank()) return given;
        try {
            Process p =
                    new ProcessBuilder("git", "config", "user.email")
                            .directory(dir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !out.isBlank()) return out;
        } catch (Exception e) {
            // no git here: the OS user will do
        }
        return System.getProperty("user.name", "unknown");
    }
}
