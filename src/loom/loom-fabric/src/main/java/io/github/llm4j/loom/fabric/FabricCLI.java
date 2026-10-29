package io.github.llm4j.loom.fabric;

import io.github.llm4j.loom.inspect.tls.Tls;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fabric}: set the server up ({@code init}, {@code tls init}), manage who may report ({@code
 * clients}), and run it ({@code serve}). The master key and the passwords never come from the
 * command line, only from a file, an environment variable or a prompt, so they do not end up in a
 * shell history or a process listing.
 */
@Command(
        name = "fabric",
        mixinStandardHelpOptions = true,
        versionProvider = FabricCLI.Version.class,
        description = "The server many Loom processes report to.",
        subcommands = {
            FabricCLI.Init.class,
            FabricCLI.Clients.class,
            FabricCLI.Serve.class,
            FabricCLI.TlsCmd.class,
            FabricCLI.Admin.class
        })
public final class FabricCLI implements Callable<Integer> {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Where the commands read the environment and write, so tests can drive them. */
    static final class Env {
        PrintStream out = System.out;
        PrintStream err = System.err;
        Function<String, String> getenv = System::getenv;
        Runnable block =
                () -> {
                    try {
                        Thread.currentThread().join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v =
                    FabricCLI.class.getPackage() == null
                            ? null
                            : FabricCLI.class.getPackage().getImplementationVersion();
            return new String[] {"fabric " + (v == null ? "dev" : v)};
        }
    }

    @CommandLine.Spec CommandLine.Model.CommandSpec spec;

    private final Env env;

    public FabricCLI() {
        this(new Env());
    }

    FabricCLI(Env env) {
        this.env = env;
    }

    public static void main(String[] args) {
        try {
            io.github.llm4j.loom.inspect.CoreMatch.require(
                    "loom-fabric", FabricCLI.class, io.github.llm4j.loom.inspect.CoreMatch.class);
        } catch (IllegalStateException mismatch) {
            System.err.println("Error: " + mismatch.getMessage());
            System.exit(2);
        }
        System.exit(commandLine().execute(args));
    }

    /**
     * The command line as picocli sees it: what the documentation may name, for checks that it
     * still exists.
     */
    static CommandLine commandLine() {
        return new CommandLine(new FabricCLI(), new Factory(new Env()));
    }

    /** Runs with the given environment (tests). */
    static int run(Env env, String... args) {
        return new CommandLine(new FabricCLI(env), new Factory(env))
                .setOut(new java.io.PrintWriter(env.out, true))
                .setErr(new java.io.PrintWriter(env.err, true))
                .execute(args);
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(env.out);
        return 0;
    }

    private static final class Factory implements CommandLine.IFactory {
        private final Env env;

        Factory(Env env) {
            this.env = env;
        }

        @Override
        public <K> K create(Class<K> cls) throws Exception {
            try {
                return cls.getDeclaredConstructor(Env.class).newInstance(env);
            } catch (NoSuchMethodException e) {
                return CommandLine.defaultFactory().create(cls);
            }
        }
    }

    // ------------------------------------------------------------------------------ shared

    abstract static class Base implements Callable<Integer> {
        @Option(
                names = "--data",
                required = true,
                paramLabel = "dir",
                description = "The data directory (the vault, the history).")
        Path data;

        @Option(
                names = "--master-key-file",
                paramLabel = "file",
                description =
                        "A file holding the master key (default: master.key in the data directory, or LOOM_FABRIC_MASTER_KEY).")
        Path masterKeyFile;

        final Env env;

        Base(Env env) {
            this.env = env;
        }

        String masterKey() throws IOException {
            if (masterKeyFile != null) return Files.readString(masterKeyFile).trim();
            String fromEnv = env.getenv.apply("LOOM_FABRIC_MASTER_KEY");
            if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
            Path f = data.resolve("master.key");
            if (Files.isRegularFile(f)) return Files.readString(f).trim();
            throw new IOException(
                    "no master key: give --master-key-file, set LOOM_FABRIC_MASTER_KEY, or keep master.key in "
                            + data);
        }

        Vault vault() throws IOException {
            Path file = data.resolve("vault.json");
            if (!Files.isRegularFile(file))
                throw new IOException(
                        "no vault in " + data + ": run 'fabric init --data " + data + "' first");
            return Vault.open(file, masterKey());
        }

        Integer failed(Exception e) {
            env.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    // ------------------------------------------------------------------------------ init

    @Command(
            name = "init",
            description = "Creates the data directory, the vault and the admin password.")
    static final class Init extends Base {
        @Option(
                names = "--admin-password-env",
                paramLabel = "VAR",
                description =
                        "Take the admin password from this environment variable (default LOOM_FABRIC_ADMIN_PASSWORD); asked for when it is not set.")
        String passwordEnv = "LOOM_FABRIC_ADMIN_PASSWORD";

        Init(Env env) {
            super(env);
        }

        @Override
        public Integer call() {
            try {
                Files.createDirectories(data);
                String password = env.getenv.apply(passwordEnv);
                if (password == null || password.isBlank()) password = prompt();
                if (password.length() < 12) {
                    env.err.println("Error: the admin password must be at least 12 characters.");
                    return 2;
                }
                boolean generated =
                        masterKeyFile == null
                                && (env.getenv.apply("LOOM_FABRIC_MASTER_KEY") == null);
                String master;
                if (generated) {
                    master = Vault.newMasterKey();
                    Path mk = data.resolve("master.key");
                    Files.writeString(mk, master + "\n");
                    restrict(mk);
                } else {
                    master = masterKey();
                }
                Vault v = Vault.create(data.resolve("vault.json"), master);
                v.put(
                        Vault.ADMIN,
                        io.github.llm4j.loom.inspect.http.PasswordHash.hash(
                                password)); // a hash, not the password
                env.out.println("Fabric is set up in " + data);
                if (generated)
                    env.out.println(
                            "The master key is in "
                                    + data.resolve("master.key")
                                    + ". Keep it somewhere other than the vault's own backup; without it the vault cannot be read.");
                env.out.println(
                        "Next: fabric clients add <name> --data "
                                + data
                                + "      (prints that process's key once)");
                env.out.println("      fabric serve --data " + data + " --port 9090");
                return 0;
            } catch (IOException | RuntimeException e) {
                return failed(e);
            }
        }

        private String prompt() throws IOException {
            Console c = System.console();
            if (c == null)
                throw new IOException(
                        "no admin password: set "
                                + passwordEnv
                                + " (there is no terminal to ask on)");
            char[] a = c.readPassword("Admin password: ");
            char[] b = c.readPassword("Again: ");
            if (!Arrays.equals(a, b)) throw new IOException("the two passwords differ");
            return new String(a);
        }
    }

    static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
    }

    // ------------------------------------------------------------------------------ admin

    @Command(
            name = "admin",
            description = "Administrator tasks.",
            subcommands = {SetPassword.class})
    static final class Admin implements Callable<Integer> {
        Admin(Env env) {}

        @Override
        public Integer call() {
            return 0;
        }
    }

    @Command(
            name = "set-password",
            description =
                    "Replaces the admin password (ends nothing already logged in; restart to end sessions).")
    static final class SetPassword extends Base {
        SetPassword(Env env) {
            super(env);
        }

        @Override
        public Integer call() {
            try {
                String p = env.getenv.apply("LOOM_FABRIC_ADMIN_PASSWORD");
                if (p == null || p.length() < 12) {
                    env.err.println(
                            "Error: set LOOM_FABRIC_ADMIN_PASSWORD to the new password (at least 12 characters).");
                    return 2;
                }
                vault().put(Vault.ADMIN, io.github.llm4j.loom.inspect.http.PasswordHash.hash(p));
                env.out.println("The admin password was replaced.");
                return 0;
            } catch (IOException | RuntimeException e) {
                return failed(e);
            }
        }
    }

    // ------------------------------------------------------------------------------ clients

    @Command(
            name = "clients",
            description = "Who may report: add, list, revoke.",
            subcommands = {ClientAdd.class, ClientList.class, ClientRevoke.class})
    static final class Clients implements Callable<Integer> {
        Clients(Env env) {}

        @Override
        public Integer call() {
            return 0;
        }
    }

    @Command(name = "add", description = "Creates a client and prints its key once.")
    static final class ClientAdd extends Base {
        @Parameters(
                index = "0",
                paramLabel = "name",
                description = "The process's name (what it gives as --observe-name).")
        String name;

        ClientAdd(Env env) {
            super(env);
        }

        @Override
        public Integer call() {
            try {
                if (!NAME.matcher(name).matches()) {
                    env.err.println(
                            "Error: a name may use letters, digits, '.', '_' and '-' (up to 64).");
                    return 2;
                }
                Vault v = vault();
                if (v.get("client." + name) != null) {
                    env.err.println(
                            "Error: there is already a client named '"
                                    + name
                                    + "'. Revoke it first to issue a new key.");
                    return 2;
                }
                String key = Vault.newClientKey();
                v.put("client." + name, key);
                env.out.println("Client '" + name + "' created. Its key (shown once):");
                env.out.println();
                env.out.println("    " + key);
                env.out.println();
                env.out.println(
                        "On that process's machine, store it in its secret store, then point weave at this server:");
                env.out.println(
                        "    weave secrets set LOOM_FABRIC_CLIENT_KEY --secrets keys.store");
                env.out.println(
                        "    weave run flow.loom --secrets keys.store --observe-fabric https://<this server> --observe-name "
                                + name);
                return 0;
            } catch (IOException | RuntimeException e) {
                return failed(e);
            }
        }
    }

    @Command(name = "list", description = "Lists clients (names only, never keys).")
    static final class ClientList extends Base {
        ClientList(Env env) {
            super(env);
        }

        @Override
        public Integer call() {
            try {
                List<String> names = new ArrayList<>();
                for (String n : vault().names())
                    if (n.startsWith("client.")) names.add(n.substring(7));
                if (names.isEmpty())
                    env.out.println(
                            "No clients yet. Add one with: fabric clients add <name> --data "
                                    + data);
                names.forEach(env.out::println);
                return 0;
            } catch (IOException | RuntimeException e) {
                return failed(e);
            }
        }
    }

    @Command(name = "revoke", description = "Removes a client's key; its next request is refused.")
    static final class ClientRevoke extends Base {
        @Parameters(index = "0", paramLabel = "name")
        String name;

        ClientRevoke(Env env) {
            super(env);
        }

        @Override
        public Integer call() {
            try {
                if (!vault().remove("client." + name)) {
                    env.err.println("Error: no client named '" + name + "'.");
                    return 2;
                }
                env.out.println(
                        "Client '" + name + "' revoked. A running server refuses it at once.");
                return 0;
            } catch (IOException | RuntimeException e) {
                return failed(e);
            }
        }
    }

    // ------------------------------------------------------------------------------ serve

    @Command(name = "serve", description = "Runs the server.")
    static final class Serve extends Base {
        @Option(
                names = "--port",
                defaultValue = "9090",
                description = "The port (default ${DEFAULT-VALUE}).")
        int port;

        @Option(
                names = "--bind",
                defaultValue = "127.0.0.1",
                description =
                        "The address to listen on (default ${DEFAULT-VALUE}). Another address needs TLS or --trust-proxy.")
        String bind;

        @Option(
                names = "--tls-keystore",
                paramLabel = "file",
                description =
                        "A PKCS12 keystore with the server's key and certificate (as written by 'fabric tls init').")
        Path keystore;

        @Option(
                names = "--tls-password-file",
                paramLabel = "file",
                description = "The keystore's password.")
        Path keystorePassword;

        @Option(
                names = "--tls-cert",
                paramLabel = "pem",
                description = "The server's certificate chain, PEM.")
        Path certPem;

        @Option(
                names = "--tls-key",
                paramLabel = "pem",
                description = "The server's private key, PKCS#8 PEM.")
        Path keyPem;

        @Option(
                names = "--trust-proxy",
                description = "Listen without TLS because a reverse proxy in front terminates it.")
        boolean trustProxy;

        @Option(
                names = "--retention-days",
                defaultValue = "30",
                description = "Keep history this many days (default ${DEFAULT-VALUE}).")
        int days;

        @Option(
                names = "--retention-size",
                defaultValue = "2GB",
                paramLabel = "size",
                description = "Keep at most this much history (default ${DEFAULT-VALUE}).")
        String size;

        @Option(
                names = "--allow-capture",
                description =
                        "Let a signed-in administrator create a test from a run and try a change on it. Off by default: it shows run content.")
        boolean allowCapture;

        @Option(
                names = "--capture-store",
                paramLabel = "dir",
                description =
                        "Where the runs' journals are, as this server sees them. Without it the page shows the 'weave capture' command to run where the store is.")
        Path captureStore;

        @Option(
                names = "--capture-folder",
                paramLabel = "dir",
                description =
                        "Also write each captured case into this folder (not a source repository).")
        Path captureFolder;

        Serve(Env env) {
            super(env);
        }

        @Override
        public Integer call() {
            try {
                Vault vault = vault();
                SSLContext tls = null;
                String pin = null;
                if (keystore != null) {
                    char[] pw =
                            Files.readString(
                                            keystorePassword == null
                                                    ? keystore.resolveSibling("server.password")
                                                    : keystorePassword)
                                    .trim()
                                    .toCharArray();
                    tls = Tls.serverContext(keystore, pw);
                    pin = Tls.pinOfKeystore(keystore, pw);
                } else if (certPem != null || keyPem != null) {
                    if (certPem == null || keyPem == null)
                        throw new IllegalArgumentException("--tls-cert and --tls-key go together");
                    tls = Tls.serverContextFromPem(certPem, keyPem);
                    pin = Tls.pinOfPem(certPem);
                }
                if (vault.get(Vault.ADMIN) == null)
                    env.err.println(
                            "Warning: no admin password is set, so nobody can log in. Run 'fabric admin set-password'.");
                FabricServer server =
                        new FabricServer(
                                new FabricServer.Settings(
                                        data,
                                        bind,
                                        port,
                                        tls,
                                        trustProxy,
                                        days,
                                        bytes(size),
                                        new FabricLoop.Settings(
                                                allowCapture, captureStore, captureFolder)),
                                vault,
                                env.out);
                server.start();
                server.applyRetention();
                String scheme = tls != null ? "https" : "http";
                env.out.println(
                        "Fabric is listening on "
                                + scheme
                                + "://"
                                + (bind.contains(":") ? "[" + bind + "]" : bind)
                                + ":"
                                + server.port());
                if (pin != null)
                    env.out.println("Server key pin (for --observe-pin): sha256:" + pin);
                Runtime.getRuntime().addShutdownHook(new Thread(server::close));
                env.block.run();
                server.close();
                return 0;
            } catch (IOException | RuntimeException e) {
                return failed(e);
            }
        }

        static long bytes(String text) {
            var m = Pattern.compile("(?i)\\s*(\\d+)\\s*(b|kb|mb|gb)?\\s*").matcher(text);
            if (!m.matches())
                throw new IllegalArgumentException(
                        "--retention-size takes a size like 500MB or 2GB, got '" + text + "'");
            long n = Long.parseLong(m.group(1));
            String u = m.group(2) == null ? "b" : m.group(2).toLowerCase();
            return switch (u) {
                case "kb" -> n << 10;
                case "mb" -> n << 20;
                case "gb" -> n << 30;
                default -> n;
            };
        }
    }

    // ------------------------------------------------------------------------------ tls

    @Command(
            name = "tls",
            description = "TLS for the server.",
            subcommands = {TlsInit.class})
    static final class TlsCmd implements Callable<Integer> {
        TlsCmd(Env env) {}

        @Override
        public Integer call() {
            return 0;
        }
    }

    @Command(
            name = "init",
            description =
                    "Creates a small private CA and a server certificate: ca.pem (give it to processes with --observe-ca), server.p12 and its password.")
    static final class TlsInit implements Callable<Integer> {
        @Option(
                names = "--host",
                required = true,
                description =
                        "The name processes use to reach the server, for example fabric.example.com.")
        String host;

        @Option(
                names = "--also",
                split = ",",
                description = "More names or addresses to put in the certificate.")
        List<String> also = new ArrayList<>();

        @Option(
                names = "--out",
                required = true,
                paramLabel = "dir",
                description = "Where to write the files.")
        Path out;

        private final Env env;

        TlsInit(Env env) {
            this.env = env;
        }

        @Override
        public Integer call() {
            try {
                Tls.Created c = Tls.createCa(out, host, also);
                env.out.println("Written to " + out + ":");
                env.out.println(
                        "  "
                                + c.caPem().getFileName()
                                + "      the CA certificate: give it to each process as --observe-ca (public, not secret)");
                env.out.println(
                        "  "
                                + c.serverKeystore().getFileName()
                                + "   the server's key and certificate; start with: fabric serve --tls-keystore "
                                + c.serverKeystore());
                env.out.println(
                        "  " + c.passwordFile().getFileName() + "  its password (keep private)");
                env.out.println(
                        "Server key pin, if you would rather pin than trust the CA: sha256:"
                                + c.pin());
                return 0;
            } catch (RuntimeException e) {
                env.err.println("Error: " + e.getMessage());
                return 2;
            }
        }
    }
}
