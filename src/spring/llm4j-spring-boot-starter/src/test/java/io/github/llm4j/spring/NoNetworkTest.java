package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ProxySelector;
import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The guard that keeps every default-build test on this machine does what it says. V14.1. */
class NoNetworkTest {

    @Tag("SB-V14.1")
    @Test
    void aTestThatLooksForAHostOutsideThisMachineFailsAndLoopbackIsFine() {
        ProxySelector selector = ProxySelector.getDefault();
        selector.select(URI.create("http://127.0.0.1:8080/x"));
        selector.select(URI.create("http://localhost:8080/x"));
        new NoNetwork().afterEach(null);                       // nothing outside: no failure
        selector.select(URI.create("https://api.example.com/v1/chat"));
        assertThatThrownBy(() -> new NoNetwork().afterEach(null)).isInstanceOf(AssertionError.class).hasMessageContaining("api.example.com");
        assertThat(selector.getClass().getName()).contains("NoNetwork");
    }
}
