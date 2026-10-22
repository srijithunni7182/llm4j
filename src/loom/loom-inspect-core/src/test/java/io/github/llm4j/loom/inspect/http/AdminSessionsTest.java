package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The password (plain or hashed) and how guessing is slowed. */
class AdminSessionsTest {

    private static final String PASSWORD = "correct horse battery";
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-12T10:00:00Z"));
    private final Clock clock =
            new Clock() {
                public java.time.ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(java.time.ZoneId z) {
                    return this;
                }

                public Instant instant() {
                    return now.get();
                }
            };

    private AdminSessions sessions(String stored) {
        return new AdminSessions(() -> stored, clock, false);
    }

    @Test
    void aHashedPasswordSignsInWithThePassword_andIsNotThePassword() {
        String hash = PasswordHash.hash(PASSWORD);
        assertThat(hash).startsWith("pbkdf2-sha256$").doesNotContain(PASSWORD);
        assertThat(PasswordHash.hash(PASSWORD)).isNotEqualTo(hash); // a fresh salt each time
        AdminSessions s = sessions(hash);
        assertThat(s.login("1.1.1.1", PASSWORD).session()).isNotNull();
        assertThat(s.login("1.1.1.1", "not the password").session()).isNull();
        assertThat(s.login("1.1.1.1", hash).session())
                .isNull(); // the hash itself is not a password
    }

    @Test
    void aPlainStoredPasswordStillWorks_andAMalformedHashNeverMatches() {
        assertThat(sessions(PASSWORD).login("a", PASSWORD).session()).isNotNull();
        assertThat(PasswordHash.matches("pbkdf2-sha256$1$x$y", "x")).isFalse();
        assertThat(PasswordHash.matches("pbkdf2-sha256$210000$!!$!!", "x")).isFalse();
        assertThat(PasswordHash.matches(null, "x")).isFalse();
    }

    @Test
    void repeatedWrongGuessesFromOneAddressWaitLongerEachTime_andAreForgottenAfterAWhile() {
        AdminSessions s = sessions(PASSWORD);
        for (int i = 0; i < 3; i++) s.login("2.2.2.2", "nope");
        AdminSessions.Attempt held = s.login("2.2.2.2", PASSWORD);
        assertThat(held.session()).isNull();
        assertThat(held.retryAfterSeconds()).isGreaterThan(0); // even the right password waits
        assertThat(s.login("3.3.3.3", PASSWORD).session())
                .isNotNull(); // another address is not held
        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThat(s.login("2.2.2.2", PASSWORD).session()).isNotNull();
    }

    @Test
    void guessesSpreadOverManyAddressesStillSlowEverySignIn() {
        AdminSessions s = sessions(PASSWORD);
        for (int i = 0; i < AdminSessions.GLOBAL_PER_MINUTE; i++) s.login("10.0.0." + i, "nope");
        AdminSessions.Attempt a = s.login("10.9.9.9", PASSWORD);
        assertThat(a.session()).isNull();
        assertThat(a.retryAfterSeconds()).isBetween(1L, 60L);
        now.set(now.get().plusSeconds(61));
        assertThat(s.login("10.9.9.9", PASSWORD).session()).isNotNull();
    }

    @Test
    void confirmingAPasswordStartsNoSession() {
        AdminSessions s = sessions(PASSWORD);
        assertThat(s.confirm("a", PASSWORD)).isTrue();
        assertThat(s.confirm("a", "wrong")).isFalse();
    }
}
