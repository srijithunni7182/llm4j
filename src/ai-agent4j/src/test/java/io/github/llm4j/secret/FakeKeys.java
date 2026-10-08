package io.github.llm4j.secret;

/** Obvious fakes: nothing here is, or looks like, a real credential. */
final class FakeKeys {

    static final String ONE = "test-key-0001";
    static final String TWO = "test-key-0002";
    static final String PASSPHRASE = "correct horse battery staple (test)";

    private FakeKeys() { }

    static MasterKey master() {
        return MasterKey.of(PASSPHRASE.toCharArray());
    }

    static EncryptedFileSecretStore.Options fast() {
        return EncryptedFileSecretStore.Options.defaults().kdfIterations(EncryptedFileSecretStore.MIN_ITERATIONS);
    }
}
