package io.github.llm4j.loom.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The real processes, network and clock behind {@code weave fabric}. */
final class SystemFabricHost implements FabricHost {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    private static final long DEFAULT_RUN_LIMIT_SECONDS = 60;
    private static final long STOP_LIMIT_SECONDS = 10;
    private static final long OUTPUT_GRACE_MILLIS = 1000;
    private static final int DRAIN_BUFFER_BYTES = 4096;
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;

    private final java.util.function.Function<String, String> env;
    private final Path home;
    private final Path cwd;
    private final long runLimitSeconds;

    SystemFabricHost() {
        this(System::getenv, Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath());
    }

    SystemFabricHost(java.util.function.Function<String, String> env, Path home, Path cwd) {
        this(env, home, cwd, DEFAULT_RUN_LIMIT_SECONDS);
    }

    SystemFabricHost(
            java.util.function.Function<String, String> env,
            Path home,
            Path cwd,
            long runLimitSeconds) {
        this.runLimitSeconds = runLimitSeconds;
        this.env = env;
        this.home = home;
        this.cwd = cwd;
    }

    @Override
    public Optional<Path> jar() {
        return FabricJar.find(env, home, cwd);
    }

    @Override
    public String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Override
    public Result run(List<String> command, Map<String, String> extraEnvironment) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putAll(extraEnvironment);
            Process process = builder.start();
            // the output is read on a thread of its own: reading it here would wait for the process
            // to end and the limit below would never apply
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> drain(process, captured), "fabric-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(runLimitSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
            // a descendant that inherited the pipe keeps it open after the process is gone: wait a
            // moment for the rest of the output, then take what there is
            reader.join(OUTPUT_GRACE_MILLIS);
            if (reader.isAlive()) {
                process.getInputStream().close();
                reader.join(OUTPUT_GRACE_MILLIS);
            }
            String output = textOf(captured);
            return finished
                    ? new Result(process.exitValue(), output)
                    : new Result(1, output + "\n(stopped after " + runLimitSeconds + " seconds)");
        } catch (IOException e) {
            return new Result(1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(1, "interrupted");
        }
    }

    private static void drain(Process process, ByteArrayOutputStream into) {
        byte[] buffer = new byte[DRAIN_BUFFER_BYTES];
        try (var in = process.getInputStream()) {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                synchronized (into) {
                    into.write(buffer, 0, n);
                }
            }
        } catch (IOException e) {
            // the stream was closed on purpose, or the process is gone: what was read is what there is
        }
    }

    private static String textOf(ByteArrayOutputStream captured) {
        synchronized (captured) {
            return captured.toString(StandardCharsets.UTF_8);
        }
    }

    @Override
    public long startBackground(List<String> command, Path log) {
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        // the server holds only the hash of the admin password
        builder.environment().remove(FabricLauncher.PASSWORD_VARIABLE);
        try {
            return builder.start().pid();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not start the Fabric server: " + e.getMessage(), e);
        }
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    /**
     * An http address is asked for its health page. An https one is only asked whether something
     * accepts connections: a certificate check cannot succeed against a server's own address, and
     * certificate checks are never turned off.
     */
    @Override
    public boolean answers(String baseUrl, int seconds) {
        for (int i = 0; i < Math.max(1, seconds); i++) {
            if (answersOnce(baseUrl)) return true;
            if (i + 1 < seconds)
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
        }
        return false;
    }

    private boolean answersOnce(String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (uri.getScheme().equals("https")) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MILLIS);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        try {
            HttpResponse<Void> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(uri.resolve("/api/health"))
                                            .timeout(HEALTH_TIMEOUT)
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Override
    public boolean looksLikeFabric(long pid) {
        return ProcessHandle.of(pid)
                .flatMap(p -> p.info().commandLine())
                .map(c -> c.contains("fabric") && c.contains(" serve"))
                .orElse(false);
    }

    @Override
    public void stop(long pid) {
        ProcessHandle.of(pid)
                .ifPresent(
                        p -> {
                            p.destroy();
                            try {
                                p.onExit().get(STOP_LIMIT_SECONDS, TimeUnit.SECONDS);
                            } catch (java.util.concurrent.TimeoutException
                                    | java.util.concurrent.ExecutionException e) {
                                // still alive: the caller kills it
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
    }

    @Override
    public void kill(long pid) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }

    @Override
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public String environment(String name) {
        return env.apply(name);
    }
}
