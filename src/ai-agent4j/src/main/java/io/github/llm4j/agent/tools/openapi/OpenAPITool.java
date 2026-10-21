package io.github.llm4j.agent.tools.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretRef;
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
    private final RequestAuth auth;

    private OpenAPITool(Builder builder) {
        this.name = builder.name;
        this.spec = builder.spec;
        this.httpClient =
                builder.httpClient != null ? builder.httpClient : HttpClient.newHttpClient();
        this.auth = builder.auth;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        StringBuilder d = new StringBuilder();
        d.append("Calls the ")
                .append(spec.getTitle() != null ? spec.getTitle() : "HTTP")
                .append(" API");
        if (spec.getDescription() != null && !spec.getDescription().isBlank()) {
            d.append(" (").append(spec.getDescription().strip()).append(")");
        }
        d.append(
                ". Arguments: {\"endpoint\": \"<path>\", \"method\": \"GET|POST|…\", "
                        + "\"parameters\": {<name>: <value>}, \"body\": {…}}. Endpoints:");
        for (OpenAPIEndpoint e : spec.getEndpoints()) {
            d.append("\n- ").append(e.getMethod()).append(' ').append(e.getPath());
            String what = e.getSummary() != null ? e.getSummary() : e.getDescription();
            if (what != null && !what.isBlank()) d.append(": ").append(what.strip());
            if (e.getParameters() != null && !e.getParameters().isEmpty()) {
                d.append(" [params: ");
                d.append(
                        String.join(
                                ", ",
                                e.getParameters().stream()
                                        .map(
                                                p ->
                                                        p.getName()
                                                                + " ("
                                                                + p.getIn()
                                                                + (p.getType() != null
                                                                        ? ", " + p.getType()
                                                                        : "")
                                                                + ")")
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
        if (!SUPPORTED_METHODS.contains(endpoint.getMethod().toUpperCase())) {
            return "Error: Unsupported HTTP method: " + endpoint.getMethod();
        }

        String baseUrl = spec.getServers().isEmpty() ? "" : spec.getServers().get(0);
        try {
            Exchange exchange = send(endpoint, parameters, body, baseUrl);
            if (exchange.refused()) {
                // a credential the server refuses may have expired since it was fetched: ask again
                // once, then give the answer as it is
                exchange.credentials().rejected();
                exchange = send(endpoint, parameters, body, baseUrl);
            }
            return render(exchange.response(), exchange.credentials().secrets());
        } catch (SecretException e) {
            return "Error: credential unavailable: " + e.getMessage();
        }
    }

    /** One request and what it was sent with. */
    private record Exchange(HttpResponse<String> response, RequestAuth.Resolved credentials) {
        /** The server said 401 to a credential that could now be different. */
        boolean refused() {
            return response.statusCode() == 401 && credentials.canRetry();
        }
    }

    private Exchange send(
            OpenAPIEndpoint endpoint,
            Map<String, Object> parameters,
            Map<String, Object> body,
            String baseUrl)
            throws Exception {
        RequestAuth.Resolved credentials = auth.resolve(baseUrl);
        HttpRequest request = buildRequest(endpoint, parameters, body, baseUrl, credentials);
        return new Exchange(
                httpClient.send(request, HttpResponse.BodyHandlers.ofString()), credentials);
    }

    private static final java.util.Set<String> SUPPORTED_METHODS =
            java.util.Set.of("GET", "POST", "PUT", "DELETE");

    private HttpRequest buildRequest(
            OpenAPIEndpoint endpoint,
            Map<String, Object> parameters,
            Map<String, Object> body,
            String baseUrl,
            RequestAuth.Resolved credentials)
            throws Exception {
        String path = endpoint.getPath();
        Map<String, String> pathParams = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>(credentials.query());

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
                fullUrl.contains("?")
                        ? fullUrl.substring(0, fullUrl.indexOf('?')) + "?..."
                        : fullUrl,
                endpoint.getMethod());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(fullUrl));
        credentials.headers().forEach(requestBuilder::header);

        switch (endpoint.getMethod().toUpperCase()) {
            case "GET":
                requestBuilder.GET();
                break;
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonOf(body)));
                requestBuilder.header("Content-Type", "application/json");
                break;
            case "PUT":
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(jsonOf(body)));
                requestBuilder.header("Content-Type", "application/json");
                break;
            default:
                requestBuilder.DELETE();
                break;
        }
        return requestBuilder.build();
    }

    private static String jsonOf(Map<String, Object> body) throws Exception {
        return body != null ? objectMapper.writeValueAsString(body) : "";
    }

    private static String render(
            HttpResponse<String> response, java.util.List<String> credentials) {
        String safeBody = scrub(response.body(), credentials);
        logger.info(
                "OpenAPI Tool Response - Status: {}, Body: {}", response.statusCode(), safeBody);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            if (safeBody.length() > 2000) {
                return safeBody.substring(0, 2000) + "\n... (truncated due to length)";
            }
            return safeBody;
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
        private final RequestAuth auth = new RequestAuth();

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
            auth.query(paramName, apiKey);
            return this;
        }

        /**
         * An API key kept in a {@link io.github.llm4j.secret.SecretStore}: fetched for each request
         * and not held by the tool.
         */
        public Builder apiKeyAuth(String paramName, SecretRef apiKey) {
            auth.query(paramName, apiKey);
            return this;
        }

        /**
         * An API key asked for on each request, for instance the token of the person the run is
         * for. It is only sent over https (or to this machine); a 401 makes the tool call {@link
         * CredentialSource#rejected()}, ask again and repeat the request once.
         */
        public Builder apiKeyAuth(String paramName, CredentialSource apiKey) {
            auth.query(paramName, apiKey);
            return this;
        }

        /**
         * A header credential (for example {@code Authorization} with its whole value) kept in a
         * store, fetched for each request.
         */
        public Builder headerAuth(String headerName, SecretRef value) {
            auth.header(headerName, value);
            return this;
        }

        /** As {@link #apiKeyAuth(String, CredentialSource)}, for a header. */
        public Builder headerAuth(String headerName, CredentialSource value) {
            auth.header(headerName, value);
            return this;
        }

        public Builder headerAuth(String headerName, String value) {
            auth.header(headerName, value);
            return this;
        }

        public OpenAPITool build() {
            if (name == null) throw new IllegalArgumentException("name is required");
            if (spec == null) throw new IllegalArgumentException("spec is required");
            return new OpenAPITool(this);
        }
    }
}
