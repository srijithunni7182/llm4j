package io.github.llm4j.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void testDefaultPolicy() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertThat(policy.getMaxRetries()).isEqualTo(3);
        assertThat(policy.getBackoffStrategy()).isEqualTo(RetryPolicy.BackoffStrategy.EXPONENTIAL);
        assertThat(policy.isRetryable(429)).isTrue();
        assertThat(policy.isRetryable(500)).isTrue();
        assertThat(policy.isRetryable(400)).isFalse();
    }

    @Test
    void testNoRetryPolicy() {
        RetryPolicy policy = RetryPolicy.noRetry();

        assertThat(policy.getMaxRetries()).isEqualTo(0);
    }

    @Test
    void testExponentialBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .backoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofSeconds(10))
                .build();

        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.calculateBackoff(3)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void testLinearBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .backoffStrategy(RetryPolicy.BackoffStrategy.LINEAR)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofSeconds(10))
                .build();

        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofMillis(300));
        assertThat(policy.calculateBackoff(3)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void testFixedBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .backoffStrategy(RetryPolicy.BackoffStrategy.FIXED)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofSeconds(10))
                .build();

        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void testMaxBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .backoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL)
                .initialBackoff(Duration.ofMillis(1000))
                .maxBackoff(Duration.ofMillis(2000))
                .build();

        // Should cap at maxBackoff
        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofMillis(2000));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void testCustomRetryableStatusCodes() {
        RetryPolicy policy = RetryPolicy.builder()
                .addRetryableStatusCode(418) // I'm a teapot
                .build();

        assertThat(policy.isRetryable(418)).isTrue();
        assertThat(policy.isRetryable(500)).isFalse();
    }
}
