package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretNamesAndMetadataTest {

    @ParameterizedTest
    @ValueSource(strings = {"A", "GEMINI_API_KEY", "gemini-prod", "k1", "A-b_C-9"})
    void validNames(String name) {
        assertTrue(SecretNames.isValid(name));
        assertEquals(name, SecretNames.require(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1abc", "_x", "-x", "a.b", "a b", "a/b", "é", "a{b}"})
    void invalidNames(String name) {
        assertFalse(SecretNames.isValid(name));
        assertThrows(IllegalArgumentException.class, () -> SecretNames.require(name));
    }

    @Test
    void nameLengthLimit() {
        assertTrue(SecretNames.isValid("a".repeat(64)));
        assertFalse(SecretNames.isValid("a".repeat(65)));
        assertFalse(SecretNames.isValid(null));
    }

    @Test
    void unrestrictedAllowsEverything() {
        assertTrue(SecretMetadata.NONE.allows("anything.example"));
        assertTrue(SecretMetadata.NONE.allows(null));
        assertTrue(SecretMetadata.NONE.allowedHosts().isEmpty());
    }

    @Test
    void exactHostsAreCaseInsensitive() {
        SecretMetadata m = SecretMetadata.allowing("API.Example.com");
        assertEquals(Set.of("api.example.com"), m.allowedHosts());
        assertTrue(m.allows("api.example.com"));
        assertTrue(m.allows("API.EXAMPLE.COM"));
        assertTrue(m.allows("api.example.com."), "a trailing dot is the same host");
        assertFalse(m.allows("example.com"));
        assertFalse(m.allows("x.api.example.com"));
        assertFalse(m.allows("evil-api.example.com"));
    }

    @Test
    void wildcardMatchesSubdomainsNotTheApexOrLookalikes() {
        SecretMetadata m = SecretMetadata.allowing("*.example.com");
        assertTrue(m.allows("a.example.com"));
        assertTrue(m.allows("a.b.example.com"));
        assertFalse(m.allows("example.com"));
        assertFalse(m.allows("evilexample.com"));
        assertFalse(m.allows("example.com.evil.net"));
        assertFalse(m.allows(".example.com"));
    }

    @Test
    void restrictedSecretNeverAllowsAMissingHost() {
        SecretMetadata m = SecretMetadata.allowing("api.example.com");
        assertFalse(m.allows(null));
        assertFalse(m.allows(""));
        assertFalse(m.allows("   "));
    }

    @Test
    void hostsMayCarryAPortOrBracketsWhenAsked() {
        SecretMetadata m = SecretMetadata.allowing("localhost", "::1");
        assertTrue(m.allows("localhost:8080"));
        assertTrue(m.allows("[::1]"));
        assertTrue(m.allows("::1"));
        assertFalse(m.allows("127.0.0.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.com", "api.example.com/path", "user@host", "a b", "", "*", "*.", "*example.com", "ex ample.com"})
    void badHostPatternsAreRejected(String pattern) {
        assertThrows(IllegalArgumentException.class, () -> SecretMetadata.allowing(pattern), pattern);
    }

    @Test
    void hostOfUrls() {
        assertEquals("generativelanguage.googleapis.com", SecretMetadata.hostOf("https://generativelanguage.googleapis.com/v1beta"));
        assertEquals("localhost", SecretMetadata.hostOf("http://localhost:11434/api"));
        assertEquals("::1", SecretMetadata.hostOf("http://[::1]:8080/"));
        assertEquals("api.example.com", SecretMetadata.hostOf("API.example.com"));
        assertNull(SecretMetadata.hostOf(null));
        assertNull(SecretMetadata.hostOf("  "));
        assertNull(SecretMetadata.hostOf("https:///nohost"));
    }

    @Test
    void metadataHoldsNoValueAndIsImmutable() {
        SecretMetadata m = SecretMetadata.allowing("a.example.com").withDescription("prod key");
        assertEquals("prod key", m.description());
        assertThrows(UnsupportedOperationException.class, () -> m.allowedHosts().add("b.example.com"));
        assertFalse(m.toString().contains("test-key"));
    }
}
