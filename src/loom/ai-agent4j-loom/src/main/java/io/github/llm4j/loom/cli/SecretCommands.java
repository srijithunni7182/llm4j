package io.github.llm4j.loom.cli;

import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretNames;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave secrets create|set|list|remove|import-env|rekey}: manages an encrypted secret store
 * file. The file's location and master key are the consumer's: nothing here has a default, and
 * protecting the file (an ACL) is the consumer's job. A secret's value is never an argument (it
 * would land in shell history and the process list): it is typed at the console without echo, or
 * piped in with {@code --stdin}. Nothing prints a value.
 */
@Command(
        name = "secrets",
        mixinStandardHelpOptions = true,
        description =
                "Manages an encrypted secret store: create, set, list, remove, import-env, rekey. Values are never printed or taken from arguments.",
        subcommands = {
            SecretCommands.Create.class,
            SecretCommands.Set.class,
            SecretCommands.ListNames.class,
            SecretCommands.Remove.class,
            SecretCommands.ImportEnv.class,
            SecretCommands.Rekey.class
        })
final class SecretCommands implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /** Shared by the subcommands: the file is required here (it is optional on {@code run}). */
    private abstract static class Base implements Callable<Integer> {
        @Mixin SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() {
            return execute(WeaveEnv.system(), Prompts.console());
        }

        abstract Integer body(WeaveEnv env, Prompts prompts);

        Integer execute(WeaveEnv env, Prompts prompts) {
            if (secrets.file == null) {
                env.err().println("Error: --secrets <file> is required");
                return 2;
            }
            try {
                return body(env, prompts);
            } catch (SecretException | IllegalArgumentException e) {
                env.err().println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "create", description = "Creates an empty store. The file must not exist yet.")
    static class Create extends Base {
        @Option(
                names = "--iterations",
                description = "PBKDF2 iterations (default ${DEFAULT-VALUE}).")
        int iterations = EncryptedFileSecretStore.DEFAULT_ITERATIONS;

        @Override
        Integer body(WeaveEnv env, Prompts prompts) {
            MasterKey key = newKey(secrets, env, prompts, "Master key for the new store: ");
            try (EncryptedFileSecretStore store =
                    EncryptedFileSecretStore.create(
                            secrets.file.toPath(),
                            key,
                            EncryptedFileSecretStore.Options.defaults()
                                    .kdfIterations(iterations))) {
                env.out()
                        .println(
                                "Created "
                                        + secrets.file
                                        + ". Keep the master key safe, and restrict who can read this file.");
            } finally {
                key.destroy();
            }
            return 0;
        }
    }

    @Command(
            name = "set",
            description =
                    "Stores a secret. The value is asked for at the console (no echo), or read from standard input with --stdin.")
    static class Set extends Base {
        @Parameters(index = "0", description = "The secret's name, e.g. GEMINI_API_KEY.")
        String name;

        @Option(
                names = "--allow-host",
                paramLabel = "<host>",
                description =
                        "Only send this secret to this host (repeatable; *.example.com allowed).")
        List<String> allowHosts = new ArrayList<>();

        @Option(names = "--description", description = "A note shown by 'list'.")
        String description;

        @Option(
                names = "--stdin",
                description = "Read the value from standard input instead of the console.")
        boolean stdin;

        @Override
        Integer body(WeaveEnv env, Prompts prompts) {
            SecretNames.require(name);
            char[] value =
                    stdin
                            ? prompts.readLine().toCharArray()
                            : prompts.readSecret("Value for " + name + ": ");
            MasterKey key = secrets.masterKey(env, prompts, "Master key: ");
            try (EncryptedFileSecretStore store =
                    EncryptedFileSecretStore.open(secrets.file.toPath(), key)) {
                // a vendor's key is offered the vendor's hosts, so it cannot be sent anywhere else
                // by accident
                List<String> hosts =
                        allowHosts.isEmpty()
                                ? io.github.llm4j.provider.openai.Vendor.hostsOfKey(name)
                                : allowHosts;
                SecretMetadata meta =
                        SecretMetadata.allowing(hosts.toArray(new String[0]))
                                .withDescription(description);
                store.put(name, value, meta);
                env.out()
                        .println(
                                "Stored "
                                        + name
                                        + (hosts.isEmpty()
                                                ? " (any host: consider --allow-host)"
                                                : " for "
                                                        + String.join(", ", hosts)
                                                        + (allowHosts.isEmpty()
                                                                ? " (the vendor's host; --allow-host sets another, for a gateway)"
                                                                : "")));
            } finally {
                SecretOptions.wipe(value);
                key.destroy();
            }
            return 0;
        }
    }

    @Command(
            name = "list",
            description = "Lists the names, allowed hosts and update times. Never the values.")
    static class ListNames extends Base {
        @Override
        Integer body(WeaveEnv env, Prompts prompts) {
            MasterKey key = secrets.masterKey(env, prompts, "Master key: ");
            try (EncryptedFileSecretStore store =
                    EncryptedFileSecretStore.open(secrets.file.toPath(), key)) {
                PrintStream out = env.out();
                if (store.names().isEmpty()) out.println("(no secrets)");
                for (String n : store.names()) {
                    SecretMetadata m = store.metadata(n).orElse(SecretMetadata.NONE);
                    out.println(
                            n
                                    + "  hosts: "
                                    + (m.allowedHosts().isEmpty()
                                            ? "any"
                                            : String.join(", ", m.allowedHosts()))
                                    + (m.updatedAt() == null ? "" : "  updated: " + m.updatedAt())
                                    + (m.description() == null ? "" : "  " + m.description()));
                }
            } finally {
                key.destroy();
            }
            return 0;
        }
    }

    @Command(name = "remove", description = "Removes a secret.")
    static class Remove extends Base {
        @Parameters(index = "0", description = "The secret's name.")
        String name;

        @Override
        Integer body(WeaveEnv env, Prompts prompts) {
            MasterKey key = secrets.masterKey(env, prompts, "Master key: ");
            try (EncryptedFileSecretStore store =
                    EncryptedFileSecretStore.open(secrets.file.toPath(), key)) {
                if (!store.remove(name)) {
                    env.err().println("Error: no secret called " + name);
                    return 1;
                }
                env.out().println("Removed " + name);
            } finally {
                key.destroy();
            }
            return 0;
        }
    }

    @Command(
            name = "import-env",
            description =
                    "Copies secrets into the store: from variables in the environment, or with --from from a file of NAME=value lines (a template such as .env.example is then offered to be blanked). Values are never printed.")
    static class ImportEnv extends Base {
        @Parameters(
                arity = "0..*",
                description = "The variables to import (with --from: only these; default: every secret-named value in the file).")
        String[] variables = new String[0];

        @Option(
                names = "--from",
                paramLabel = "<file>",
                description = "Read NAME=value lines from this file instead of the environment.")
        java.io.File from;

        @Option(
                names = "--blank",
                description =
                        "With --from a template file (.example, .sample, .template): blank the imported values without asking. A real .env is never changed.")
        boolean blank;

        @Override
        Integer body(WeaveEnv env, Prompts prompts) {
            if (from == null && variables.length == 0) {
                env.err().println("Error: name the variables to import, or give --from <file>");
                return 2;
            }
            java.util.Map<String, String> source = null;
            java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of(variables));
            if (from != null) {
                try {
                    source =
                            EnvFile.parse(
                                    java.nio.file.Files.readString(from.toPath()),
                                    from.getName());
                } catch (java.io.IOException | IllegalArgumentException e) {
                    env.err().println("Error: " + e.getMessage());
                    return 2;
                }
                if (names.isEmpty()) names.addAll(EnvExampleCheck.namesWithValuesIn(from.toPath()));
                if (names.isEmpty()) {
                    env.err().println("Error: " + from.getName() + " has nothing to import: no secret-named name has a value");
                    return 1;
                }
            }
            MasterKey key = secrets.masterKey(env, prompts, "Master key: ");
            int missing = 0;
            java.util.List<String> imported = new java.util.ArrayList<>();
            try (EncryptedFileSecretStore store =
                    EncryptedFileSecretStore.open(secrets.file.toPath(), key)) {
                for (String v : names) {
                    String value = source != null ? source.get(v) : env.env().apply(v);
                    if (value == null || value.isBlank()) {
                        env.err().println("Error: " + v + " is not set");
                        missing++;
                        continue;
                    }
                    char[] chars = value.toCharArray();
                    try {
                        store.put(v, chars, store.metadata(v).orElse(SecretMetadata.NONE));
                    } finally {
                        SecretOptions.wipe(chars);
                    }
                    imported.add(v);
                    env.out().println("Imported " + v);
                }
            } finally {
                key.destroy();
            }
            if (from != null && !imported.isEmpty()) offerToBlank(env, prompts, imported);
            return missing == 0 ? 0 : 1;
        }

        /** A template is meant to be committed, so its values go once they are safe in the store. */
        private void offerToBlank(WeaveEnv env, Prompts prompts, java.util.List<String> imported) {
            String name = from.getName();
            boolean template =
                    name.equals(".env.example")
                            || name.endsWith(".example")
                            || name.endsWith(".sample")
                            || name.endsWith(".template");
            if (!template) return;
            if (!blank) {
                env.out()
                        .println(
                                name
                                        + " is meant to be committed. Blank the imported values in it? (yes/no)");
                String answer = prompts.readLine();
                if (answer == null || !answer.strip().equalsIgnoreCase("yes") && !answer.strip().equalsIgnoreCase("y"))
                    return;
            }
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(from.toPath());
                java.util.regex.Pattern line =
                        java.util.regex.Pattern.compile("^(\\s*(?:export\\s+)?)([A-Za-z_][A-Za-z0-9_]*)(\\s*=).*$");
                java.util.List<String> rewritten = new java.util.ArrayList<>();
                for (String raw : lines) {
                    java.util.regex.Matcher m = line.matcher(raw);
                    rewritten.add(
                            m.matches() && imported.contains(m.group(2))
                                    ? m.group(1) + m.group(2) + "="
                                    : raw);
                }
                java.nio.file.Files.write(from.toPath(), rewritten);
                env.out().println("Blanked the imported values in " + name + ".");
            } catch (java.io.IOException e) {
                env.err().println("Error: could not blank " + name + ": " + e.getMessage());
            }
        }
    }

    @Command(
            name = "rekey",
            description =
                    "Re-encrypts the store under a new master key (asked for at the console, or --new-key-file).")
    static class Rekey extends Base {
        @Option(
                names = "--new-key-file",
                paramLabel = "<file>",
                description = "A file whose first line is the new master key.")
        java.io.File newKeyFile;

        @Override
        Integer body(WeaveEnv env, Prompts prompts) {
            MasterKey old = secrets.masterKey(env, prompts, "Current master key: ");
            MasterKey fresh =
                    newKeyFile != null
                            ? MasterKey.fromFile(newKeyFile.toPath())
                            : confirmed(prompts, "New master key: ");
            try (EncryptedFileSecretStore store =
                    EncryptedFileSecretStore.open(secrets.file.toPath(), old)) {
                store.rekey(fresh);
                env.out().println("Re-encrypted " + secrets.file + " under the new master key.");
            } finally {
                old.destroy();
                fresh.destroy();
            }
            return 0;
        }
    }

    /** A key for a new store: from the chosen source, or asked for twice at the console. */
    private static MasterKey newKey(SecretOptions o, WeaveEnv env, Prompts prompts, String prompt) {
        if (o.keyFile != null || o.keyEnv != null) return o.masterKey(env, prompts, prompt);
        return confirmed(prompts, prompt);
    }

    private static MasterKey confirmed(Prompts prompts, String prompt) {
        char[] first = prompts.readSecret(prompt);
        char[] again = prompts.readSecret("Again: ");
        try {
            if (!Arrays.equals(first, again))
                throw new io.github.llm4j.secret.SecretStoreException("the two entries differ");
            return MasterKey.of(first.clone());
        } finally {
            SecretOptions.wipe(first);
            SecretOptions.wipe(again);
        }
    }

    // ---- entry points for tests: an environment and prompts instead of the process's ----

    static int run(WeaveEnv env, Prompts prompts, String... args) {
        CommandLine cl = new CommandLine(new SecretCommands());
        cl.setOut(new java.io.PrintWriter(env.out(), true));
        cl.setErr(new java.io.PrintWriter(env.err(), true));
        cl.setExecutionStrategy(
                parse -> {
                    CommandLine.ParseResult leaf = parse;
                    while (leaf.hasSubcommand()) leaf = leaf.subcommand();
                    Object command = leaf.commandSpec().userObject();
                    if (command instanceof Base base) return base.execute(env, prompts);
                    return 0;
                });
        return cl.execute(args);
    }
}
