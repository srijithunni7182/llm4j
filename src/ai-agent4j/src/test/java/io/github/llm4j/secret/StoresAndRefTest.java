package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoresAndRefTest {

    @Test
    void inMemoryRoundTrip() {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put("gemini", FakeKeys.ONE);
        assertTrue(s.contains("gemini"));
        assertEquals(FakeKeys.ONE, s.resolve("gemini"));
        assertEquals(Set.of("gemini"), s.names());
        assertTrue(s.metadata("gemini").orElseThrow().updatedAt() != null, "updatedAt is stamped");
        s.put("gemini", FakeKeys.TWO);
        assertEquals(FakeKeys.TWO, s.resolve("gemini"), "a put replaces");
        assertTrue(s.remove("gemini"));
        assertFalse(s.remove("gemini"));
        assertFalse(s.contains("gemini"));
        assertThrows(SecretNotFoundException.class, () -> s.resolve("gemini"));
    }

    @Test
    void inMemoryCopiesTheCallersArrayAndWipesOnRemoveAndClose() {
        InMemorySecretStore s = new InMemorySecretStore();
        char[] mine = FakeKeys.ONE.toCharArray();
        s.put("a", mine);
        java.util.Arrays.fill(mine, '\0'); // the caller wipes its copy
        assertEquals(FakeKeys.ONE, s.resolve("a"), "the store kept its own copy");
        s.close();
        assertThrows(IllegalStateException.class, () -> s.resolve("a"));
        assertThrows(IllegalStateException.class, () -> s.put("b", "x"));
    }

    @Test
    void inMemoryRejectsBadNamesAndEmptyValues() {
        InMemorySecretStore s = new InMemorySecretStore();
        assertThrows(IllegalArgumentException.class, () -> s.put("bad name", "x"));
        assertThrows(IllegalArgumentException.class, () -> s.put("ok", ""));
        assertThrows(IllegalArgumentException.class, () -> s.put("ok", (char[]) null));
        assertFalse(s.contains(null));
        assertTrue(s.metadata("nothing").isEmpty());
    }

    @Test
    void hostBindingIsEnforcedByResolveFor() {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put("k", FakeKeys.ONE, SecretMetadata.allowing("api.example.com"));
        assertEquals(FakeKeys.ONE, s.resolveFor("k", "api.example.com"));
        SecretAccessDeniedException e = assertThrows(SecretAccessDeniedException.class, () -> s.resolveFor("k", "evil.example.net"));
        assertEquals("k", e.name());
        assertEquals("evil.example.net", e.host());
        assertFalse(e.getMessage().contains(FakeKeys.ONE), e.getMessage());
        assertThrows(SecretAccessDeniedException.class, () -> s.resolveFor("k", null));
        assertThrows(SecretNotFoundException.class, () -> s.resolveFor("missing", "api.example.com"));
        assertEquals(FakeKeys.ONE, s.resolve("k"), "resolve without a host does not check (callers that know the host use resolveFor)");
    }

    @Test
    void envStoreIsReadOnlyAndNamesAreVariables() {
        EnvSecretStore s = EnvSecretStore.of(Map.of("GEMINI_API_KEY", FakeKeys.ONE, "EMPTY", "  ", "bad.name", "x"));
        assertEquals(FakeKeys.ONE, s.resolve("GEMINI_API_KEY"));
        assertTrue(s.contains("GEMINI_API_KEY"));
        assertFalse(s.contains("EMPTY"), "a blank variable is not a secret");
        assertEquals(Set.of("EMPTY", "GEMINI_API_KEY"), s.names(), "only legal names are listed");
        assertThrows(UnsupportedOperationException.class, () -> s.put("a", "b"));
        assertThrows(UnsupportedOperationException.class, () -> s.remove("a"));
        assertThrows(SecretNotFoundException.class, () -> s.resolve("NOPE"));
        assertEquals(SecretMetadata.NONE, s.metadata("GEMINI_API_KEY").orElseThrow());
        assertEquals(FakeKeys.ONE, EnvSecretStore.of((java.util.function.Function<String, String>) n -> FakeKeys.ONE).resolve("ANY"));
        assertTrue(EnvSecretStore.system().names().stream().allMatch(SecretNames::isValid));
    }

    @Test
    void chainTakesTheFirstStoreThatHasTheName() {
        InMemorySecretStore first = new InMemorySecretStore();
        InMemorySecretStore second = new InMemorySecretStore();
        first.put("a", "from-first", SecretMetadata.allowing("one.example.com"));
        second.put("a", "from-second");
        second.put("b", "only-second");
        ChainedSecretStore c = ChainedSecretStore.of(first, second);
        assertEquals("from-first", c.resolve("a"));
        assertEquals("only-second", c.resolve("b"));
        assertEquals(Set.of("a", "b"), c.names());
        assertEquals(Set.of("one.example.com"), c.metadata("a").orElseThrow().allowedHosts());
        assertThrows(SecretAccessDeniedException.class, () -> c.resolveFor("a", "other.example.com"), "the holder's host binding applies");
        assertThrows(SecretNotFoundException.class, () -> c.resolve("zzz"));
        assertThrows(UnsupportedOperationException.class, () -> c.put("x", "y"));
        assertThrows(IllegalArgumentException.class, ChainedSecretStore::of);
        c.close();
        assertThrows(IllegalStateException.class, () -> first.resolve("a"));
    }

    @Test
    void refResolvesOnTheFly() {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put("k", FakeKeys.ONE);
        SecretRef ref = SecretRef.of(s, "k");
        assertEquals(FakeKeys.ONE, ref.resolve());
        assertEquals(FakeKeys.ONE, ref.get());
        s.put("k", FakeKeys.TWO);
        assertEquals(FakeKeys.TWO, ref.resolve(), "rotation takes effect on the next resolve");
        assertTrue(ref.exists());
        s.remove("k");
        assertFalse(ref.exists());
        assertThrows(SecretNotFoundException.class, ref::resolve);
    }

    @Test
    void refNeverPrintsTheValueAndIsNotSerializable() {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put("k", FakeKeys.ONE);
        SecretRef ref = SecretRef.of(s, "k");
        assertEquals("secret:k", ref.toString());
        assertFalse(ref.toString().contains(FakeKeys.ONE));
        assertFalse(SecretRef.literal(FakeKeys.ONE).toString().contains(FakeKeys.ONE));
        assertEquals("secret:(literal)", SecretRef.literal(FakeKeys.ONE).toString());
        assertFalse(java.io.Serializable.class.isAssignableFrom(SecretRef.class));
    }

    @Test
    void refEqualityAndLiterals() {
        InMemorySecretStore s = new InMemorySecretStore();
        InMemorySecretStore other = new InMemorySecretStore();
        assertEquals(SecretRef.of(s, "k"), SecretRef.of(s, "k"));
        assertEquals(SecretRef.of(s, "k").hashCode(), SecretRef.of(s, "k").hashCode());
        assertNotEquals(SecretRef.of(s, "k"), SecretRef.of(other, "k"));
        assertNotEquals(SecretRef.of(s, "k"), SecretRef.of(s, "j"));
        SecretRef lit = SecretRef.literal("x");
        assertEquals(lit, SecretRef.literal("x"), "literals with the same value are equal");
        assertEquals(lit.hashCode(), SecretRef.literal("y").hashCode(), "a literal's hash is constant, so it reveals nothing about the value");
        assertNotEquals(lit, SecretRef.literal("y"));
        assertNotEquals(lit, SecretRef.of(s, "x"), "a literal never equals a stored secret");
        assertTrue(lit.isLiteral());
        assertTrue(lit.exists());
        assertEquals("x", lit.resolve());
        assertEquals("x", lit.resolveFor("any.host"), "a literal carries no host binding");
        assertThrows(IllegalArgumentException.class, () -> SecretRef.of(s, "bad name"));
        assertThrows(NullPointerException.class, () -> SecretRef.of(null, "k"));
        assertThrows(NullPointerException.class, () -> SecretRef.literal(null));
    }

    @Test
    void refResolveForEnforcesTheStoresBinding() {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put("k", FakeKeys.ONE, SecretMetadata.allowing("api.example.com"));
        SecretRef ref = SecretRef.of(s, "k");
        assertEquals(FakeKeys.ONE, ref.resolveFor("api.example.com"));
        assertThrows(SecretAccessDeniedException.class, () -> ref.resolveFor("localhost"));
    }
}
