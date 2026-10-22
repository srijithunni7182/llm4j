package io.github.llm4j.spring;

import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;

/** A credential is a literal or the name of an entry in the secret store, never both. */
final class CredentialProperty {

    private CredentialProperty() {}

    /**
     * @param property the literal property, as the user writes it ({@code
     *     llm4j.clients.default.api-key}); its {@code -secret} twin is named in messages
     * @return a reference that fetches the value when it is used, or null when neither form is set
     */
    static SecretRef ref(String property, String literal, String secretName, SecretStore store) {
        boolean hasLiteral = literal != null && !literal.isBlank();
        boolean hasSecret = secretName != null && !secretName.isBlank();
        if (hasLiteral && hasSecret) {
            throw new Llm4jConfigurationException(
                    property + " and " + property + "-secret are both set",
                    "Set one: "
                            + property
                            + "-secret names an entry in the secret store and is the safer form.");
        }
        if (hasSecret) {
            if (store == null) {
                throw new Llm4jConfigurationException(
                        property + "-secret is set but there is no secret store",
                        "Set llm4j.secrets.store (env, encrypted-file or chained), or declare a SecretStore bean.");
            }
            return SecretRef.of(store, secretName);
        }
        return hasLiteral ? SecretRef.literal(literal) : null;
    }

    /**
     * The value now (for a place that needs a string, such as a tool option); null when neither
     * form is set.
     */
    static String value(String property, String literal, String secretName, SecretStore store) {
        SecretRef ref = ref(property, literal, secretName, store);
        return ref == null ? null : ref.resolve();
    }
}
