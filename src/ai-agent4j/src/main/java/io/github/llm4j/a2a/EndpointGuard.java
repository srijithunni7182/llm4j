package io.github.llm4j.a2a;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chooses the endpoint to use from a card, and refuses one that points off the host the script
 * declared. The check is on data, so it is llm4j's; redirects, private addresses and TLS belong to
 * the transport.
 */
public final class EndpointGuard {

    private EndpointGuard() {}

    /**
     * Picks the first endpoint the transport supports and checks it is on a declared host.
     *
     * @param declaredCardAddress the https card address from the script
     * @param extraHosts further hosts the declaration allows
     * @param card the card as fetched
     * @param transport says which endpoints it can speak to
     * @return the endpoint to use
     * @throws A2AException.Refused if the card points elsewhere or offers nothing supported
     */
    public static AgentCardView.Endpoint select(
            String declaredCardAddress,
            Set<String> extraHosts,
            AgentCardView card,
            A2ATransport transport) {
        Objects.requireNonNull(declaredCardAddress, "declaredCardAddress");
        Objects.requireNonNull(extraHosts, "extraHosts");
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(transport, "transport");
        String declaredHost = httpsHost(declaredCardAddress, "the declared card address");
        Set<String> allowed = new java.util.HashSet<>(Set.of(declaredHost));
        for (String host : extraHosts) {
            allowed.add(host.toLowerCase(Locale.ROOT));
        }
        for (AgentCardView.Endpoint endpoint : card.endpoints()) {
            if (!transport.supports(endpoint)) {
                continue;
            }
            String host = httpsHost(endpoint.url(), "the card's address " + endpoint.url());
            if (!allowed.contains(host)) {
                throw new A2AException.Refused(
                        "the card of '"
                                + card.name()
                                + "' points at the host "
                                + host
                                + ", which the script did not declare (declared: "
                                + String.join(", ", new java.util.TreeSet<>(allowed))
                                + "); nothing was sent. Declare the host, or do not call this agent");
            }
            return endpoint;
        }
        String offered =
                card.endpoints().isEmpty()
                        ? "no endpoints"
                        : card.endpoints().stream()
                                .map(e -> e.binding() + " " + e.protocolVersion())
                                .collect(Collectors.joining(", "));
        throw new A2AException.Refused(
                "the card of '"
                        + card.name()
                        + "' offers no endpoint this transport supports (offered: "
                        + offered
                        + "); nothing was sent. Use a transport for one of these, or call another agent");
    }

    /**
     * Checks that an address is a usable https address and returns its host in lower case.
     *
     * @param address the address
     * @param what how to describe it in the message
     * @throws A2AException.Refused if it is not https, carries a user name, or has no host
     */
    static String requireHttps(String address, String what) {
        return httpsHost(address, what);
    }

    private static String httpsHost(String address, String what) {
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            throw new A2AException.Refused(what + " is not a valid address; use an https address");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new A2AException.Refused(what + " must use https; got '" + uri.getScheme() + "'");
        }
        if (uri.getRawUserInfo() != null) {
            throw new A2AException.Refused(what + " must not carry a user name or password");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new A2AException.Refused(what + " has no host; use an https address");
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
