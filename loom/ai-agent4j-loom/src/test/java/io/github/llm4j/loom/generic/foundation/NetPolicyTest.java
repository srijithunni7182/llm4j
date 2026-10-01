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
        // A declaration's own URL is the configured host (that is what lets localhost through).
        String result = policy(STRICT, new StubResolver(), HttpUrl.parse(url).host()).checkUrl(HttpUrl.parse(url));
        if (problem == null) assertThat(result).isNull();
        else assertThat(result).contains(problem);
    }

    @Test
    @Tag("V3.2")
    void aLoopbackAddressInAUrlIsOnlyAcceptedWhenItIsTheDeclaredHost() {
        HttpUrl local = HttpUrl.parse("http://127.0.0.1:8080/x");
        assertThat(policy(STRICT, new StubResolver(), "127.0.0.1").checkUrl(local)).isNull();
        assertThat(policy(STRICT, new StubResolver(), "api.example.com").checkUrl(local)).contains("loopback");
        assertThat(policy(STRICT, new StubResolver(), null).checkUrl(local)).contains("loopback");
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

    @Test
    @Tag("V3.2")
    void anIpv4MappedIpv6AnswerIsJudgedAsTheIpv4AddressItWraps() throws Exception {
        StubResolver dns = new StubResolver().mapped("m-private.test", 10, 0, 0, 5).mapped("m-loop.test", 127, 0, 0, 1)
                .mapped("m-meta.test", 169, 254, 169, 254).mapped("m-public.test", 93, 184, 216, 34);
        NetPolicy p = policy(STRICT, dns, null);
        assertThatThrownBy(() -> p.resolveChecked("m-private.test")).isInstanceOf(ToolRefusal.class).hasMessageContaining("private");
        assertThatThrownBy(() -> p.resolveChecked("m-loop.test")).isInstanceOf(ToolRefusal.class).hasMessageContaining("loopback");
        assertThatThrownBy(() -> p.resolveChecked("m-meta.test")).isInstanceOf(ToolRefusal.class).hasMessageContaining("link-local");
        assertThat(p.resolveChecked("m-public.test")).hasSize(1);
    }

    @Test
    @Tag("V3.2")
    void carrierGradeNatIsRefusedOnlyInsideItsRange() throws Exception {
        StubResolver dns = new StubResolver().answer("a.test", "100.63.255.255").answer("b.test", "100.64.0.1").answer("c.test", "100.127.255.255")
                .answer("d.test", "100.128.0.1").answer("v6.test", "2001:db8::1");
        NetPolicy p = policy(STRICT, dns, null);
        assertThat(p.resolveChecked("a.test")).hasSize(1);
        assertThatThrownBy(() -> p.resolveChecked("b.test")).hasMessageContaining("shared");
        assertThatThrownBy(() -> p.resolveChecked("c.test")).hasMessageContaining("shared");
        assertThat(p.resolveChecked("d.test")).hasSize(1);
        assertThat(p.resolveChecked("v6.test")).as("an ordinary public IPv6 address").hasSize(1);
    }

    @Test
    @Tag("V3.2")
    void anEmptyAnswerCountsAsAnUnknownHost() {
        NetPolicy.Resolver empty = host -> java.util.List.of();
        assertThatThrownBy(() -> new NetPolicy(STRICT, empty, null).resolveChecked("void.test")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    @Tag("V3.1")
    void aUserNameOrPasswordAloneIsEnoughToRefuseTheUrl() {
        NetPolicy p = policy(STRICT, new StubResolver(), null);
        assertThat(p.checkUrl(HttpUrl.parse("https://user@example.com/"))).contains("user name or password");
        assertThat(p.checkUrl(HttpUrl.parse("https://:secret@example.com/"))).contains("user name or password");
    }

    @Test
    @Tag("V3.2")
    void bracketedIpv6LiteralsAreJudgedWithoutALookup() {
        NetPolicy p = policy(STRICT, new StubResolver(), "api.example.com");
        assertThat(p.checkUrl(HttpUrl.parse("https://[fd00::1]/"))).contains("private");
        assertThat(p.checkUrl(HttpUrl.parse("https://[::ffff:10.0.0.5]/"))).contains("private");
        assertThat(p.checkUrl(HttpUrl.parse("https://[2001:db8::1]/"))).isNull();
        assertThat(policy(STRICT, new StubResolver(), "::1").checkUrl(HttpUrl.parse("http://[::1]:8080/"))).as("the declared host, as HttpUrl names it").isNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://1.2.3.4/"))).isNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://0.1.2.3/"))).contains("unspecified");
    }

    @Test
    @Tag("V3.4")
    void aWildcardNeedsARealSubdomainLabel() {
        NetPolicy p = policy(new NetPolicy.Rules(false, false, Set.of("*.example.com"), false), new StubResolver(), null);
        assertThat(p.checkUrl(HttpUrl.parse("https://x.example.com/"))).isNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://a.b.example.com/"))).isNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://xexample.com/"))).isNotNull();
        assertThat(p.checkUrl(HttpUrl.parse("https://example.com.cn/"))).isNotNull();
    }
}
