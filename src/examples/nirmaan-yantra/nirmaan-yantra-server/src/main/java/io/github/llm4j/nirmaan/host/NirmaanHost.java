package io.github.llm4j.nirmaan.host;

import java.nio.file.Files;

/** Starts the factory's web host: {@code java -cp "target/classes:target/lib/*" io.github.llm4j.nirmaan.host.NirmaanHost}. */
public final class NirmaanHost {

    private NirmaanHost() {}

    public static void main(String[] args) throws Exception {
        FactoryConfig config = FactoryConfig.fromEnvironment();
        if (!Files.isRegularFile(config.script())) {
            System.err.println("The workflow " + config.script() + " was not found. Run this from the nirmaan-yantra-server folder, or set NIRMAAN_SCRIPT.");
            System.exit(2);
        }
        FactoryHost host = new FactoryHost(config, FactoryHost.realModels());
        NirmaanServer server = new NirmaanServer(host, config.uiDir(), config.bind(), config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            host.close();
        }, "nirmaan-shutdown"));
        server.start();
        System.out.println("Nirmaan Yantra is up: " + server.url());
        System.out.println("  workflow: " + config.script());
        System.out.println("  model key (GEMINI_API_KEY): " + (host.hasSecret("GEMINI_API_KEY") ? "found" : "NOT FOUND (add it to the secret store, see README)"));
        System.out.println("  reporting to Fabric: " + (config.fabricUrl().isBlank() ? "no (set NIRMAAN_FABRIC_URL to turn it on)" : config.fabricUrl()));
        Thread.currentThread().join();
    }
}
