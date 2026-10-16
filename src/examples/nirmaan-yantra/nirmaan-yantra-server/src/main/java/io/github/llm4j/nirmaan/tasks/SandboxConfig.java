package io.github.llm4j.nirmaan.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Where and how a build, test or run command executes.
 *
 * <ul>
 *   <li>{@code local} (the default): started directly on this machine, in the project's folder, with a clean environment and a
 *       private tool home ({@code HOME} is not the real one). This keeps secrets out of the child's environment and keeps the build from
 *       reading the real {@code ~/.ssh} through {@code ~}; it does <b>not</b> stop code that uses an absolute path. It is a convenience,
 *       not a jail.</li>
 *   <li>{@code docker} or {@code podman}: each command runs in a throwaway container that sees only the project folder and the tool
 *       home, as an unprivileged user, with no capabilities, a memory, CPU and process limit, and (if you choose) no network.</li>
 * </ul>
 * Settings (environment variables): {@code NIRMAAN_SANDBOX} (local, docker, podman), {@code NIRMAAN_SANDBOX_IMAGE} (default
 * {@code nirmaan-sandbox:latest}, built from {@code sandbox/Dockerfile}), {@code NIRMAAN_SANDBOX_NETWORK} (default {@code bridge};
 * {@code none} for a build that needs no downloads), {@code NIRMAAN_SANDBOX_MEMORY} (4g), {@code NIRMAAN_SANDBOX_CPUS} (2),
 * {@code NIRMAAN_TOOL_HOME} (a folder, or {@code per-project}), {@code NIRMAAN_REQUIRE_CONTAINER=true} (refuse to run locally at all).
 */
public record SandboxConfig(String mode, String image, String network, String memory, String cpus, String toolHome, boolean requireContainer) {

    public static final String CONTAINER_HOME = "/home/nirmaan";
    private static final List<String> RUNTIMES = List.of("docker", "podman");
    /** The only variables of this process the child may see. Nothing that holds a credential is on the list. */
    private static final List<String> PASSED_ENV = List.of("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR", "JAVA_HOME", "RUSTUP_HOME");

    public SandboxConfig {
        if (!mode.equals("local") && !RUNTIMES.contains(mode)) throw new IllegalArgumentException("NIRMAAN_SANDBOX must be local, docker or podman, not \"" + mode + "\"");
    }

    public static SandboxConfig local() {
        return new SandboxConfig("local", "nirmaan-sandbox:latest", "bridge", "4g", "2", "", false);
    }

    public static SandboxConfig fromEnvironment() {
        return from(System::getenv);
    }

    static SandboxConfig from(Function<String, String> env) {
        return new SandboxConfig(
                or(env.apply("NIRMAAN_SANDBOX"), "local"),
                or(env.apply("NIRMAAN_SANDBOX_IMAGE"), "nirmaan-sandbox:latest"),
                or(env.apply("NIRMAAN_SANDBOX_NETWORK"), "bridge"),
                or(env.apply("NIRMAAN_SANDBOX_MEMORY"), "4g"),
                or(env.apply("NIRMAAN_SANDBOX_CPUS"), "2"),
                or(env.apply("NIRMAAN_TOOL_HOME"), ""),
                "true".equalsIgnoreCase(or(env.apply("NIRMAAN_REQUIRE_CONTAINER"), "false")));
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public boolean container() {
        return !mode.equals("local");
    }

    /** The folder tools use as their home (caches, config): shared by every project unless {@code per-project} was chosen. */
    Path toolHome(Path workspace, String project) throws IOException {
        Path home = toolHome.equals("per-project") ? Sandbox.project(workspace, project).resolve("home")
                : toolHome.isBlank() ? workspace.resolve(".toolhome") : Path.of(toolHome).toAbsolutePath().normalize();
        return Files.createDirectories(home);
    }

    /** A command ready to start, and what to do if it has to be stopped. */
    record Launch(ProcessBuilder builder, Runnable stop) {}

    Launch launch(List<String> words, Path codeRoot, Path workspace, String project) throws IOException {
        Path home = toolHome(workspace, project);
        if (!container()) {
            ProcessBuilder builder = new ProcessBuilder(words).directory(codeRoot.toFile()).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            Map<String, String> parent = new LinkedHashMap<>(env);
            env.clear();
            for (String name : PASSED_ENV) if (parent.containsKey(name)) env.put(name, parent.get(name));
            env.putAll(toolEnvironment(home.toString()));
            return new Launch(builder, () -> {});
        }
        String name = "nirmaan-" + project + "-" + Long.toHexString(System.nanoTime());
        List<String> command = containerCommand(name, words, codeRoot, home, id("-u"), id("-g"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        String runtime = mode;
        return new Launch(builder, () -> {
            try {
                new ProcessBuilder(runtime, "rm", "-f", name).redirectErrorStream(true).start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                // nothing more can be done from here; the container has a --rm and a time limit of its own
            }
        });
    }

    /** The environment tools get: a home of their own, and every cache pointed into it. */
    static Map<String, String> toolEnvironment(String home) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home);
        env.put("USER", "nirmaan");
        env.put("XDG_CACHE_HOME", home + "/.cache");
        env.put("XDG_CONFIG_HOME", home + "/.config");
        env.put("GOPATH", home + "/go");
        env.put("CARGO_HOME", home + "/.cargo");
        // The JVM takes user.home from the account database, not from $HOME, so Maven would still read the real ~/.m2: say so explicitly.
        env.put("JAVA_TOOL_OPTIONS", "-Duser.home=" + home);
        // Python trusts a cached .pyc when the source's size and whole-second modification time match, so a fix that rewrites a file
        // to the same size within the same second would run the old code. Fix cycles are exactly that fast: never write the cache.
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        return env;
    }

    /** The full container command line. A pure function of its inputs, so it is tested without a container runtime. */
    List<String> containerCommand(String name, List<String> words, Path codeRoot, Path home, String uid, String gid) {
        List<String> command = new ArrayList<>(List.of(mode, "run", "--rm", "--name", name,
                "--network", network, "--memory", memory, "--cpus", cpus, "--pids-limit", "1024",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", uid + ":" + gid,
                "-v", codeRoot + ":/work", "-v", home + ":" + CONTAINER_HOME, "-w", "/work"));
        for (Map.Entry<String, String> e : toolEnvironment(CONTAINER_HOME).entrySet()) {
            command.add("-e");
            command.add(e.getKey() + "=" + e.getValue());
        }
        command.add(image);
        command.addAll(words);
        return command;
    }

    private static String id(String flag) {
        try {
            Process process = new ProcessBuilder("id", flag).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return out.matches("\\d+") ? out : "1000";
        } catch (IOException | InterruptedException e) {
            return "1000";
        }
    }
}
