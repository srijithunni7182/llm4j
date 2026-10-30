package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code weave fabric up | down | status}: one front door for a local Fabric server. It starts the
 * jar as a separate process and keeps a pid file beside the data, so a second command finds it.
 * The admin password is generated into a file only its owner can read and is never printed, put
 * on a command line or written to the log. Local use only: a deployment is {@code weave deploy}'s
 * job.
 */
final class FabricLauncher {

    /**
     * @param data the server's data folder
     * @param port the port to listen on
     * @param bind the address to listen on
     * @param extra more options for {@code serve}, such as {@code --tls-keystore}
     */
    record UpOptions(Path data, int port, String bind, List<String> extra) {
        UpOptions {
            extra = List.copyOf(extra);
        }
    }

    static final String PASSWORD_VARIABLE = "LOOM_FABRIC_ADMIN_PASSWORD";

    private static final int GENERATED_PASSWORD_LENGTH = 24;
    private static final int START_WAIT_SECONDS = 40;
    private static final int RUNNING_CHECK_SECONDS = 2;
    private static final int STATUS_CHECK_SECONDS = 3;
    private static final int LOG_TAIL_LINES = 15;
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern CLIENT_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int EXIT_USAGE = 2;
    private static final int EXIT_UNSAFE_BIND = 3;
    private static final int EXIT_INIT = 4;
    private static final int EXIT_SILENT = 5;

    private final FabricHost host;
    private final PrintStream out;
    private final PrintStream err;
    private final SecureRandom random = new SecureRandom();

    FabricLauncher(FabricHost host, PrintStream out, PrintStream err) {
        this.host = host;
        this.out = out;
        this.err = err;
    }

    // ---- up

    int up(UpOptions o) {
        Path jar;
        try {
            jar = host.jar().orElse(null);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_USAGE;
        }
        if (jar == null) {
            err.println(
                    "Error: the Fabric jar was not found. Run: weave tools fetch fabric (it asks first and checks the checksum), or set FABRIC_JAR=/path/to/loom-fabric-<version>-all.jar.");
            return EXIT_USAGE;
        }
        if (!loopback(o.bind()) && !protectedBeyondThisComputer(o.extra())) {
            err.println(
                    "Error: '"
                            + o.bind()
                            + "' is not this computer. Fabric listens beyond it only with TLS (--tls-keystore ... or --tls-cert ... --tls-key ...) or behind a proxy that terminates it (--trust-proxy). Ask the user which, then pass it after '--'.");
            return EXIT_UNSAFE_BIND;
        }
        Path data = o.data().toAbsolutePath().normalize();
        try {
            Files.createDirectories(data);
        } catch (IOException e) {
            err.println("Error: could not create " + data + ": " + e.getMessage());
            return EXIT_INIT;
        }
        String url = baseUrl(o);
        Path pidFile = data.resolve("fabric.pid");
        Long running = pid(pidFile);
        if (running != null
                && host.alive(running)
                && host.looksLikeFabric(running)
                && host.answers(url, RUNNING_CHECK_SECONDS)) {
            out.println(
                    "Fabric is already running: "
                            + url
                            + " (pid "
                            + running
                            + ", data "
                            + data
                            + "). Nothing changed.");
            return 0;
        }
        if (!Files.exists(data.resolve("vault.json"))) {
            int exit = initialise(jar, data);
            if (exit != 0) return exit;
        }
        return start(jar, o, data, url, pidFile);
    }

    private int initialise(Path jar, Path data) {
        String password = host.environment(PASSWORD_VARIABLE);
        Path generated = null;
        if (password == null || password.isEmpty()) {
            generated = data.resolve("admin-password.txt");
            password = randomPassword();
            try {
                writeOwnerOnly(generated, password + "\n");
            } catch (IOException e) {
                err.println(
                        "Error: could not write the admin password file "
                                + generated
                                + ": "
                                + e.getMessage()
                                + ". Nothing was started.");
                return EXIT_INIT;
            }
            out.println(
                    "A new admin password was written to "
                            + generated
                            + " (readable by you only). It was not printed.");
        }
        FabricHost.Result init =
                host.run(
                        List.of(host.java(), "-jar", jar.toString(), "init", "--data", data.toString()),
                        Map.of(PASSWORD_VARIABLE, password));
        if (init.exit() != 0) {
            if (generated != null) deleteQuietly(generated);
            err.println("Error: fabric init failed (is the password 12 or more characters?)");
            return EXIT_INIT;
        }
        out.println(
                "Initialised "
                        + data
                        + ": vault.json and master.key. master.key sits next to the vault for development; in production move it elsewhere (see the deployment chapter).");
        return 0;
    }

    private int start(Path jar, UpOptions o, Path data, String url, Path pidFile) {
        List<String> command = new ArrayList<>();
        command.addAll(
                List.of(
                        host.java(),
                        "-jar",
                        jar.toString(),
                        "serve",
                        "--data",
                        data.toString(),
                        "--port",
                        Integer.toString(o.port()),
                        "--bind",
                        o.bind()));
        command.addAll(o.extra());
        Path log = data.resolve("fabric.log");
        long pid = host.startBackground(command, log);
        try {
            Files.writeString(pidFile, pid + "\n");
        } catch (IOException e) {
            host.stop(pid);
            err.println("Error: could not write " + pidFile + ": " + e.getMessage());
            return EXIT_INIT;
        }
        if (!host.answers(url, START_WAIT_SECONDS)) {
            err.println(tail(log));
            host.stop(pid);
            deleteQuietly(pidFile);
            err.println(
                    "Error: Fabric did not answer within "
                            + START_WAIT_SECONDS
                            + " seconds (the log is above).");
            return EXIT_SILENT;
        }
        out.println(
                "Fabric is up: " + url + " (pid " + pid + ", data " + data + ", log " + log + ").");
        Path password = data.resolve("admin-password.txt");
        out.println(
                "Sign in on that page with the admin password"
                        + (Files.exists(password) ? " in " + password : "")
                        + ".");
        return 0;
    }

    // ---- down

    int down(Path dataFolder) {
        Path data = dataFolder.toAbsolutePath().normalize();
        Path pidFile = data.resolve("fabric.pid");
        Long pid = pid(pidFile);
        if (pid == null) {
            out.println("Fabric is not running (no pid file in " + data + "). Nothing to do.");
            return 0;
        }
        if (!host.alive(pid)) {
            out.println("Fabric was not running (a stale pid file was removed).");
        } else if (!host.looksLikeFabric(pid)) {
            out.println(
                    "Process "
                            + pid
                            + " is another program now, not Fabric: it was not stopped and the stale pid file was removed.");
        } else {
            host.stop(pid);
            if (host.alive(pid)) {
                host.kill(pid);
                out.println(
                        "Fabric (pid " + pid + ") did not stop in time and was killed.");
            } else {
                out.println("Fabric (pid " + pid + ") stopped.");
            }
        }
        deleteQuietly(pidFile);
        return 0;
    }

    // ---- status

    int status(Path dataFolder, int port) {
        return status(dataFolder, port, "127.0.0.1");
    }

    /** As {@link #status(Path, int)} for a server that was told to listen on {@code bind}. */
    int status(Path dataFolder, int port, String bind) {
        Path data = dataFolder.toAbsolutePath().normalize();
        if (!Files.isDirectory(data)) {
            err.println(
                    "No Fabric data directory at "
                            + data
                            + ": it has not been set up here. Run weave fabric up.");
            return 1;
        }
        Long pid = pid(data.resolve("fabric.pid"));
        if (pid != null && host.alive(pid) && host.looksLikeFabric(pid)) {
            String where = probeHost(bind) + ":" + port;
            if (host.answers("http://" + where, STATUS_CHECK_SECONDS)
                    || host.answers("https://" + where, 1)) {
                out.println("running: pid " + pid + ", port " + port + ", data " + data);
            } else {
                out.println(
                        "the process is alive (pid "
                                + pid
                                + ") but does not answer on port "
                                + port
                                + ": look at "
                                + data.resolve("fabric.log"));
            }
        } else {
            out.println("not running (data " + data + " exists). Start it with weave fabric up.");
        }
        if (Files.exists(data.resolve("vault.json"))) clients(data);
        return 0;
    }

    private void clients(Path data) {
        Optional<Path> jar;
        try {
            jar = host.jar();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (jar.isEmpty()) return;
        FabricHost.Result listed =
                host.run(
                        List.of(
                                host.java(),
                                "-jar",
                                jar.get().toString(),
                                "clients",
                                "list",
                                "--data",
                                data.toString()),
                        Map.of());
        List<String> names =
                listed.output().lines().map(String::strip).filter(this::isClientName).toList();
        out.println("clients that may report:");
        if (names.isEmpty()) out.println("  none yet: add one with fabric clients add <name>");
        Instant now = host.clock().instant();
        for (String name : names) {
            Optional<Instant> last = lastReport(data, name);
            out.println(
                    "  "
                            + name
                            + ": "
                            + last.map(t -> "last reported " + t + " (" + ago(t, now) + ")")
                                    .orElse("never reported"));
        }
    }

    private boolean isClientName(String line) {
        return CLIENT_NAME.matcher(line).matches();
    }

    /** The time of the newest message a client sent, read from the history Fabric keeps. */
    static Optional<Instant> lastReport(Path data, String client) {
        Path folder = data.resolve("history").resolve(client);
        if (!Files.isDirectory(folder)) return Optional.empty();
        List<Path> days;
        try (Stream<Path> files = Files.list(folder)) {
            days =
                    files.filter(p -> p.toString().endsWith(".jsonl"))
                            .sorted(java.util.Comparator.reverseOrder())
                            .toList();
        } catch (IOException e) {
            return Optional.empty();
        }
        for (Path day : days) {
            List<String> lines;
            try {
                lines = Files.readAllLines(day, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            for (int i = lines.size() - 1; i >= 0; i--) {
                Instant at = timeOf(lines.get(i));
                if (at != null) return Optional.of(at);
            }
        }
        return Optional.empty();
    }

    private static Instant timeOf(String line) {
        try {
            JsonNode n = JSON.readTree(line);
            return n != null && n.hasNonNull("t") ? Instant.parse(n.get("t").asText()) : null;
        } catch (IOException | RuntimeException e) {
            return null; // a line a crash cut off
        }
    }

    static String ago(Instant then, Instant now) {
        Duration d = Duration.between(then, now);
        if (d.isNegative() || d.toMinutes() < 1) return "just now";
        if (d.toMinutes() < 60) return plural(d.toMinutes(), "minute");
        if (d.toHours() < 48) return plural(d.toHours(), "hour");
        return plural(d.toDays(), "day");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }

    // ---- small helpers

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Whether the address is this computer. Only {@code localhost} and an IP literal count: a name
     * that merely begins with 127. is a host name anyone can point elsewhere. A literal is parsed
     * without any name lookup.
     */
    private static boolean loopback(String bind) {
        if (bind.equals("localhost")) return true;
        String address = bind.startsWith("[") && bind.endsWith("]") ? bind.substring(1, bind.length() - 1) : bind;
        if (!IPV4_LITERAL.matcher(address).matches() && !address.contains(":")) return false;
        try {
            return java.net.InetAddress.getByName(address).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private static boolean protectedBeyondThisComputer(List<String> extra) {
        return extra.stream()
                .anyMatch(
                        a ->
                                a.equals("--trust-proxy")
                                        || a.startsWith("--tls-cert")
                                        || a.startsWith("--tls-keystore"));
    }

    private static String baseUrl(UpOptions o) {
        boolean tls = o.extra().stream().anyMatch(a -> a.startsWith("--tls-"));
        return (tls ? "https" : "http") + "://" + probeHost(o.bind()) + ":" + o.port();
    }

    /**
     * Where to knock: the address the server was told to listen on. A server bound to one interface
     * does not answer on the loopback; one bound to every address does.
     */
    private static String probeHost(String bind) {
        String address = bind.startsWith("[") && bind.endsWith("]") ? bind.substring(1, bind.length() - 1) : bind;
        if (address.equals("0.0.0.0") || address.equals("::") || address.equals("*")) return "127.0.0.1";
        return address.contains(":") ? "[" + address + "]" : address;
    }

    private static Long pid(Path pidFile) {
        try {
            return Long.parseLong(Files.readString(pidFile).strip());
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private String randomPassword() {
        StringBuilder b = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++)
            b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /**
     * Creates the file already restricted to its owner, so there is no moment when others can read
     * it. Where the file system has no POSIX modes (Windows) the owner-only flags of the Java file
     * API are the best that can be done, and the folder's own rights apply.
     */
    private static void writeOwnerOnly(Path file, String text) throws IOException {
        // a leftover file (or a link, which is removed, never followed) is replaced; a folder is not ours
        if (!Files.isDirectory(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Files.deleteIfExists(file);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(
                    file,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
            java.io.File f = file.toFile();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join(
                    "\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a file that stays is reported by the next command's own checks
        }
    }
}
