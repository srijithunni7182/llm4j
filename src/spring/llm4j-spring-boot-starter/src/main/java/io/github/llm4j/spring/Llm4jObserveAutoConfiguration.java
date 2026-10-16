package io.github.llm4j.spring;

import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.secret.SecretStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.logging.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * The live run page (loom-inspect) and reporting to a Fabric server, when {@code llm4j.observe.enabled=true}: one session opened for the application, each
 * workflow run attached to it before it starts. This starts a web server on {@code llm4j.observe.port} (loopback unless you say otherwise): it is the one place
 * start-up opens a port, and only because you asked.
 */
@AutoConfiguration(before = Llm4jLoomAutoConfiguration.class, after = Llm4jSecretsAutoConfiguration.class)
@ConditionalOnClass(name = "io.github.llm4j.loom.observe.Observe")
@ConditionalOnProperty(prefix = "llm4j.observe", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jObserveAutoConfiguration {

    private static final Logger log = Logger.getLogger(Llm4jObserveAutoConfiguration.class.getName());

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ObserveProvider.Session llm4jObserveSession(Llm4jProperties properties, ObjectProvider<SecretStore> secrets, Environment environment, ResourceLoader resources) {
        Llm4jProperties.Observe o = properties.observe();
        boolean loopback = o.bind().equals("127.0.0.1") || o.bind().equals("localhost") || o.bind().equals("::1");
        SecretStore store = secrets.getIfAvailable();
        if (!loopback && (store == null || !store.contains("LOOM_INSPECT_ADMIN_PASSWORD"))) {
            throw new Llm4jConfigurationException("llm4j.observe.bind=" + o.bind() + " exposes the run page beyond this machine, and there is no admin password",
                    "Put LOOM_INSPECT_ADMIN_PASSWORD (a hash: weave observe hash-password) in the secret store, or bind to 127.0.0.1.");
        }
        try {
            ObserveOptions options = ObserveOptions.fromCommandLine(String.valueOf(o.port()), o.bind(), o.detail(), o.name(), o.linger() == null ? null : o.linger().toSeconds() + "s",
                    o.fabric().url(), o.fabric().ca(), false, null, o.fabric().outbox(), null, null, environment::getProperty);
            if (o.rulesFile() != null) options = options.withRules(Llm4jAgentsAutoConfiguration.onDisk("llm4j.observe.rules-file", o.rulesFile(), resources));
            PrintStream out = new PrintStream(new ByteArrayOutputStream() {
                @Override
                public synchronized void write(byte[] b, int off, int len) {
                    String text = new String(b, off, len).strip();
                    if (!text.isEmpty()) log.info(text);
                }
            }, true);
            return Observe.open(options, out);
        } catch (Observe.Unavailable e) {
            throw new Llm4jConfigurationException("llm4j.observe.enabled is true but loom-inspect is not on the class path: " + e.getMessage(), "Add the artifact loom-inspect (and loom-inspect-core).");
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException("llm4j.observe: " + e.getMessage(), "Check the llm4j.observe.* settings.");
        }
    }
}
