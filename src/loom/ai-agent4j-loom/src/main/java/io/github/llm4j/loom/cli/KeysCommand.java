package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.SecretException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave keys}: which key each model needs, whether it is set, and the exact commands a person
 * runs in their own terminal to set a missing one. It reads names only: never a key's value, and it
 * never asks for one.
 */
@Command(
        name = "keys",
        description =
                "Says which API key each model needs, whether it is set (in the environment, a .env file or the secret store; never its value), and prints the exact commands that set a missing one. Give a script, or the models you mean to use with --model.")
final class KeysCommand implements Callable<Integer> {

    /** Where the commands suggest a store when none is named, under the person's home folder. */
    static final String DEFAULT_FOLDER = ".llm4j";

    private static final String WIDTH = "  %-24s %-20s %s%n";

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
        return env == null ? 2 : keys(this, env, Path.of(System.getProperty("user.home")));
    }

    /** The models, their keys and the commands, for {@code home} as the person's home folder. */
    static int keys(KeysCommand c, WeaveEnv env, Path home) {
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
        c.secrets.fromEnvironment(env.env());
        Path store = c.secrets.file != null ? c.secrets.file.toPath() : home.resolve(DEFAULT_FOLDER).resolve("secrets.store");
        Path keyFile = c.secrets.keyFile != null ? c.secrets.keyFile.toPath() : home.resolve(DEFAULT_FOLDER).resolve("secrets.key");
        Set<String> inStore = namesInStore(c.secrets, env);

        env.out().println("The models, and the key each needs:");
        Map<String, String> missing = new LinkedHashMap<>();
        boolean unknown = false;
        for (String model : wanted) {
            String key;
            try {
                key = keyOf(model, loaded).orElse(null);
            } catch (IllegalArgumentException e) {
                env.out().printf(WIDTH, model, "?", e.getMessage());
                unknown = true;
                continue;
            }
            if (key == null) {
                env.out().printf(WIDTH, model, "-", "no key: it runs on this computer");
            } else if (present(env.env().apply(key))) {
                env.out().printf(WIDTH, model, key, "set in the environment (or the .env file)");
            } else if (inStore.contains(key)) {
                env.out().printf(WIDTH, model, key, "set in the secret store " + store);
            } else {
                env.out().printf(WIDTH, model, key, "not set");
                missing.putIfAbsent(key, model);
            }
        }
        if (!missing.isEmpty()) instructions(env, missing.keySet(), store, keyFile, c);
        return missing.isEmpty() && !unknown ? 0 : 1;
    }

    /** The commands that set each missing key, for the person to run themselves. */
    private static void instructions(WeaveEnv env, Set<String> missing, Path store, Path keyFile, KeysCommand c) {
        String where = " --secrets " + quoted(store) + keyOption(c, keyFile);
        env.out().println();
        env.out().println("Run these in your own terminal, not through a coding agent: each asks for what it needs and shows nothing.");
        env.out().println("Never paste a key into a chat.");
        env.out().println();
        if (!Files.exists(store))
            env.out().println("  weave secrets create" + where + "      (once: makes the store" + (c.secrets.keyEnv == null && !Files.exists(keyFile) ? " and a master key file only you can read)" : ")"));
        for (String key : missing) env.out().println("  weave secrets set " + key + where);
        env.out().println();
        if (c.secrets.keyEnv == null) {
            env.out().println("Then name the store once, so every weave command finds it. In your shell profile (~/.bashrc, ~/.zshrc):");
            env.out().println("  export " + SecretOptions.STORE_VARIABLE + "=" + quoted(store));
            env.out().println("  export " + SecretOptions.KEY_FILE_VARIABLE + "=" + quoted(keyFile));
            env.out().println("On Windows, then open a new terminal:");
            env.out().println("  setx " + SecretOptions.STORE_VARIABLE + " \"" + store + "\"");
            env.out().println("  setx " + SecretOptions.KEY_FILE_VARIABLE + " \"" + keyFile + "\"");
        } else {
            env.out().println("Then give the store to each weave command:" + where);
        }
        env.out().println();
        Path dotEnv = c.script == null ? null : io.github.llm4j.loom.init.ProjectLayout.envFile(c.script.toPath());
        env.out().println(
                "Or, for one project only: a line " + missing.iterator().next() + "=... in "
                        + (dotEnv == null ? "a .env file beside the script" : dotEnv.toString())
                        + ", which git must ignore (weave doctor --fix sees to that).");
        env.out().println("A deployed application reads keys from its own store or vault: weave guide 9, \"Keys\".");
    }

    /**
     * The key a model needs: a provider the script declares names its own ({@code api_key:
     * env.NAME}); a Jev or Perplexity decider model takes that vendor's key; any other name is
     * placed by the default model factory.
     */
    static java.util.Optional<String> keyOf(String model, LoomScript script) {
        int slash = model.indexOf('/');
        if (script != null && slash > 0) {
            String provider = model.substring(0, slash);
            for (var p : script.getProviders()) {
                if (!p.getName().equals(provider)) continue;
                var key = p.getOptions().get("api_key");
                return key == null || !key.isReference() ? java.util.Optional.empty() : java.util.Optional.of(key.value());
            }
        }
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("jev/") || lower.startsWith("jev-")) return java.util.Optional.of("JEV_API_KEY");
        if (io.github.llm4j.loom.execution.DecisionModels.isPerplexityDecider(model)) return java.util.Optional.of("PERPLEXITY_API_KEY");
        return DefaultLLMClientFactory.keyNameOf(model);
    }

    private static String keyOption(KeysCommand c, Path keyFile) {
        return c.secrets.keyEnv != null ? " --secrets-key-env " + c.secrets.keyEnv : " --secrets-key-file " + quoted(keyFile);
    }

    /** The names in the store, when one is named with a key source and can be opened; else none. */
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
    private static String quoted(Path path) {
        String text = path.toString();
        return text.contains(" ") ? "\"" + text + "\"" : text;
    }
}
