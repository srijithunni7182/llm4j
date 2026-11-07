package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EndpointGuardTest {

    private static final String DECLARED =
            "https://supplier.example.com/.well-known/agent-card.json";

    private static AgentCardView card(AgentCardView.Endpoint... endpoints) {
        return AgentCardView.builder("Supplier", "sells parts")
                .endpoints(List.of(endpoints))
                .build();
    }

    private static AgentCardView.Endpoint ep(String url, String version) {
        return new AgentCardView.Endpoint(url, "JSONRPC", version, null);
    }

    @Test
    void selectsTheFirstEndpointTheTransportSupports() {
        var transport = new FakeTransport().supporting(e -> e.protocolVersion().equals("1.0"));
        var chosen =
                EndpointGuard.select(
                        DECLARED,
                        Set.of(),
                        card(
                                ep("https://supplier.example.com/old", "0.3"),
                                ep("https://supplier.example.com/new", "1.0")),
                        transport);
        assertThat(chosen.url()).endsWith("/new");
    }

    @Test
    void anEndpointOnAnotherHostIsRefusedBeforeAnythingIsSent() {
        var transport = new FakeTransport();
        assertThatThrownBy(
                        () ->
                                EndpointGuard.select(
                                        DECLARED,
                                        Set.of(),
                                        card(ep("https://evil.example.net/rpc", "1.0")),
                                        transport))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("evil.example.net");
        assertThat(transport.operations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://supplier.example.com.evil.net/rpc",
                "https://evilsupplier.example.com/rpc",
                "https://supplier.example.com@evil.net/rpc",
                "https://supplier.example.com:pw@evil.net/rpc",
                "https://user@supplier.example.com/rpc",
                "http://supplier.example.com/rpc",
                "https://supplier.example.com\\@evil.net/rpc",
                "ftp://supplier.example.com/rpc",
                "not a url"
            })
    void lookalikesUserinfoAndPlainHttpAreRefused(String url) {
        assertThatThrownBy(
                        () ->
                                EndpointGuard.select(
                                        DECLARED,
                                        Set.of(),
                                        card(ep(url, "1.0")),
                                        new FakeTransport()))
                .isInstanceOf(A2AException.Refused.class);
    }

    @Test
    void aHostTheDeclarationListsIsAccepted() {
        var chosen =
                EndpointGuard.select(
                        DECLARED,
                        Set.of("api.partner.example.org"),
                        card(ep("https://api.partner.example.org/rpc", "1.0")),
                        new FakeTransport());
        assertThat(chosen.url()).contains("partner");
    }

    @Test
    void hostComparisonIgnoresCase() {
        var chosen =
                EndpointGuard.select(
                        DECLARED,
                        Set.of(),
                        card(ep("https://SUPPLIER.Example.COM/rpc", "1.0")),
                        new FakeTransport());
        assertThat(chosen).isNotNull();
    }

    @Test
    void noSupportedEndpointNamesWhatTheCardOffered() {
        var transport = new FakeTransport().supporting(e -> false);
        assertThatThrownBy(
                        () ->
                                EndpointGuard.select(
                                        DECLARED,
                                        Set.of(),
                                        card(
                                                ep("https://supplier.example.com/a", "0.3"),
                                                new AgentCardView.Endpoint(
                                                        "https://supplier.example.com/b",
                                                        "GRPC",
                                                        "1.0",
                                                        null)),
                                        transport))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("JSONRPC")
                .hasMessageContaining("0.3")
                .hasMessageContaining("GRPC")
                .hasMessageContaining("1.0");
    }

    @Test
    void aCardWithNoEndpointsIsRefused() {
        assertThatThrownBy(
                        () -> EndpointGuard.select(DECLARED, Set.of(), card(), new FakeTransport()))
                .isInstanceOf(A2AException.Refused.class);
    }

    @Test
    void theDeclaredAddressMustBeHttps() {
        assertThatThrownBy(
                        () ->
                                EndpointGuard.select(
                                        "http://supplier.example.com/card.json",
                                        Set.of(),
                                        card(ep("https://supplier.example.com/a", "1.0")),
                                        new FakeTransport()))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("https");
    }
}
