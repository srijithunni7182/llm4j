package io.github.llm4j.loom.cli;

import java.io.File;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * The developer's keys: a {@code .env} file beside the script, ignored by git, read when you run or evaluate the project. {@code --env-file} names another
 * file; {@code --no-env-file} reads none. A variable already set in the shell wins over the file. For a deployed application use the secret store instead.
 */
final class EnvFileOptions {

    @Option(names = "--env-file", paramLabel = "<file>",
            description = "Read keys from this file (NAME=value lines) instead of the .env beside the script. For development: keep it out of git.")
    File file;

    @Option(names = "--no-env-file", description = "Do not read a .env file, even if one is beside the script.")
    boolean none;

    /** The environment with the keys of the file added, or null (after saying why) when the file is refused. */
    WeaveEnv apply(WeaveEnv env, Path script) {
        if (none) return env;
        Path dir = script.toAbsolutePath().normalize().getParent();
        Path chosen = file != null ? file.toPath() : dir.resolve(".env");
        if (file == null && !java.nio.file.Files.isRegularFile(chosen)) return env;
        return EnvFile.apply(env, chosen, file != null);
    }
}
