package io.github.llm4j.tools.browser;

import io.github.llm4j.tools.EgressProxy;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.ToolRefusal;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Decides every request a page makes (D16 rule 1), not just the address the agent opened: https to a
 * listed host whose addresses are public, or {@code http://127.0.0.1} on the port of a program the
 * run serves (D7's one-port grant). Loopback, private and metadata addresses are refused even for a
 * listed name. Data and blob addresses carry no request and are let through.
 */
public final class RequestGate {

    private static final Set<String> LOCAL_SCHEMES = Set.of("data", "blob", "about");

    private final EgressProxy.Hosts hosts;
    private final NetPolicy addresses;
    private final IntPredicate servedPorts;

    /**
     * @param hosts the hosts pages may load from, or null for none
     * @param resolver how names are looked up (a seam for tests)
     * @param servedPorts the one-port grant, or null when the tool reaches no served program
     */
    public RequestGate(EgressProxy.Hosts hosts, NetPolicy.Resolver resolver, IntPredicate servedPorts) {
        if (hosts == null && servedPorts == null) throw new IllegalArgumentException("a browser needs hosts or a served program to reach");
        this.hosts = hosts;
        this.addresses = new NetPolicy(new NetPolicy.Rules(false, false, Set.of(), false), Objects.requireNonNull(resolver, "resolver"), null);
        this.servedPorts = servedPorts;
    }

    /** The hosts pages may load from, or null. */
    public EgressProxy.Hosts hosts() {
        return hosts;
    }

    /** Whether the tool reaches a served program. */
    public boolean reachesServed() {
        return servedPorts != null;
    }

    /** Why {@code url} must not be loaded, or null when it may. */
    public String refusal(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "not an address";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (LOCAL_SCHEMES.contains(scheme)) return null;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (uri.getUserInfo() != null) return "an address with a user name or password";
        if (host.equals("127.0.0.1") && servedPorts != null) {
            if (!scheme.equals("http")) return "a served program is reached over http";
            int port = uri.getPort();
            return port > 0 && servedPorts.test(port) ? null : "port " + port + " is not a program this run serves now";
        }
        if (!scheme.equals("https")) return "only https is loaded (" + scheme + ":// is refused)";
        if (hosts == null) return "this browser reaches only the program the run serves";
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        if (!hosts.allows(host, port)) return host + ":" + port + " is not in this tool's hosts";
        try {
            addresses.resolveChecked(host);
        } catch (ToolRefusal e) {
            return e.getMessage().replaceFirst("^refused: ", "");
        } catch (UnknownHostException e) {
            return "unknown host " + host;
        }
        return null;
    }
}
