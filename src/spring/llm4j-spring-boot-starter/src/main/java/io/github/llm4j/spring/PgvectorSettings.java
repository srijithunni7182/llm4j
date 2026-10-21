package io.github.llm4j.spring;

import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import org.springframework.core.env.Environment;

/**
 * Where the pgvector store connects: {@code llm4j.rag.pgvector.*}, falling back to the
 * application's own {@code spring.datasource.*}. Resolved apart from the store itself, because the
 * store connects when it is built.
 */
record PgvectorSettings(
        String url,
        String user,
        SecretRef password,
        boolean fromSecret,
        String table,
        int dimension) {

    static PgvectorSettings resolve(
            Llm4jProperties.Rag.Pgvector p, SecretStore secrets, Environment env) {
        String url = p.url() != null ? p.url() : env.getProperty("spring.datasource.url");
        String user = p.user() != null ? p.user() : env.getProperty("spring.datasource.username");
        if (url == null) {
            throw new Llm4jConfigurationException(
                    "llm4j.rag.store=pgvector has no database",
                    "Set llm4j.rag.pgvector.url, or spring.datasource.url.");
        }
        if (p.dimension() == null) {
            throw new Llm4jConfigurationException(
                    "llm4j.rag.store=pgvector needs llm4j.rag.pgvector.dimension",
                    "Give the size of the embedding vectors.");
        }
        SecretRef password =
                CredentialProperty.ref(
                        "llm4j.rag.pgvector.password", p.password(), p.passwordSecret(), secrets);
        boolean fromSecret = password != null && p.passwordSecret() != null;
        if (password == null && env.getProperty("spring.datasource.password") != null)
            password = SecretRef.literal(env.getProperty("spring.datasource.password"));
        return new PgvectorSettings(url, user, password, fromSecret, p.table(), p.dimension());
    }
}
