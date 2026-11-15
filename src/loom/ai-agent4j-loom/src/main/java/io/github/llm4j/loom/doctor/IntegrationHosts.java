package io.github.llm4j.loom.doctor;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.ast.ToolDef;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The hosts a script's integrations reach, read from the script alone (phase 36e, D12): an {@code
 * http} tool's {@code base_url}, an {@code openapi} tool's description and server addresses, and a
 * remote {@code mcp} server's {@code url}. {@code weave doctor --network} makes one TLS connection to
 * each: no request, and no credential is ever sent.
 */
public final class IntegrationHosts {

    private IntegrationHosts() {}

    /** The hosts, sorted and without duplicates; an address that is not https is left out. */
    public static List<String> of(LoomScript script) {
        TreeSet<String> hosts = new TreeSet<>();
        for (ToolDef t : script.getTools()) {
            if (!List.of("http", "openapi").contains(t.getKind())) continue;
            for (String option : List.of("base_url", "server", "spec")) {
                ToolDef.OptionValue v = t.getOptions().get(option);
                if (v != null && !v.isReference()) add(hosts, v.value());
            }
        }
        for (McpServerDef m : script.getMcpServers()) if (m.isRemote()) add(hosts, m.getUrl());
        return List.copyOf(hosts);
    }

    private static void add(TreeSet<String> hosts, String address) {
        if (address == null || !address.regionMatches(true, 0, "https://", 0, 8)) return;
        try {
            String host = URI.create(address.strip()).getHost();
            if (host != null) hosts.add(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException notAnAddress) {
            // an address that does not parse is reported by weave check, not by the network probe
        }
    }
}
