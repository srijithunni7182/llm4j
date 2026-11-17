package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.DecisionModels;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.SecretException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave keys}: which key each model needs, whether it is set, and the exact lines a person
 * uses to set a missing one, by either route: a {@code .env} line for running {@code weave}
 * themselves, or the encrypted secret store for a program or a server. It reads names only: never
 * a key's value, and it never asks for one.
 */
@Command(
        name = "keys",
        description =
                "Says which API key each model needs, whether it is set (in the environment, a .env file or the secret store; never its value), and prints how to set a missing one: a .env line for running weave yourself, or the secret store commands for a program or a server. Give a script, or the models you mean to use with --model.")
final class KeysCommand implements Callable<Integer> {

    /** What the store commands name when no store is given: chosen by the person, shown as a place to start. */
    static final String EXAMPLE_STORE = "keys.store";

    /** The environment variable the store commands name for the master key when none is given. */
    static final String EXAMPLE_KEY_VARIABLE = "LOOM_MASTER_KEY";

    private static final String WIDTH = "  %-24s %-20s %s%n";

    /** Where a key is read from at run time: the environment (a {@code .env} file included), the secret store, or either. */
    enum Source {
        ENVIRONMENT,
        STORE,
        EITHER;

        boolean readsEnvironment() {
            return this != STORE;
        }

        boolean readsStore() {
            return this != ENVIRONMENT;
        }
    }

    /** A key a model needs, and where the run will look for it. */
    record Need(String name, Source source) {}

    @Parameters(index = "0", arity = "0..1", paramLabel = "<script>", description = "The .loom script whose models to look at.")
    File script;

    @Option(names = "--model", paramLabel = "<name>", description = "A model you mean to use, e.g. gpt-4.1-mini (repeatable).")
    List<String> models = new ArrayList<>();

    @Mixin SecretOptions secrets = new SecretOptions();

    @Mixin EnvFileOptions envFile = new EnvFileOptions();

    @Override
    public Integer call() {
        WeaveEnv env = WeaveEnv.system();
        if (script != null) env = envFile.apply(env, script.toPath());
        return env == null ? 2 : keys(this, env);
    }

    /** The models, their keys and how to set the missing ones. */
    static int keys(KeysCommand c, WeaveEnv env) {
        Set<String> wanted = new LinkedHashSet<>(c.models);
        LoomScript loaded = null;
        if (c.script != null) {
            try {
                loaded = new LoomLoader().load(c.script.getAbsolutePath());
                wanted.addAll(modelsOf(loaded));
            } catch (Exception e) {
                env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
                return 2;
            }
        }
        if (wanted.isEmpty()) {
            env.err().println("Error: give a script, or the models you mean to use: weave keys --model gpt-4.1-mini --model claude-sonnet-5-5");
            return 2;
        }
        try {
            c.secrets.fromEnvironment(env.env());
        } catch (SecretException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        Set<String> inStore = namesInStore(c.secrets, env);

        env.out().println("The models, and the key each needs:");
        Map<String, Source> missing = new LinkedHashMap<>();
        boolean unknown = false;
        for (String model : wanted) {
            Optional<Need> need;
            try {
                need = keyOf(model, loaded);
            } catch (IllegalArgumentException e) {
                env.out().printf(WIDTH, model, "?", e.getMessage());
                unknown = true;
                continue;
            }
            if (need.isEmpty()) {
                env.out().printf(WIDTH, model, "-", "no key: it runs on this computer");
                continue;
            }
            Need n = need.get();
            if (n.source().readsEnvironment() && present(env.env().apply(n.name()))) {
                env.out().printf(WIDTH, model, n.name(), "set in the environment (or the .env file)");
            } else if (n.source().readsStore() && inStore.contains(n.name())) {
                env.out().printf(WIDTH, model, n.name(), "set in the secret store " + c.secrets.file);
            } else {
                env.out().printf(WIDTH, model, n.name(), "not set" + where(n.source()));
                missing.merge(n.name(), n.source(), (a, b) -> a == b ? a : Source.EITHER);
            }
        }
        if (!missing.isEmpty()) instructions(env, missing, c);
        return missing.isEmpty() && !unknown ? 0 : 1;
    }

    private static String where(Source source) {
        return switch (source) {
            case ENVIRONMENT -> " (the script reads it from the environment only: env.NAME)";
            case STORE -> " (the script reads it from the secret store only: secret.NAME)";
            case EITHER -> "";
        };
    }

    /** How to set each missing key, by the routes its source allows, for the person to do themselves. */
    private static void instructions(WeaveEnv env, Map<String, Source> missing, KeysCommand c) {
        List<String> byEnvironment = missing.entrySet().stream().filter(e -> e.getValue().readsEnvironment()).map(Map.Entry::getKey).toList();
        List<String> byStore = missing.entrySet().stream().filter(e -> e.getValue().readsStore()).map(Map.Entry::getKey).toList();
        env.out().println();
        env.out().println("Set them yourself, in your own terminal or editor, not through a coding agent. Never paste a key into a chat.");
        if (!byEnvironment.isEmpty()) {
            Path dotEnv = c.script == null ? null : io.github.llm4j.loom.init.ProjectLayout.envFile(c.script.toPath());
            env.out().println();
            env.out().println("Running weave yourself, on this machine: put these lines in "
                    + (dotEnv == null ? "a .env file beside your script" : dotEnv.toString())
                    + ", with the key after the =. Git must ignore the file (weave doctor --fix sees to that).");
            for (String key : byEnvironment) env.out().println("  " + key + "=");
        }
        if (!byStore.isEmpty()) {
            String where = storeOptions(c.secrets);
            env.out().println();
            env.out().println("A program or a server: the encrypted secret store. Choose where the store lives and the variable that holds its master key"
                    + (c.secrets.file == null ? " (" + EXAMPLE_STORE + " and " + EXAMPLE_KEY_VARIABLE + " below are only examples)" : "")
                    + ", and keep both out of git.");
            if (c.secrets.file == null || !c.secrets.file.exists())
                env.out().println("  weave secrets create" + where + "      (once)");
            for (String key : byStore) env.out().println("  weave secrets set " + key + where + "      (asks for the value and shows nothing)");
            env.out().println("  then add" + where + " to weave run and weave eval.");
            env.out().println("The store keeps a key encrypted at rest: someone with the file but not the master key cannot read it. It does not protect"
                    + " a key from a program that runs with the master key available.");
        }
        env.out().println();
        env.out().println("Then run weave keys again to check. weave guide 9, \"Keys\", has both routes in full.");
    }

    /** The store options the commands repeat: the ones the person gave, else the examples. */
    private static String storeOptions(SecretOptions s) {
        String store = " --secrets " + quoted(s.file == null ? EXAMPLE_STORE : s.file.toString());
        if (s.keyFile != null) return store + " --secrets-key-file " + quoted(s.keyFile.toString());
        return store + " --secrets-key-env " + (s.keyEnv != null ? s.keyEnv : EXAMPLE_KEY_VARIABLE);
    }

    /** The names in the store, when one is named with a key source and can be opened; else none. Never asks for a passphrase. */
    private static Set<String> namesInStore(SecretOptions secrets, WeaveEnv env) {
        if (secrets.file == null || !secrets.file.isFile() || (secrets.keyFile == null && secrets.keyEnv == null)) return Set.of();
        if (secrets.keyFile != null && !secrets.keyFile.isFile()) return Set.of();
        try (EncryptedFileSecretStore store = EncryptedFileSecretStore.open(secrets.file.toPath(), secrets.masterKey(env, null, ""))) {
            return Set.copyOf(store.names());
        } catch (SecretException e) {
            env.err().println("Note: the secret store " + secrets.file + " could not be opened, so its keys are not counted: " + e.getMessage());
            return Set.of();
        }
    }

    /**
     * The key a model needs and where the run reads it. A provider the script declares names its own
     * ({@code api_key: env.NAME} reads the environment only, {@code secret.NAME} the store only); a
     * Jev or Perplexity decider model takes that vendor's key; any other name is placed by the default
     * model factory, which reads the environment or the store. Empty for a model that needs no key.
     */
    static Optional<Need> keyOf(String model, LoomScript script) {
        int slash = model.indexOf('/');
        if (script != null && slash > 0) {
            String provider = model.substring(0, slash);
            for (var p : script.getProviders()) {
                if (!p.getName().equals(provider)) continue;
                ToolDef.OptionValue key = p.getOptions().get("api_key");
                if (key == null || !key.isReference()) return Optional.empty();
                return switch (key.source()) {
                    case ENV -> Optional.of(new Need(key.value(), Source.ENVIRONMENT));
                    case SECRET -> Optional.of(new Need(key.value(), Source.STORE));
                    default -> Optional.empty(); // invoker.NAME: the person who runs it supplies it each time
                };
            }
        }
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jev/") || lower.startsWith("jev-")) return Optional.of(new Need("JEV_API_KEY", Source.EITHER));
        if (DecisionModels.isPerplexityDecider(model)) return Optional.of(new Need("PERPLEXITY_API_KEY", Source.EITHER));
        return DefaultLLMClientFactory.keyNameOf(model).map(name -> new Need(name, Source.EITHER));
    }

    /** Every model a script names: its agents (with their fallbacks), extractors, evaluators, classifiers and task steps. */
    static Set<String> modelsOf(LoomScript script) {
        Set<String> models = new LinkedHashSet<>();
        script.getAgents().forEach(a -> add(models, a.getModel()));
        script.getRoutingPolicies().forEach(p -> {
            add(models, p.getPrimaryModel());
            p.getFallbackModels().forEach(m -> add(models, m));
        });
        script.getExtractors().forEach(x -> add(models, x.getModel()));
        script.getEvaluators().forEach(e -> add(models, e.getModel()));
        script.getIntentClassifiers().forEach(k -> add(models, k.getModel()));
        script.getWorkflows().forEach(w -> StatementWalker.walk(w.getStatements(), st -> {
            if (st instanceof RunStmt r) add(models, r.getModel());
        }));
        return models;
    }

    private static void add(Set<String> models, String model) {
        if (model != null && !model.isBlank()) models.add(model.strip());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** A path as a shell reads it: quoted when it holds a space. */
    private static String quoted(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }
}
