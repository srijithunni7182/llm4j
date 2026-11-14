package io.github.llm4j.tools;

import io.github.llm4j.agent.tool.Isolation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Runs a program as a child process on this machine (D8): isolation {@code process}, which is not
 * isolation in a strong sense. The environment is cleared, the working directory is inside the
 * workspace, and time and output are bounded; nothing else is. It cannot keep a program off the
 * network or within memory, so a tool refuses those settings with this backend at load.
 */
public final class ProcessSandbox implements SandboxBackend {

    private final Function<String, Path> programs;

    /** Programs are found on this machine's {@code PATH}. */
    public ProcessSandbox() {
        this(ProcessSandbox::onPath);
    }

    /** Programs are found by {@code programs} (a name to the file to run): a seam for tests. */
    public ProcessSandbox(Function<String, Path> programs) {
        this.programs = programs;
    }

    static Path onPath(String name) {
        String path = System.getenv("PATH");
        if (path != null)
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path candidate = Path.of(dir).resolve(name);
                if (java.nio.file.Files.isRegularFile(candidate) && java.nio.file.Files.isExecutable(candidate))
                    return candidate.toAbsolutePath().normalize();
            }
        throw new ToolRefusal(name + " was not found on this machine's PATH");
    }

    @Override
    public Isolation isolation() {
        return Isolation.PROCESS;
    }

    @Override
    public Set<Limit> enforces() {
        return Set.of(Limit.TIME, Limit.OUTPUT);
    }

    @Override
    public String probe() {
        return null;
    }

    @Override
    public SandboxResult run(SandboxRequest request) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(programs.apply(request.program()).toString());
        command.addAll(request.args());
        ProcessBuilder builder = new ProcessBuilder(command).directory(request.workspace().resolve(request.cwd()).toFile());
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        env.put("LANG", "C.UTF-8");
        env.put("TZ", "UTC");
        env.put("HOME", request.workspace().toString());
        env.putAll(request.env());
        long started = System.nanoTime();
        Process process = builder.start();
        CompletableFuture<HeadAndTail> out = drain(process.getInputStream()::transferTo, request.limits().maxOutput());
        CompletableFuture<HeadAndTail> err = drain(process.getErrorStream()::transferTo, request.limits().maxOutput());
        try (OutputStream in = process.getOutputStream()) {
            if (request.stdin() != null) in.write(request.stdin().getBytes(StandardCharsets.UTF_8));
        } catch (IOException closedEarly) {
            // the program did not read its input; that is its business
        }
        boolean finished = process.waitFor(request.limits().timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        HeadAndTail o = out.join();
        HeadAndTail e = err.join();
        return new SandboxResult(
                finished ? process.exitValue() : -1,
                !finished,
                o.text(),
                e.text(),
                o.cut() + e.cut() > 0,
                o.cut() + e.cut(),
                Isolation.PROCESS,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /** A sink for a stream: a method reference to its {@code transferTo}. */
    @FunctionalInterface
    interface Source {
        long transferTo(OutputStream out) throws IOException;
    }

    static CompletableFuture<HeadAndTail> drain(Source source, long cap) {
        return CompletableFuture.supplyAsync(
                () -> {
                    HeadAndTail kept = new HeadAndTail(cap);
                    try {
                        source.transferTo(
                                new OutputStream() {
                                    @Override
                                    public void write(int b) {
                                        kept.add((byte) b);
                                    }

                                    @Override
                                    public void write(byte[] b, int off, int len) {
                                        for (int i = off; i < off + len; i++) kept.add(b[i]);
                                    }
                                });
                    } catch (IOException closed) {
                        // the process ended
                    }
                    return kept;
                });
    }
}
