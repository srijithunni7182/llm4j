package io.github.llm4j.agent.tools.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretRef;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAPITool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(OpenAPITool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String name;
    private final OpenAPISpec spec;
    private final HttpClient httpClient;
    private final Map<String, String> authHeaders;
    private final Map<String, String> authQueryParams;
    /** Credentials held by reference: fetched for each request, never kept. */
    private final Map<String, SecretRef> authQuerySecrets;
    private final Map<String, SecretRef> authHeaderSecrets;

    private OpenAPITool(Builder builder) {
        this.name = builder.name;
        this.spec = builder.spec;
        this.httpClient =
                builder.httpClient != null ? builder.httpClient : HttpClient.newHttpClient();
        this.authHeaders = builder.authHeaders != null ? builder.authHeaders : new HashMap<>();
        this.authQueryParams =
                builder.authQueryParams != null ? builder.authQueryParams : new HashMap<>();
        this.authQuerySecrets = builder.authQuerySecrets != null ? builder.authQuerySecrets : new HashMap<>();
        this.authHeaderSecrets = builder.authHeaderSecrets != null ? builder.authHeaderSecrets : new HashMap<>();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        StringBuilder d = new StringBuilder();
        d.append("Calls the ").append(spec.getTitle() != null ? spec.getTitle() : "HTTP").append(" API");
        if (spec.getDescription() != null && !spec.getDescription().isBlank()) {
            d.append(" (").append(spec.getDescription().strip()).append(")");
        }
        d.append(". Arguments: {\"endpoint\": \"<path>\", \"method\": \"GET|POST|…\", "
                + "\"parameters\": {<name>: <value>}, \"body\": {…}}. Endpoints:");
        for (OpenAPIEndpoint e : spec.getEndpoints()) {
            d.append("\n- ").append(e.getMethod()).append(' ').append(e.getPath());
            String what = e.getSummary() != null ? e.getSummary() : e.getDescription();
            if (what != null && !what.isBlank()) d.append(": ").append(what.strip());
            if (e.getParameters() != null && !e.getParameters().isEmpty()) {
                d.append(" [params: ");
                d.append(String.join(", ", e.getParameters().stream()
                        .map(p -> p.getName() + " (" + p.getIn() + (p.getType() != null ? ", " + p.getType() : "") + ")")
                        .toList()));
                d.append("]");
            }
        }
        return d.toString();
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            if (!(args.get("endpoint") instanceof String)
                    || !(args.get("method") instanceof String)) {
                return "Error: 'endpoint' and 'method' arguments must be strings";
            }
            String endpointPath = (String) args.get("endpoint");
            String method = (String) args.get("method");

            @SuppressWarnings("unchecked")
            Map<String, Object> parameters =
                    (Map<String, Object>) args.getOrDefault("parameters", new HashMap<>());

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) args.get("body");

            OpenAPIEndpoint endpoint = findEndpoint(endpointPath, method);
            if (endpoint == null) {
                return String.format(
                        "Error: Endpoint '%s %s' not found in API specification",
                        method, endpointPath);
            }

            return executeRequest(endpoint, parameters, body);
        } catch (Exception e) {
            logger.error("Error executing API request", e);
            return "Error executing API request: " + e.getMessage();
        }
    }

    private OpenAPIEndpoint findEndpoint(String path, String method) {
        return spec.getEndpoints().stream()
                .filter(e -> e.getPath().equals(path) && e.getMethod().equalsIgnoreCase(method))
                .findFirst()
                .orElse(null);
    }

    private String executeRequest(
            OpenAPIEndpoint endpoint, Map<String, Object> parameters, Map<String, Object> body)
            throws Exception {
        // Validation for required parameters
        if (endpoint.getParameters() != null) {
            for (OpenAPIParameter param : endpoint.getParameters()) {
                if (param.isRequired() && !parameters.containsKey(param.getName())) {
                    return String.format("Error: Missing required parameter '%s'", param.getName());
                }
            }
        }

        String baseUrl = spec.getServers().isEmpty() ? "" : spec.getServers().get(0);
        String path = endpoint.getPath();
        Map<String, String> pathParams = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>(authQueryParams);
        String requestHost = SecretMetadata.hostOf(baseUrl);
        // every credential this request carries, so none can come back out in a log line or an error
        java.util.List<String> credentials = new java.util.ArrayList<>(authQueryParams.values());
        credentials.addAll(authHeaders.values());
        try {
            for (Map.Entry<String, SecretRef> secret : authQuerySecrets.entrySet()) {
                String value = secret.getValue().resolveFor(requestHost);
                queryParams.put(secret.getKey(), value);
                credentials.add(value);
            }
        } catch (SecretException e) {
            return "Error: credential unavailable: " + e.getMessage();
        }

        if (endpoint.getParameters() != null) {
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
        }

        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        if (!queryParams.isEmpty()) {
            String queryString =
                    queryParams.entrySet().stream()
                            .map(
                                    e ->
                                            e.getKey()
                                                    + "="
                                                    + URLEncoder.encode(
                                                            e.getValue(), StandardCharsets.UTF_8))
                            .collect(Collectors.joining("&"));
            path += "?" + queryString;
        }

        String fullUrl = baseUrl + path;
        // not the query string: it can carry an API key, and logs outlive the run
        logger.info(
                "Executing OpenAPI Tool request to URL: {} with method: {}",
                fullUrl.contains("?") ? fullUrl.substring(0, fullUrl.indexOf('?')) + "?..." : fullUrl,
                endpoint.getMethod());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(fullUrl));
        authHeaders.forEach(requestBuilder::header);
        try {
            for (Map.Entry<String, SecretRef> secret : authHeaderSecrets.entrySet()) {
                String value = secret.getValue().resolveFor(requestHost);
                requestBuilder.header(secret.getKey(), value);
                credentials.add(value);
                int space = value.indexOf(' ');
                if (space > 0) credentials.add(value.substring(space + 1)); // "Bearer <token>": the token alone
            }
        } catch (SecretException e) {
            return "Error: credential unavailable: " + e.getMessage();
        }

        switch (endpoint.getMethod().toUpperCase()) {
            case "GET":
                requestBuilder.GET();
                break;
            case "POST":
                String requestBody = body != null ? objectMapper.writeValueAsString(body) : "";
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody));
                requestBuilder.header("Content-Type", "application/json");
                break;
            case "PUT":
                String putBody = body != null ? objectMapper.writeValueAsString(body) : "";
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(putBody));
                requestBuilder.header("Content-Type", "application/json");
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            default:
                return "Error: Unsupported HTTP method: " + endpoint.getMethod();
        }

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String safeBody = scrub(response.body(), credentials);
        logger.info(
                "OpenAPI Tool Response - Status: {}, Body: {}",
                response.statusCode(),
                safeBody);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String responseBody = safeBody;
            if (responseBody.length() > 2000) {
                return responseBody.substring(0, 2000) + "\n... (truncated due to length)";
            }
            return responseBody;
        } else {
            return String.format(
                    "Error: API returned status code %d. Body: %s",
                    response.statusCode(), safeBody);
        }
    }

    private static String scrub(String text, java.util.List<String> credentials) {
        String out = text == null ? "" : text;
        for (String c : credentials) {
            if (c != null && c.length() >= 6) out = out.replace(c, "***");
        }
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private OpenAPISpec spec;
        private HttpClient httpClient;
        private Map<String, String> authHeaders;
        private Map<String, String> authQueryParams;
        private Map<String, SecretRef> authQuerySecrets;
        private Map<String, SecretRef> authHeaderSecrets;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder spec(OpenAPISpec spec) {
            this.spec = spec;
            return this;
        }

        public Builder httpClient(HttpClient client) {
            this.httpClient = client;
            return this;
        }

        public Builder apiKeyAuth(String paramName, String apiKey) {
            if (this.authQueryParams == null) this.authQueryParams = new HashMap<>();
            this.authQueryParams.put(paramName, apiKey);
            return this;
        }

        /** An API key kept in a {@link io.github.llm4j.secret.SecretStore}: fetched for each request and not held by the tool. */
        public Builder apiKeyAuth(String paramName, SecretRef apiKey) {
            if (this.authQuerySecrets == null) this.authQuerySecrets = new HashMap<>();
            this.authQuerySecrets.put(paramName, apiKey);
            return this;
        }

        /** A header credential (for example {@code Authorization} with its whole value) kept in a store, fetched for each request. */
        public Builder headerAuth(String headerName, SecretRef value) {
            if (this.authHeaderSecrets == null) this.authHeaderSecrets = new HashMap<>();
            this.authHeaderSecrets.put(headerName, value);
            return this;
        }

        public Builder headerAuth(String headerName, String value) {
            if (this.authHeaders == null) this.authHeaders = new HashMap<>();
            this.authHeaders.put(headerName, value);
            return this;
        }

        public OpenAPITool build() {
            if (name == null) throw new IllegalArgumentException("name is required");
            if (spec == null) throw new IllegalArgumentException("spec is required");
            return new OpenAPITool(this);
        }
    }
}
