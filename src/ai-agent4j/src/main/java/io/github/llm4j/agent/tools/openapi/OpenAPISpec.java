package io.github.llm4j.agent.tools.openapi;

import java.util.List;
import java.util.Map;

/**
 * Represents a parsed OpenAPI specification.
 *
 * @deprecated since 6.2.0, for removal at the next major version. Its replacement is the {@code
 *     openapi} tool kind of the {@code ai-agent4j-tools-openapi} module ({@code OpenApiKind}, one tool
 *     per operation), which sends every request through the shared HTTP layer: the address rules of
 *     {@code NetPolicy}, a timeout, a size cap and retries. This class uses a bare {@code HttpClient}
 *     with none of them, and its parser fetches remote descriptions and references on its own. See
 *     docs/API_COMPATIBILITY.md, "Integrations (phase 36)", for the migration.
 */
@Deprecated(since = "6.2.0", forRemoval = true)
public class OpenAPISpec {
    private final String title;
    private final String version;
    private final String description;
    private final List<String> servers;
    private final List<OpenAPIEndpoint> endpoints;
    private final Map<String, Object> securitySchemes;

    private OpenAPISpec(Builder builder) {
        this.title = builder.title;
        this.version = builder.version;
        this.description = builder.description;
        this.servers = builder.servers;
        this.endpoints = builder.endpoints;
        this.securitySchemes = builder.securitySchemes;
    }

    public String getTitle() {
        return title;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getServers() {
        return servers;
    }

    public List<OpenAPIEndpoint> getEndpoints() {
        return endpoints;
    }

    public Map<String, Object> getSecuritySchemes() {
        return securitySchemes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String title;
        private String version;
        private String description;
        private List<String> servers;
        private List<OpenAPIEndpoint> endpoints;
        private Map<String, Object> securitySchemes;

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder servers(List<String> servers) {
            this.servers = servers;
            return this;
        }

        public Builder endpoints(List<OpenAPIEndpoint> endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder securitySchemes(Map<String, Object> securitySchemes) {
            this.securitySchemes = securitySchemes;
            return this;
        }

        public OpenAPISpec build() {
            return new OpenAPISpec(this);
        }
    }
}
