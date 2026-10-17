package io.github.llm4j.spring;

import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Settings that need an optional llm4j module fail with the name of the artifact to add, instead of silently creating nothing. Runs before any other bean, and
 * looks at the class path the application context sees.
 */
@AutoConfiguration(before = {Llm4jClientsAutoConfiguration.class, Llm4jToolsAutoConfiguration.class, Llm4jAgentsAutoConfiguration.class, Llm4jRagAutoConfiguration.class,
        Llm4jLoomAutoConfiguration.class, Llm4jObserveAutoConfiguration.class})
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
public class Llm4jGuardAutoConfiguration {

    @Bean
    static BeanFactoryPostProcessor llm4jModuleGuard(Environment environment) {
        return beanFactory -> check(environment, beanFactory.getBeanClassLoader());
    }

    static void check(Environment env, ClassLoader loader) {
        if (Llm4jConditions.anyUnder(env, "llm4j.loom") && !present("io.github.llm4j.loom.host.LoomHost", loader)) {
            missing("llm4j.loom.*", "ai-agent4j-loom");
        }
        if (Llm4jConditions.anyUnder(env, "llm4j.tools.custom") && !present("io.github.llm4j.tools.GenericKind", loader)) {
            missing("llm4j.tools.custom.*", "ai-agent4j-tools");
        }
        String store = env.getProperty("llm4j.rag.store", "").toLowerCase();
        String embedding = env.getProperty("llm4j.rag.embedding", "").toLowerCase();
        if ((List.of("pgvector", "pinecone").contains(store) || List.of("onnx", "djl").contains(embedding)) && !present("io.github.llm4j.agent.rag.store.PGVectorStore", loader)) {
            missing("llm4j.rag.store=" + store + " / llm4j.rag.embedding=" + embedding, "ai-agent4j-addons");
        }
        if (env.getProperty("llm4j.observe.enabled", Boolean.class, false) && !present("io.github.llm4j.loom.observe.Observe", loader)) {
            missing("llm4j.observe.enabled", "loom-inspect");
        }
        if ("jdbc".equalsIgnoreCase(env.getProperty("llm4j.loom.persistence", "")) && !present("org.springframework.jdbc.support.JdbcUtils", loader)) {
            missing("llm4j.loom.persistence=jdbc", "spring-jdbc (spring-boot-starter-jdbc)");
        }
    }

    private static boolean present(String className, ClassLoader loader) {
        return ClassUtils.isPresent(className, loader);
    }

    private static void missing(String setting, String artifact) {
        throw new Llm4jConfigurationException(setting + " is set but " + artifact + " is not on the class path", "Add " + artifact + " to the application's dependencies (or remove the setting).");
    }
}
