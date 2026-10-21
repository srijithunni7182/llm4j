package io.github.llm4j.spring;

import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;

/**
 * Where the Pinecone store connects: {@code llm4j.rag.pinecone.*}. Resolved apart from the store,
 * which reaches the service when it is built.
 */
record PineconeSettings(SecretRef key, boolean fromSecret, String index, String namespace) {

    static PineconeSettings resolve(Llm4jProperties.Rag.Pinecone p, SecretStore secrets) {
        SecretRef key =
                CredentialProperty.ref(
                        "llm4j.rag.pinecone.api-key", p.apiKey(), p.apiKeySecret(), secrets);
        if (key == null || p.index() == null) {
            throw new Llm4jConfigurationException(
                    "llm4j.rag.store=pinecone needs an API key and an index",
                    "Set llm4j.rag.pinecone.api-key-secret and llm4j.rag.pinecone.index.");
        }
        return new PineconeSettings(key, p.apiKeySecret() != null, p.index(), p.namespace());
    }
}
