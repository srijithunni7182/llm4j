package io.github.llm4j.loom.tools.generic;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.HttpUrl;

/**
 * Which network addresses a tool may contact: https only, no private or metadata addresses, an optional host
 * allow-list. A model that has been talked into calling somewhere it shouldn't meets this first.
 *
 * <p>The address check is separate from the connection: {@link #resolveChecked} returns the addresses it
 * approved, and the caller connects to exactly those (so DNS can't answer differently the second time).
 */
public final class NetPolicy {

    /** Looks up a host. A seam: tests answer from a table. */
    @FunctionalInterface
    public interface Resolver {
        Resolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /** What the tool's declaration allows. */
    public record Rules(boolean allowHttp, boolean allowPrivate, Set<String> hosts, boolean followRedirects) {
        public Rules {
            hosts = Set.copyOf(hosts);
        }
    }

    private final Rules rules;
    private final Resolver resolver;
    private final String configuredHost;

    /**
     * @param configuredHost the host the declaration itself names (a webhook's host, an http tool's base host):
     *                       always allowed, and when it is {@code localhost} or a loopback address, loopback
     *                       connections to it are allowed too
     */
    public NetPolicy(Rules rules, Resolver resolver, String configuredHost) {
        this.rules = rules;
        this.resolver = resolver;
        this.configuredHost = configuredHost == null ? null : configuredHost.toLowerCase(Locale.ROOT);
    }

    public Rules rules() {
        return rules;
    }

    /** The first thing wrong with the URL on its face (scheme, credentials, host list), or null. */
    public String checkUrl(HttpUrl url) {
        String host = url.host().toLowerCase(Locale.ROOT);
        if (!url.username().isEmpty() || !url.password().isEmpty()) return "the URL must not carry a user name or password";
        boolean loopbackHost = isLoopbackName(host);
        if (!url.isHttps() && !(rules.allowHttp() || loopbackHost)) {
            return "only https is allowed (http needs allow_http: true, or a localhost address)";
        }
        if (!hostAllowed(host)) return "host " + host + " is not in this tool's hosts: list";
        InetAddress literal = literalAddress(host);
        if (literal != null && !rules.allowPrivate()) {
            String why = forbidden(unmap(literal), loopbackAllowedFor(host));
            if (why != null) return "the address is " + why + " address (allow_private: true permits it)";
        }
        return null;
    }

    /** The address if {@code host} is an IP literal (no lookup is made), else null. */
    private static InetAddress literalAddress(String host) {
        boolean v4 = host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = host.contains(":");
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Resolves {@code host} and refuses it if any address is one the policy forbids.
     *
     * @throws ToolRefusal if the host resolves to a forbidden address
     * @throws UnknownHostException if it doesn't resolve
     */
    public List<InetAddress> resolveChecked(String host) throws UnknownHostException {
        List<InetAddress> addresses = resolver.resolve(host);
        if (addresses.isEmpty()) throw new UnknownHostException(host);
        if (!rules.allowPrivate()) {
            boolean loopbackOk = loopbackAllowedFor(host);
            for (InetAddress a : addresses) {
                String why = forbidden(unmap(a), loopbackOk);
                if (why != null) throw new ToolRefusal("refused: the host resolves to " + why + " address");
            }
        }
        return new ArrayList<>(addresses);
    }

    private boolean hostAllowed(String host) {
        if (rules.hosts().isEmpty()) return true;
        if (host.equals(configuredHost)) return true;
        for (String pattern : rules.hosts()) {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.startsWith("*.") ? host.endsWith(p.substring(1)) && host.length() > p.length() - 1 : host.equals(p)) return true;
        }
        return false;
    }

    private boolean loopbackAllowedFor(String host) {
        return configuredHost != null && host.equalsIgnoreCase(configuredHost) && isLoopbackName(configuredHost);
    }

    static boolean isLoopbackName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    /** An IPv4-mapped IPv6 address ({@code ::ffff:10.0.0.5}) is the IPv4 address it wraps. */
    static InetAddress unmap(InetAddress a) {
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = b.length == 16;
            for (int i = 0; mapped && i < 10; i++) mapped = b[i] == 0;
            mapped = mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
            if (mapped) {
                try {
                    return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return a;
    }

    /** Why the address is off limits ("a private", "a loopback"…), or null. */
    static String forbidden(InetAddress a, boolean loopbackOk) {
        if (a.isLoopbackAddress()) return loopbackOk ? null : "a loopback";
        if (a.isAnyLocalAddress()) return "an unspecified";
        if (a.isLinkLocalAddress()) return "a link-local (metadata)";
        if (a.isSiteLocalAddress()) return "a private";
        if (a.isMulticastAddress()) return "a multicast";
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return "a private (unique-local)";
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0) return "an unspecified";
            if (first == 100 && second >= 64 && second <= 127) return "a shared (carrier-grade NAT)";
        }
        return null;
    }
}
