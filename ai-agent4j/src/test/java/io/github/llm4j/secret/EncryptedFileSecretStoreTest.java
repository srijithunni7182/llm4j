package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedFileSecretStoreTest {

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    private Path file() {
        return dir.resolve("vault.json");
    }

    private EncryptedFileSecretStore create() {
        return EncryptedFileSecretStore.create(file(), FakeKeys.master(), FakeKeys.fast());
    }

    private EncryptedFileSecretStore open() {
        return EncryptedFileSecretStore.open(file(), FakeKeys.master(), FakeKeys.fast());
    }

    private Map<String, String> snapshot(SecretStore s) {
        Map<String, String> out = new TreeMap<>();
        for (String n : s.names()) out.put(n, s.resolve(n));
        return out;
    }

    // ---- round trip ---------------------------------------------------------------------------------------------

    @Test
    void roundTripSurvivesReopening() {
        try (EncryptedFileSecretStore s = create()) {
            s.put("gemini", FakeKeys.ONE, SecretMetadata.allowing("generativelanguage.googleapis.com").withDescription("prod"));
            s.put("sarvam", FakeKeys.TWO);
        }
        try (EncryptedFileSecretStore s = open()) {
            assertEquals(Map.of("gemini", FakeKeys.ONE, "sarvam", FakeKeys.TWO), snapshot(s));
            assertEquals(Set.of("generativelanguage.googleapis.com"), s.metadata("gemini").orElseThrow().allowedHosts());
            assertEquals("prod", s.metadata("gemini").orElseThrow().description());
            assertNotNull(s.metadata("sarvam").orElseThrow().updatedAt());
        }
    }

    @Test
    void awkwardValuesRoundTrip() {
        String[] values = {"a", "with \"quotes\" and \\ backslash", "line1\nline2\r\nline3", "unicode: é中😀", "{\"json\":true}", "  spaces  ", "x".repeat(10_000)};
        try (EncryptedFileSecretStore s = create()) {
            for (int i = 0; i < values.length; i++) s.put("k" + i, values[i]);
        }
        try (EncryptedFileSecretStore s = open()) {
            for (int i = 0; i < values.length; i++) assertEquals(values[i], s.resolve("k" + i), "value " + i);
        }
    }

    @Test
    void theFileHoldsNoPlaintextAndNoSecretNames() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("my-very-recognisable-name", "my-very-recognisable-value", SecretMetadata.allowing("api.secret-host.example"));
        }
        String text = Files.readString(file());
        assertFalse(text.contains("my-very-recognisable-name"), "names are encrypted too");
        assertFalse(text.contains("my-very-recognisable-value"));
        assertFalse(text.contains("secret-host"), "metadata is encrypted too");
        JsonNode root = json.readTree(text);
        assertEquals("llm4j-secrets", root.get("format").asText());
        assertEquals(1, root.get("version").asInt());
        assertEquals("PBKDF2WithHmacSHA256", root.at("/kdf/alg").asText());
        assertEquals("AES-256-GCM", root.at("/cipher/alg").asText());
        assertEquals(EncryptedFileSecretStore.MIN_ITERATIONS, root.at("/kdf/iterations").asInt());
    }

    @Test
    void defaultIterationsAreSixHundredThousand() throws Exception {
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.create(file(), FakeKeys.master())) {
            s.put("a", "b");
        }
        assertEquals(600_000, json.readTree(Files.readString(file())).at("/kdf/iterations").asInt());
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), FakeKeys.master())) {
            assertEquals("b", s.resolve("a"), "an existing file keeps its own iteration count");
        }
    }

    @Test
    void everyWriteUsesAFreshNonce() throws Exception {
        Set<String> nonces = new HashSet<>();
        try (EncryptedFileSecretStore s = create()) {
            for (int i = 0; i < 200; i++) {
                s.put("k", "value-" + i);
                nonces.add(json.readTree(Files.readString(file())).at("/cipher/nonce").asText());
            }
        }
        assertEquals(200, nonces.size(), "a nonce must never repeat under one key");
    }

    @Test
    void theSameContentEncryptsDifferentlyEachTime() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", "v");
            String first = json.readTree(Files.readString(file())).get("data").asText();
            s.put("k", "v");
            String second = json.readTree(Files.readString(file())).get("data").asText();
            assertNotEquals(first, second);
        }
    }

    // ---- wrong key, tampering, damage ---------------------------------------------------------------------------

    @Test
    void aWrongMasterKeyIsRefusedWithoutSayingWhy() {
        create().close();
        SecretStoreException e = assertThrows(SecretStoreException.class,
                () -> EncryptedFileSecretStore.open(file(), MasterKey.of("not the key".toCharArray()), FakeKeys.fast()));
        assertTrue(e.getMessage().contains("wrong master key or the file is damaged"), e.getMessage());
        assertFalse(e.getMessage().contains("not the key"));
    }

    private ObjectNode doc() throws Exception {
        return (ObjectNode) json.readTree(Files.readString(file()));
    }

    private void write(JsonNode doc) throws Exception {
        Files.writeString(file(), json.writeValueAsString(doc));
    }

    private void assertRefused(String why) {
        SecretStoreException e = assertThrows(SecretStoreException.class, this::open, why);
        assertFalse(e.getMessage().contains(FakeKeys.ONE), why);
    }

    @Test
    void everyAuthenticatedHeaderFieldIsBoundToTheCiphertext() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
        }
        String original = Files.readString(file());
        // lowering the iteration count (a downgrade attack) must fail authentication, not just be accepted
        ObjectNode d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("kdf")).put("iterations", EncryptedFileSecretStore.MIN_ITERATIONS + 1);
        write(d);
        assertRefused("iteration count");
        // a different (but valid) salt
        d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("kdf")).put("salt", java.util.Base64.getEncoder().encodeToString(new byte[16]));
        write(d);
        assertRefused("salt");
        // a different nonce
        d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("cipher")).put("nonce", java.util.Base64.getEncoder().encodeToString(new byte[12]));
        write(d);
        assertRefused("nonce");
        // the header names are not what is authenticated for the format string itself: an unknown format is refused outright
        d = (ObjectNode) json.readTree(original);
        d.put("format", "something-else");
        write(d);
        assertRefused("format");
        // restoring the original works again
        Files.writeString(file(), original);
        try (EncryptedFileSecretStore s = open()) {
            assertEquals(FakeKeys.ONE, s.resolve("k"));
        }
    }

    @Test
    void unsupportedVersionsAlgorithmsAndLimitsAreRefused() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
        }
        String original = Files.readString(file());
        ObjectNode d = (ObjectNode) json.readTree(original);
        d.put("version", 2);
        write(d);
        SecretStoreException v = assertThrows(SecretStoreException.class, this::open);
        assertTrue(v.getMessage().contains("version"), v.getMessage());

        d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("kdf")).put("alg", "MD5");
        write(d);
        assertRefused("kdf alg");

        d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("cipher")).put("alg", "AES-128-ECB");
        write(d);
        assertRefused("cipher alg");

        d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("kdf")).put("iterations", 1);
        write(d);
        assertRefused("too few iterations is refused before any work");

        d = (ObjectNode) json.readTree(original);
        ((ObjectNode) d.get("kdf")).put("iterations", Integer.MAX_VALUE);
        write(d);
        assertRefused("an absurd iteration count must not be a denial of service");

        d = (ObjectNode) json.readTree(original);
        d.remove("data");
        write(d);
        assertRefused("missing data");

        d = (ObjectNode) json.readTree(original);
        d.put("data", "!!!not base64!!!");
        write(d);
        assertRefused("bad base64");
    }

    @Test
    void notAStoreAtAll() throws Exception {
        create().close();
        Files.writeString(file(), "");
        assertRefused("empty file");
        Files.writeString(file(), "this is not json");
        assertRefused("not json");
        Files.writeString(file(), "[1,2,3]");
        assertRefused("json but not an object");
        Files.writeString(file(), "{}");
        assertRefused("an object with no format");
    }

    @Test
    void truncationIsDetected() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
        }
        byte[] bytes = Files.readAllBytes(file());
        for (int keep : new int[] {0, 1, bytes.length / 4, bytes.length / 2, bytes.length - 20, bytes.length - 2}) {
            Files.write(file(), java.util.Arrays.copyOf(bytes, keep));
            assertRefused("truncated to " + keep);
        }
    }

    @Test
    void ciphertextAndTagFlipsAreDetected() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
        }
        String original = Files.readString(file());
        ObjectNode base = (ObjectNode) json.readTree(original);
        byte[] data = java.util.Base64.getDecoder().decode(base.get("data").asText());
        for (int i = 0; i < data.length; i++) { // every ciphertext byte, and the tag at the end
            byte[] bad = data.clone();
            bad[i] ^= 0x01;
            ObjectNode d = base.deepCopy();
            d.put("data", java.util.Base64.getEncoder().encodeToString(bad));
            write(d);
            assertRefused("flip in data byte " + i);
        }
    }

    /**
     * The integrity property: whatever one bit flip anywhere in the file does, opening either fails with a SecretStoreException or yields exactly
     * the original secrets. It must never succeed with different contents, and must never fail with anything else.
     */
    @Test
    void anyBitFlipInTheFileEitherFailsOrChangesNothing() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k1", FakeKeys.ONE, SecretMetadata.allowing("a.example.com"));
            s.put("k2", FakeKeys.TWO);
        }
        byte[] original = Files.readAllBytes(file());
        Map<String, String> expected = Map.of("k1", FakeKeys.ONE, "k2", FakeKeys.TWO);
        Random random = new Random(42);
        int tested = 0;
        for (int n = 0; n < 150; n++) {
            int pos = n < 100 ? random.nextInt(original.length) : n - 100; // 100 random positions, then the first 50 bytes
            byte[] bad = original.clone();
            bad[pos] ^= (byte) (1 << random.nextInt(8));
            Files.write(file(), bad);
            tested++;
            try (EncryptedFileSecretStore s = open()) {
                assertEquals(expected, snapshot(s), "a flip at byte " + pos + " changed the contents");
                assertEquals(Set.of("a.example.com"), s.metadata("k1").orElseThrow().allowedHosts(), "a flip at byte " + pos + " changed the metadata");
            } catch (SecretStoreException refused) {
                assertFalse(refused.getMessage().contains(FakeKeys.ONE));
            }
        }
        assertEquals(150, tested);
    }

    // ---- create/open rules --------------------------------------------------------------------------------------

    @Test
    void createRefusesAnExistingFileAndOpenRefusesAMissingOne() {
        create().close();
        assertThrows(SecretStoreException.class, () -> EncryptedFileSecretStore.create(file(), FakeKeys.master(), FakeKeys.fast()));
        SecretStoreException e = assertThrows(SecretStoreException.class,
                () -> EncryptedFileSecretStore.open(dir.resolve("missing.json"), FakeKeys.master(), FakeKeys.fast()));
        assertTrue(e.getMessage().contains("missing.json"));
    }

    @Test
    void openOrCreateDoesWhicheverApplies() {
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.openOrCreate(file(), FakeKeys.master(), FakeKeys.fast())) {
            s.put("a", "one");
        }
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.openOrCreate(file(), FakeKeys.master(), FakeKeys.fast())) {
            assertEquals("one", s.resolve("a"));
        }
    }

    @Test
    void theLibraryDoesNotCreateTheConsumersDirectory() {
        Path nested = dir.resolve("no-such-dir").resolve("vault.json");
        SecretStoreException e = assertThrows(SecretStoreException.class, () -> EncryptedFileSecretStore.create(nested, FakeKeys.master(), FakeKeys.fast()));
        assertTrue(e.getMessage().contains("consumer"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("no-such-dir")));
    }

    @Test
    void iterationFloorIsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> EncryptedFileSecretStore.Options.defaults().kdfIterations(99_999));
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    @Test
    void aFailedWriteLeavesTheFileIntactAndNoTemporaryFile() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            s.put("a", "before");
            byte[] bytes = Files.readAllBytes(file());
            s.beforeMoveHook = () -> {
                throw new SecretStoreException("simulated crash between the temporary write and the move");
            };
            assertThrows(SecretStoreException.class, () -> s.put("a", "after"));
            assertThrows(SecretStoreException.class, () -> s.put("b", "new"));
            assertThrows(SecretStoreException.class, () -> s.remove("a"));
            assertArrayEquals(bytes, Files.readAllBytes(file()), "the file on disk never changed");
            assertEquals("before", s.resolve("a"), "and neither did the store's view");
            assertFalse(s.contains("b"));
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(1, files.count(), "no temporary file left behind");
            }
            s.beforeMoveHook = null;
            s.put("a", "recovered");
            assertEquals("recovered", s.resolve("a"));
        }
    }

    @Test
    void anotherWritersChangeIsDetectedNotOverwritten() {
        try (EncryptedFileSecretStore first = create()) {
            first.put("a", "from-first");
            try (EncryptedFileSecretStore second = open()) {
                second.put("b", "from-second");
            }
            // first still believes the file is as it left it
            SecretStoreException e = assertThrows(SecretStoreException.class, () -> first.put("c", "late"));
            assertTrue(e.getMessage().contains("changed by someone else"), e.getMessage());
            assertTrue(first.contains("b"), "reads pick up the other writer's change (the file's size changed)");
            first.reload();
            first.put("c", "late");
            assertEquals(Set.of("a", "b", "c"), first.names(), "nothing the other writer did was lost");
        }
    }

    @Test
    void anExternallyRotatedSecretIsPickedUpWithoutARestart() throws Exception {
        try (EncryptedFileSecretStore reader = create()) {
            reader.put("k", "old-value");
            try (EncryptedFileSecretStore writer = open()) {
                writer.put("k", "new-value-which-is-longer");
            }
            assertEquals("new-value-which-is-longer", reader.resolve("k"));
        }
    }

    @Test
    void aFileRekeyedElsewhereIsFollowedWhenTheKeyFileMatches() throws Exception {
        Path keyFile = dir.resolve("master.key");
        Files.writeString(keyFile, "key-one\n");
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.create(file(), MasterKey.fromFile(keyFile), FakeKeys.fast())) {
            s.put("k", "v");
            try (EncryptedFileSecretStore other = EncryptedFileSecretStore.open(file(), MasterKey.fromFile(keyFile), FakeKeys.fast())) {
                Files.writeString(keyFile, "key-two\n");
                other.rekey(MasterKey.fromFile(keyFile)); // the key file now holds the new key; the file is re-encrypted under it
            }
            assertEquals("v", s.resolve("k"), "the first store re-derives from the key file, which now holds the new key");
        }
    }

    @Test
    void rekeyingReplacesTheKeyAndTheSalt() throws Exception {
        String saltBefore;
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
            saltBefore = json.readTree(Files.readString(file())).at("/kdf/salt").asText();
            s.rekey(MasterKey.of("a brand new passphrase".toCharArray()));
            assertEquals(FakeKeys.ONE, s.resolve("k"));
        }
        assertNotEquals(saltBefore, json.readTree(Files.readString(file())).at("/kdf/salt").asText());
        assertThrows(SecretStoreException.class, this::open, "the old key stops working");
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), MasterKey.of("a brand new passphrase".toCharArray()), FakeKeys.fast())) {
            assertEquals(FakeKeys.ONE, s.resolve("k"));
        }
    }

    @Test
    void removeAndOverwrite() {
        try (EncryptedFileSecretStore s = create()) {
            s.put("a", "1");
            s.put("b", "2");
            assertTrue(s.remove("a"));
            assertFalse(s.remove("a"));
            s.put("b", "3");
        }
        try (EncryptedFileSecretStore s = open()) {
            assertEquals(Map.of("b", "3"), snapshot(s));
        }
    }

    // ---- closing, leaks ------------------------------------------------------------------------------------------

    @Test
    void closeWipesAndLaterUseFails() {
        EncryptedFileSecretStore s = create();
        s.put("k", FakeKeys.ONE);
        s.close();
        s.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> s.resolve("k"));
        assertThrows(IllegalStateException.class, () -> s.put("k", "x"));
        assertThrows(IllegalStateException.class, s::names);
    }

    @Test
    void toStringsAndMessagesNeverContainTheValueOrThePassphrase() {
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
            assertFalse(s.toString().contains(FakeKeys.ONE));
            assertFalse(s.toString().contains(FakeKeys.PASSPHRASE));
            SecretNotFoundException e = assertThrows(SecretNotFoundException.class, () -> s.resolve("other"));
            assertFalse(e.getMessage().contains(FakeKeys.ONE));
        }
    }

    @Test
    void badNamesAndEmptyValuesAreRefused() {
        try (EncryptedFileSecretStore s = create()) {
            assertThrows(IllegalArgumentException.class, () -> s.put("bad name", "x"));
            assertThrows(IllegalArgumentException.class, () -> s.put("ok", ""));
            assertFalse(s.contains(null));
            assertTrue(s.metadata("nothing").isEmpty());
        }
    }

    @Test
    void manyThreadsReadAndWriteWithoutLosingAnything() throws Exception {
        try (EncryptedFileSecretStore s = create()) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            java.util.List<Future<?>> work = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                work.add(pool.submit(() -> {
                    for (int i = 0; i < 5; i++) {
                        s.put("t" + id + "-" + i, "value-" + id + "-" + i);
                        assertEquals("value-" + id + "-" + i, s.resolve("t" + id + "-" + i));
                    }
                    return null;
                }));
            }
            for (Future<?> f : work) f.get();
            pool.shutdown();
            assertEquals(40, s.names().size());
        }
        try (EncryptedFileSecretStore s = open()) {
            assertEquals(40, s.names().size());
        }
    }

    // ---- permissions: the consumer's responsibility ----------------------------------------------------------------

    private boolean posix() throws Exception {
        return Files.getFileStore(dir).supportsFileAttributeView("posix");
    }

    @Test
    void thereIsNoPermissionRequirementByDefault() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(posix(), "POSIX file permissions are not available on this file system");
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
        }
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--r--"));
        try (EncryptedFileSecretStore s = open()) { // opens (and logs a warning): protecting the file is the consumer's job
            assertEquals(FakeKeys.ONE, s.resolve("k"));
        }
    }

    @Test
    void requirePrivateFileRefusesAFileOthersCanRead() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(posix(), "POSIX file permissions are not available on this file system");
        create().close();
        EncryptedFileSecretStore.Options strict = FakeKeys.fast().requirePrivateFile();
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r-----"));
        SecretStoreException e = assertThrows(SecretStoreException.class, () -> EncryptedFileSecretStore.open(file(), FakeKeys.master(), strict));
        assertTrue(e.getMessage().contains("readable by group or others"), e.getMessage());
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------"));
        EncryptedFileSecretStore.open(file(), FakeKeys.master(), strict).close();
    }

    @Test
    void theLibraryCreatesTheFileOwnerOnlyAndKeepsWhatTheConsumerSets() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(posix(), "POSIX file permissions are not available on this file system");
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", "v");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())), "created owner-only");
            Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-rw----"));
            s.put("k2", "v2");
            assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())), "the replacement keeps the consumer's setting");
        }
    }

    @Test
    void passphraseVariantsAllOpenTheSameStore() throws Exception {
        Path keyFile = dir.resolve("k.txt");
        Files.writeString(keyFile, FakeKeys.PASSPHRASE + "\n", StandardCharsets.UTF_8);
        try (EncryptedFileSecretStore s = create()) {
            s.put("k", FakeKeys.ONE);
        }
        for (MasterKey key : new MasterKey[] {
                MasterKey.fromFile(keyFile),
                MasterKey.fromEnv("ANY_NAME_THE_CONSUMER_CHOSE", Map.of("ANY_NAME_THE_CONSUMER_CHOSE", FakeKeys.PASSPHRASE)::get),
                MasterKey.from(FakeKeys.PASSPHRASE::toCharArray)}) {
            try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), key, FakeKeys.fast())) {
                assertEquals(FakeKeys.ONE, s.resolve("k"), key.toString());
            }
        }
    }
}
