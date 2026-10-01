package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.generic.support.StubResolver;
import io.github.llm4j.loom.tools.generic.NetPolicy;
import io.github.llm4j.loom.tools.generic.ToolRefusal;
import java.net.UnknownHostException;
import java.util.Set;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NetPolicyTest {

    static NetPolicy policy(NetPolicy.Rules rules, StubResolver resolver, String configured) {
        return new NetPolicy(rules, resolver, configured);
    }

    static final NetPolicy.Rules STRICT = new NetPolicy.Rules(false, false, Set.of(), false);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://example.com/x|",
            "http://example.com/x|only https",
            "http://localhost:8080/x|",
            "http://127.0.0.1:8080/x|",
            "http://[::1]:8080/x|",
            "https://user:pw@example.com/|user name or password",
            "https://user@example.com/|user name or password"})
    @Tag("V3.1")
    void schemeAndCredentialsAreCheckedOnTheUrl(String url, String problem) {
        String result = policy(STRICT, new StubResolver(), null).checkUrl(HttpUrl.parse(url));
        if (problem == null) assertThat(result).isNull();
        else assertThat(result).contains(problem);
    }

    @Test
    @Tag("V3.1")
    void allowHttpOpensPlainHttp() {
        NetPolicy p = policy(new NetPolicy.Rules(true, false, Set.of(), false), new StubResolver(), null);
        assertThat(p.checkUrl(HttpUrl.parse("http://example.com/"))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"10.0.0.5,private", "192.168.1.1,private", "172.16.0.1,private", "172.31.255.255,private",
            "169.254.169.254,link-local", "127.0.0.1,loopback", "::1,loopback", "fd00::1,private",
            "0.0.0.0,unspecified", "::ffff:10.0.0.5,private", "::ffff:127.0.0.1,loopback", "100.64.0.1,shared",
            "224.0.0.1,multicast", "fe80::1,link-local", "0.1.2.3,unspecified"})
    @Tag("V3.2")
    void forbiddenAddressesAreRefusedWithTheRuleThatRefusedThem(String address, String kind) {
        StubResolver dns = new StubResolver().answer("evil.test", address);
        assertThatThrownBy(() -> policy(STRICT, dns, null).resolveChecked("evil.test"))
                .isInstanceOf(ToolRefusal.class).hasMessageContaining(kind);
    }

    @Test
    @Tag("V3.2")
    void aSingleBadAddressAmongGoodOnesRefusesTheHost() {
        StubResolver dns = new StubResolver().answer("mixed.test", "93.184.216.34", "10.0.0.5");
        assertThatThrownBy(() -> policy(STRICT, dns, null).resolveChecked("mixed.test")).isInstanceOf(ToolRefusal.class);
    }

    @Test
    @Tag("V3.2")
    void publicAddressesPassAndAllowPrivateTurnsTheCheckOff() throws UnknownHostException {
        StubResolver dns = new StubResolver().answer("ok.test", "93.184.216.34").answer("lan.test", "10.1.2.3");
        assertThat(policy(STRICT, dns, null).resolveChecked("ok.test")).hasSize(1);
        NetPolicy open = policy(new NetPolicy.Rules(false, true, Set.of(), false), dns, null);
        assertThat(open.resolveChecked("lan.test")).hasSize(1);
    }

    @Test
    @Tag("V3.2")
    void loopbackIsAllowedOnlyForTheHostTheDeclarationItselfNames() throws UnknownHostException {
        StubResolver dns = new StubResolver().answer("localhost", "127.0.0.1").answer("sneaky.test", "127.0.0.1");
        NetPolicy p = policy(STRICT, dns, "localhost");
        assertThat(p.resolveChecked("localhost")).hasSize(1);
        assertThatThrownBy(() -> p.resolveChecked("sneaky.test")).isInstanceOf(ToolRefusal.class);
    }

    @Test
    @Tag("V3.2")
    void unknownHostsAreReportedAsSuch() {
        assertThatThrownBy(() -> policy(STRICT, new StubResolver(), null).resolveChecked("nowhere.test"))
                .isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://a.example.com/", "https://deep.a.example.com/"})
    @Tag("V3.4")
    void wildcardHostsMatchSubdomains(String url) {
        NetPolicy p = policy(new NetPolicy.Rules(false, false, Set.of("*.example.com"), false), new StubResolver(), null);
        assertThat(p.checkUrl(HttpUrl.parse(url))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/", "https://example.com.evil.net/", "https://evilexample.com/", "https://other.org/"})
    @Tag("V3.4")
    void wildcardHostsDoNotMatchTheApexOrLookalikes(String url) {
        NetPolicy p = policy(new NetPolicy.Rules(false, false, Set.of("*.example.com"), false), new StubResolver(), null);
        assertThat(p.checkUrl(HttpUrl.parse(url))).contains("not in this tool's hosts");
    }

    @Test
    @Tag("V3.4")
    void theConfiguredHostIsAlwaysAllowed() {
        NetPolicy p = policy(new NetPolicy.Rules(false, false, Set.of("only.example.com"), false), new StubResolver(), "api.example.org");
        assertThat(p.checkUrl(HttpUrl.parse("https://api.example.org/x"))).isNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://only.example.com/x"))).isNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://third.example.net/x"))).isNotNull();
    }
}
