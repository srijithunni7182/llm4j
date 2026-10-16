package io.github.llm4j.nirmaan.host;

import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.secret.ChainedSecretStore;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.EnvSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The factory as a service: starts runs, remembers them, and gives each the keys, the model client and (when configured) the Fabric
 * observer. One run at a time: every run spends money and builds code on this machine.
 */
public final class FactoryHost implements AutoCloseable {

    /** Where model clients come from: the real ones, or (in tests) a scripted one. */
    public interface Models {
        LLMClientFactory factory(SecretStore secrets);

        /** What {@code env.NAME} resolves through, or null for the process environment. A test returns a stand-in so no real key is needed. */
        default Function<String, String> envLookup() { return null; }
    }

    /** A run was asked for while another is still going. */
    public static final class Busy extends Exception {
        Busy(String message) { super(message); }
    }

    private final FactoryConfig config;
    private final Models models;
    private final SecretStore secrets;
    private final ObserveProvider.Session observe;
    private final Map<String, HostRun> runs = new ConcurrentHashMap<>();
    private volatile HostRun active;

    public FactoryHost(FactoryConfig config, Models models) {
        this.config = config;
        this.models = models;
        this.secrets = openSecrets(config);
        this.observe = openObserver(config);
    }

    /** The real models: Gemini and friends, with their keys from the secret store or the environment. */
    public static Models realModels() {
        return secrets -> new DefaultLLMClientFactory(System::getenv, secrets);
    }

    public FactoryConfig config() { return config; }

    Models models() { return models; }

    SecretStore secrets() { return secrets; }

    /** Whether a secret by this name is available (names only: the value is never read here). */
    public boolean hasSecret(String name) { return secrets.contains(name); }

    public synchronized HostRun start(String idea) throws Busy {
        if (active != null && active.active()) throw new Busy("a run is already going (" + active.id() + "): one at a time, because each one spends money and builds code here");
        String id = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        HostRun run = new HostRun(id, idea, this, config);
        runs.put(id, run);
        active = run;
        run.start();
        return run;
    }

    public HostRun run(String id) { return runs.get(id); }

    synchronized void release(HostRun run) {
        if (active == run) active = null;
    }

    void attachObserver(HarnessExecutor executor, Path script) {
        if (observe != null) observe.attach(executor.observable(), script);
    }

    @Override
    public void close() {
        if (observe != null) observe.close();
    }

    private static SecretStore openSecrets(FactoryConfig config) {
        SecretStore env = EnvSecretStore.system();
        if (!Files.isRegularFile(config.secretsStore()) || !Files.isRegularFile(config.secretsKey())) return env;
        try {
            return ChainedSecretStore.of(EncryptedFileSecretStore.open(config.secretsStore(), MasterKey.fromFile(config.secretsKey())), env);
        } catch (RuntimeException e) {
            System.err.println("Could not open the secret store " + config.secretsStore() + " (" + e.getMessage() + "); using environment variables only.");
            return env;
        }
    }

    private ObserveProvider.Session openObserver(FactoryConfig config) {
        if (config.fabricUrl().isBlank()) return null;
        try {
            ObserveOptions options = ObserveOptions.fromCommandLine(null, null, "truncated", config.fabricName(), null, config.fabricUrl(), null, false, null, null, null, null, System::getenv);
            ObserveProvider.Session session = Observe.open(options, System.out);
            System.out.println("🔭 " + session.summary());
            return session;
        } catch (Exception e) {
            System.err.println("Not reporting to Fabric: " + e.getMessage());
            return null;
        }
    }
}
