package io.github.llm4j.tools;

import io.github.llm4j.agent.tool.Isolation;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a program in a container through the {@code docker} or {@code podman} command line (D8):
 * isolation {@code container}, sharing the host's kernel, so not a boundary against a kernel
 * exploit. The workspace is the only mount (protected paths inside it read only); the container's
 * own file system is read only with a small {@code /tmp}; every capability is dropped; it runs as
 * the workspace's owner. {@code network: none} gives it no network; {@code allow} puts it on a
 * network with no route out and starts an {@link EgressProxy} on that network's gateway, its only
 * way out; {@code open} uses the runtime's default network. An image is never pulled unless the
 * script says {@code pull: true}.
 */
public final class ContainerSandbox implements SandboxBackend {

    /** Where the workspace appears inside the container. */
    static final String MOUNT = "/workspace";

    static final Wait PROBE_TIMEOUT = Wait.ofSeconds(10);
    static final Wait COMMAND_TIMEOUT = Wait.ofMinutes(10);
    static final String TMP_SIZE = "64m";

    private final String runtime;
    private final String image;
    private final boolean pull;

    /** {@code runtime} is {@code docker} or {@code podman}; {@code image} is used as named. */
    public ContainerSandbox(String runtime, String image, boolean pull) {
        this.runtime = runtime;
        this.image = image;
        this.pull = pull;
    }

    private record Wait(long millis) {
        static Wait ofSeconds(long s) {
            return new Wait(s * 1000);
        }

        static Wait ofMinutes(long m) {
            return new Wait(m * 60_000);
        }
    }

    @Override
    public Isolation isolation() {
        return Isolation.CONTAINER;
    }

    @Override
    public Set<Limit> enforces() {
        return EnumSet.allOf(Limit.class);
    }

    /** Whether the runtime answers; checked once per runtime and process. */
    @Override
    public String probe() {
        try {
            Done d = command(List.of(runtime, "version", "--format", "{{.Server.Version}}"), Map.of(), PROBE_TIMEOUT);
            if (d.exit() == 0) return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return "the container runtime "
                + runtime
                + " is not available: install Docker or Podman and start it (isolation: container never falls back to a weaker level)";
    }

    /**
     * The runtime's arguments for one run (without the runtime itself): a unit test reads the mounts
     * from here (G46). {@code network} is the network's name; {@code proxy} the proxy's address, or
     * null.
     */
    List<String> arguments(SandboxRequest r, String network, String proxy, Integer publish) {
        List<String> a = new ArrayList<>();
        a.add("run");
        a.add(publish == null ? "--rm" : "--detach");
        if (publish != null) a.add("--rm");
        a.add("--name");
        a.add(r.name());
        a.add("--network");
        a.add(network);
        a.add("--cap-drop");
        a.add("ALL");
        a.add("--security-opt");
        a.add("no-new-privileges");
        a.add("--read-only");
        a.add("--tmpfs");
        a.add("/tmp:rw,size=" + (r.limits().diskBytes() == null ? TMP_SIZE : r.limits().diskBytes()));
        String owner = owner(r.workspace());
        if (owner != null) {
            a.add("--user");
            a.add(owner);
        }
        if (r.limits().memoryBytes() != null) {
            a.add("--memory");
            a.add(String.valueOf(r.limits().memoryBytes()));
        }
        if (r.limits().pids() != null) {
            a.add("--pids-limit");
            a.add(String.valueOf(r.limits().pids()));
        }
        a.add("--volume");
        a.add(r.workspace().toAbsolutePath().normalize() + ":" + MOUNT);
        for (String p : r.protect()) {
            a.add("--volume");
            a.add(r.workspace().toAbsolutePath().normalize().resolve(p) + ":" + MOUNT + "/" + p + ":ro");
        }
        a.add("--workdir");
        a.add(r.cwd().isEmpty() ? MOUNT : MOUNT + "/" + r.cwd());
        a.add("--env");
        a.add("HOME=/tmp");
        for (String name : r.env().keySet()) {
            a.add("--env");
            a.add(name); // the value travels in the runtime's own environment, never on its command line
        }
        if (proxy != null)
            for (String name : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
                a.add("--env");
                a.add(name + "=http://" + proxy);
            }
        if (publish != null) {
            a.add("--publish");
            a.add("127.0.0.1::" + publish);
        }
        if (r.stdin() != null && publish == null) a.add("--interactive");
        a.add(image);
        a.add(r.program());
        a.addAll(r.args());
        return a;
    }

    /** The workspace owner's user and group ids, so files the program writes stay the owner's. */
    static String owner(Path workspace) {
        try {
            Object uid = Files.getAttribute(workspace, "unix:uid");
            Object gid = Files.getAttribute(workspace, "unix:gid");
            return uid + ":" + gid;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public SandboxResult run(SandboxRequest request) throws IOException, InterruptedException {
        image();
        long started = System.nanoTime();
        Network net = network(request);
        try {
            List<String> command = new ArrayList<>(List.of(runtime));
            command.addAll(arguments(request, net.name(), net.proxyAddress(), null));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(request.env());
            Process process = builder.start();
            CompletableFuture<HeadAndTail> out = ProcessSandbox.drain(process.getInputStream()::transferTo, request.limits().maxOutput());
            CompletableFuture<HeadAndTail> err = ProcessSandbox.drain(process.getErrorStream()::transferTo, request.limits().maxOutput());
            try (OutputStream in = process.getOutputStream()) {
                if (request.stdin() != null) in.write(request.stdin().getBytes(StandardCharsets.UTF_8));
            } catch (IOException closedEarly) {
                // the program did not read its input
            }
            boolean finished = process.waitFor(request.limits().timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                command(List.of(runtime, "rm", "--force", request.name()), Map.of(), PROBE_TIMEOUT);
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
                    Isolation.CONTAINER,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            net.close();
        }
    }

    @Override
    public boolean canServe() {
        return true;
    }

    @Override
    public Served serve(SandboxRequest request, int port) throws IOException, InterruptedException {
        image();
        if (request.network().mode() == SandboxRequest.SandboxNetwork.Mode.ALLOW)
            throw new ToolRefusal("serve takes network: none or open; a served program cannot keep its proxy for its whole life");
        String net = request.network().mode() == SandboxRequest.SandboxNetwork.Mode.NONE ? "none" : "bridge";
        if (net.equals("none"))
            throw new ToolRefusal("a served program with network: none cannot be reached; give the sandbox network: open, or run it inside one call");
        List<String> command = new ArrayList<>(List.of(runtime));
        command.addAll(arguments(request, net, null, port));
        Done started = command(command, request.env(), COMMAND_TIMEOUT);
        if (started.exit() != 0) throw new ToolRefusal("the container did not start: " + Limits.excerpt(started.err(), 300));
        Done mapped = command(List.of(runtime, "port", request.name(), port + "/tcp"), Map.of(), PROBE_TIMEOUT);
        String line = mapped.out().lines().findFirst().orElse("");
        int colon = line.lastIndexOf(':');
        if (mapped.exit() != 0 || colon < 0) {
            stop(request.name());
            throw new ToolRefusal("the runtime did not publish port " + port);
        }
        return new Served(request.name(), Integer.parseInt(line.substring(colon + 1).strip()));
    }

    @Override
    public void stop(String handle) throws IOException, InterruptedException {
        command(List.of(runtime, "rm", "--force", handle), Map.of(), PROBE_TIMEOUT);
    }

    @Override
    public boolean running(String handle) throws IOException, InterruptedException {
        Done d = command(List.of(runtime, "inspect", "--format", "{{.State.Running}}", handle), Map.of(), PROBE_TIMEOUT);
        return d.exit() == 0 && d.out().strip().equals("true");
    }

    /** The image, present or pulled when the script allowed it; else a refusal naming the pull. */
    private void image() throws IOException, InterruptedException {
        if (command(List.of(runtime, "image", "inspect", image), Map.of(), PROBE_TIMEOUT).exit() == 0) return;
        if (!pull)
            throw new ToolRefusal(
                    "the image " + image + " is not here; pull it first (" + runtime + " pull " + image + "), or set pull: true");
        Done d = command(List.of(runtime, "pull", image), Map.of(), COMMAND_TIMEOUT);
        if (d.exit() != 0) throw new ToolRefusal("could not pull " + image + ": " + Limits.excerpt(d.err(), 300));
    }

    /** The network a run is on, and the proxy that is its only way out for {@code allow}. */
    private record Network(String name, EgressProxy proxy, String proxyAddress, Runnable remove) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (proxy != null) proxy.close();
            remove.run();
        }
    }

    private Network network(SandboxRequest r) throws IOException, InterruptedException {
        switch (r.network().mode()) {
            case NONE:
                return new Network("none", null, null, () -> {});
            case OPEN:
                return new Network("bridge", null, null, () -> {});
            default:
                break;
        }
        String name = "llm4j-" + r.name() + "-net";
        Done created = command(List.of(runtime, "network", "create", "--internal", name), Map.of(), PROBE_TIMEOUT);
        if (created.exit() != 0) throw new ToolRefusal("could not make a network for the sandbox: " + Limits.excerpt(created.err(), 300));
        Runnable remove =
                () -> {
                    try {
                        command(List.of(runtime, "network", "rm", name), Map.of(), PROBE_TIMEOUT);
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                };
        try {
            String gateway =
                    command(List.of(runtime, "network", "inspect", "--format", "{{(index .IPAM.Config 0).Gateway}}", name), Map.of(), PROBE_TIMEOUT)
                            .out()
                            .strip();
            EgressProxy proxy =
                    EgressProxy.start(InetAddress.getByName(gateway), 0, r.network().hosts(), NetPolicy.Resolver.SYSTEM, EgressProxy.Connector.DIRECT);
            return new Network(name, proxy, gateway + ":" + proxy.port(), remove);
        } catch (IOException | RuntimeException e) {
            remove.run();
            throw new ToolRefusal("the egress proxy could not start on this platform, so network: allow is refused here: " + e.getMessage());
        }
    }

    private record Done(int exit, String out, String err) {}

    private static Done command(List<String> command, Map<String, String> env, Wait timeout)
            throws IOException, InterruptedException {
        ProcessBuilder b = new ProcessBuilder(command);
        b.environment().putAll(new LinkedHashMap<>(env));
        Process p = b.start();
        CompletableFuture<HeadAndTail> out = ProcessSandbox.drain(p.getInputStream()::transferTo, 64 * 1024);
        CompletableFuture<HeadAndTail> err = ProcessSandbox.drain(p.getErrorStream()::transferTo, 64 * 1024);
        p.getOutputStream().close();
        if (!p.waitFor(timeout.millis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            return new Done(-1, "", "timed out");
        }
        return new Done(p.exitValue(), out.join().text(), err.join().text());
    }
}
