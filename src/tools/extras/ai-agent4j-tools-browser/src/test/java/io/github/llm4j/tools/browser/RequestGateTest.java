package io.github.llm4j.tools.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.EgressProxy;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D16 rule 1 and D7: what a page may load. */
class RequestGateTest {

    static InetAddress ip(String a) throws Exception {
        return InetAddress.getByName(a);
    }

    final RequestGate gate = new RequestGate(EgressProxy.Hosts.parse("example.com, *.cdn.example"), h -> switch (h) {
        case "example.com", "img.cdn.example" -> List.of(InetAddress.getByName("93.184.215.14"));
        default -> List.of(InetAddress.getByName("10.0.0.7"));
    }, port -> port == 41000);

    @Test
    void aListedHostOverHttpsOnAPublicAddressLoads() {
        assertThat(gate.refusal("https://example.com/page")).isNull();
        assertThat(gate.refusal("https://img.cdn.example/a.png")).isNull();
        assertThat(gate.refusal("data:image/png;base64,AAAA")).isNull();
        assertThat(gate.refusal("about:blank")).isNull();
    }

    @Test
    void everythingElseIsRefusedWithTheReason() {
        assertThat(gate.refusal("http://example.com/")).contains("only https");
        assertThat(gate.refusal("https://evil.example/")).contains("not in this tool's hosts");
        assertThat(gate.refusal("https://example.com:8443/")).contains("not in this tool's hosts");
        assertThat(gate.refusal("https://inside.cdn.example/")).contains("private");
        assertThat(gate.refusal("https://user:pw@example.com/")).contains("user name or password");
        assertThat(gate.refusal("file:///etc/passwd")).contains("only https");
        assertThat(gate.refusal("ws://example.com/")).contains("only https");
        assertThat(gate.refusal("http://127.0.0.1:22/")).contains("port 22 is not a program this run serves");
        assertThat(gate.refusal("https://127.0.0.1:41000/")).contains("over http");
        assertThat(gate.refusal("http://localhost:41000/")).isNotNull();
        assertThat(gate.refusal("https://169.254.169.254/latest")).isNotNull();
        assertThat(gate.refusal("not a url with spaces")).isNotNull();
        assertThat(gate.refusal("https://nowhere.example.com/")).isNotNull();
    }

    @Test
    void theServedPortIsTheOneLoopbackAddressLetThrough() {
        assertThat(gate.refusal("http://127.0.0.1:41000/app")).isNull();
        RequestGate noGrant = new RequestGate(EgressProxy.Hosts.parse("example.com"), h -> List.of(), null);
        assertThat(noGrant.refusal("http://127.0.0.1:41000/app")).isNotNull();
        assertThat(noGrant.reachesServed()).isFalse();
        RequestGate onlyServed = new RequestGate(null, h -> List.of(), p -> true);
        assertThat(onlyServed.refusal("https://example.com/")).contains("only the program the run serves");
        assertThatThrownBy(() -> new RequestGate(null, h -> List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownHostIsRefused() {
        RequestGate g = new RequestGate(EgressProxy.Hosts.parse("example.com"), h -> {
            throw new java.net.UnknownHostException(h);
        }, null);
        assertThat(g.refusal("https://example.com/")).contains("unknown host");
    }
}
