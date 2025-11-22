package io.github.llm4j.agent.tools.openapi;

import io.github.llm4j.agent.Tool;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dynamic tool that uses OpenAPI specifications to discover and execute API
 * endpoints.
 */
public class OpenAPITool implements Tool {

    private final String name;
    private final OpenAPISpec spec;
    private final HttpClient httpClient;
    private final Map<String, String> authHeaders;
    private final Map<String, String> authQueryParams;

    private OpenAPITool(Builder builder) {
        this.name = builder.name;
        this.spec = builder.spec;
        this.httpClient = HttpClient.newHttpClient();
        this.authHeaders = builder.authHeaders != null ? builder.authHeaders : new HashMap<>();
        this.authQueryParams = builder.authQueryParams != null ? builder.authQueryParams : new HashMap<>();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        StringBuilder description = new StringBuilder();
        description.append(String.format("API: %s (v%s)\n", spec.getTitle(), spec.getVersion()));

        if (spec.getDescription() != null && !spec.getDescription().isEmpty()) {
            description.append(spec.getDescription()).append("\n\n");
        }

        description.append("Available endpoints:\n\n");

        for (OpenAPIEndpoint endpoint : spec.getEndpoints()) {
            description.append(String.format("- %s %s", endpoint.getMethod(), endpoint.getPath()));

            if (endpoint.getSummary() != null) {
                description.append(": ").append(endpoint.getSummary());
            }
            description.append("\n");

            if (endpoint.getDescription() != null && !endpoint.getDescription().isEmpty()) {
                description.append("  ").append(endpoint.getDescription()).append("\n");
            }

            if (!endpoint.getParameters().isEmpty()) {
                description.append("  Parameters:\n");
                for (OpenAPIParameter param : endpoint.getParameters()) {
                    description.append(String.format("    - %s (%s, %s): %s\n",
                            param.getName(),
                            param.getIn(),
                            param.isRequired() ? "required" : "optional",
                            param.getDescription() != null ? param.getDescription() : ""));
                }
            }
            description.append("\n");
        }

        description.append("\nInput format: Provide a JSON object with:\n");
        description.append("- 'endpoint': The endpoint path (e.g., '/flights')\n");
        description.append("- 'method': HTTP method (e.g., 'GET', 'POST')\n");
        description.append("- 'parameters': Object with parameter values\n\n");
        description.append(
                "Example: {\"endpoint\": \"/flights\", \"method\": \"GET\", \"parameters\": {\"dep_iata\": \"BLR\"}}");

        return description.toString();
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            // Extract endpoint, method, and parameters from args
            String endpointPath = (String) args.get("endpoint");
            String method = (String) args.get("method");
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) args.getOrDefault("parameters", new HashMap<>());

            if (endpointPath == null || method == null) {
                return "Error: 'endpoint' and 'method' are required fields";
            }

            // Find matching endpoint in spec
            OpenAPIEndpoint endpoint = findEndpoint(endpointPath, method);
            if (endpoint == null) {
                return String.format("Error: Endpoint '%s %s' not found in API specification", method, endpointPath);
            }

            // Build and execute request
            return executeRequest(endpoint, parameters);

        } catch (Exception e) {
            return "Error executing API request: " + e.getMessage();
        }
    }

    private OpenAPIEndpoint findEndpoint(String path, String method) {
        return spec.getEndpoints().stream()
                .filter(e -> e.getPath().equals(path) && e.getMethod().equalsIgnoreCase(method))
                .findFirst()
                .orElse(null);
    }

    private String executeRequest(OpenAPIEndpoint endpoint, Map<String, Object> parameters) throws Exception {
        String baseUrl = spec.getServers().isEmpty() ? "" : spec.getServers().get(0);
        String path = endpoint.getPath();

        // Separate path and query parameters
        Map<String, String> pathParams = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>(authQueryParams);

        for (OpenAPIParameter param : endpoint.getParameters()) {
            Object value = parameters.get(param.getName());
            if (value != null) {
                String strValue = value.toString();
                if ("path".equals(param.getIn())) {
                    pathParams.put(param.getName(), strValue);
                } else if ("query".equals(param.getIn())) {
                    queryParams.put(param.getName(), strValue);
                }
            }
        }

        // Replace path parameters
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        // Build query string
        if (!queryParams.isEmpty()) {
            String queryString = queryParams.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            path += "?" + queryString;
        }

        String fullUrl = baseUrl + path;

        System.out.println("=== OpenAPI Tool Request ===");
        System.out.println("URL: " + fullUrl.replace(authQueryParams.values().stream().findFirst().orElse(""), "***"));
        System.out.println("Method: " + endpoint.getMethod());
        System.out.println("============================");

        // Build HTTP request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl));

        // Add auth headers
        authHeaders.forEach(requestBuilder::header);

        // Set method
        switch (endpoint.getMethod().toUpperCase()) {
            case "GET":
                requestBuilder.GET();
                break;
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
                break;
            case "PUT":
                requestBuilder.PUT(HttpRequest.BodyPublishers.noBody());
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            default:
                return "Error: Unsupported HTTP method: " + endpoint.getMethod();
        }

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("=== OpenAPI Tool Response ===");
        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body());
        System.out.println("=============================");

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String body = response.body();
            if (body.length() > 2000) {
                return body.substring(0, 2000) + "\n... (truncated due to length)";
            }
            return body;
        } else {
            return String.format("Error: API returned status code %d. Body: %s",
                    response.statusCode(), response.body());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String specLocation;
        private OpenAPISpec spec;
        private Map<String, String> authHeaders;
        private Map<String, String> authQueryParams;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder specLocation(String specLocation) {
            this.specLocation = specLocation;
            return this;
        }

        public Builder spec(OpenAPISpec spec) {
            this.spec = spec;
            return this;
        }

        public Builder apiKeyAuth(String paramName, String apiKey) {
            if (this.authQueryParams == null) {
                this.authQueryParams = new HashMap<>();
            }
            this.authQueryParams.put(paramName, apiKey);
            return this;
        }

        public Builder headerAuth(String headerName, String value) {
            if (this.authHeaders == null) {
                this.authHeaders = new HashMap<>();
            }
            this.authHeaders.put(headerName, value);
            return this;
        }

        public OpenAPITool build() {
            if (name == null) {
                throw new IllegalArgumentException("name is required");
            }

            if (spec == null && specLocation != null) {
                spec = OpenAPIParser.parse(specLocation);
            }

            if (spec == null) {
                throw new IllegalArgumentException("spec or specLocation is required");
            }

            return new OpenAPITool(this);
        }
    }
}
