package io.github.llm4j.agent.tools.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parser for OpenAPI/Swagger specifications.
 */
public class OpenAPIParser {

    /**
     * Parse an OpenAPI specification from a URL, file path, or JSON/YAML string.
     *
     * @param location URL, file path, or spec content
     * @return parsed OpenAPI specification
     * @throws IllegalArgumentException if spec cannot be parsed
     */
    public static OpenAPISpec parse(String location) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location, null, options);

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            throw new IllegalArgumentException("Failed to parse OpenAPI spec: " +
                    String.join(", ", result.getMessages()));
        }

        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalArgumentException("Failed to parse OpenAPI spec from: " + location);
        }

        return buildSpec(openAPI);
    }

    private static OpenAPISpec buildSpec(OpenAPI openAPI) {
        // Extract servers
        List<String> servers = new ArrayList<>();
        if (openAPI.getServers() != null) {
            servers = openAPI.getServers().stream()
                    .map(Server::getUrl)
                    .collect(Collectors.toList());
        }

        // Extract endpoints
        List<OpenAPIEndpoint> endpoints = new ArrayList<>();
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) -> {
                endpoints.addAll(extractEndpoints(path, pathItem));
            });
        }

        // Extract security schemes
        Map<String, Object> securitySchemes = new HashMap<>();
        if (openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null) {
            openAPI.getComponents().getSecuritySchemes().forEach((name, scheme) -> {
                Map<String, Object> schemeMap = new HashMap<>();
                schemeMap.put("type", scheme.getType() != null ? scheme.getType().toString() : null);
                schemeMap.put("in", scheme.getIn() != null ? scheme.getIn().toString() : null);
                schemeMap.put("name", scheme.getName());
                securitySchemes.put(name, schemeMap);
            });
        }

        return OpenAPISpec.builder()
                .title(openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : "Unknown")
                .version(openAPI.getInfo() != null ? openAPI.getInfo().getVersion() : "1.0.0")
                .description(openAPI.getInfo() != null ? openAPI.getInfo().getDescription() : "")
                .servers(servers)
                .endpoints(endpoints)
                .securitySchemes(securitySchemes)
                .build();
    }

    private static List<OpenAPIEndpoint> extractEndpoints(String path, PathItem pathItem) {
        List<OpenAPIEndpoint> endpoints = new ArrayList<>();

        if (pathItem.getGet() != null) {
            endpoints.add(buildEndpoint(path, "GET", pathItem.getGet()));
        }
        if (pathItem.getPost() != null) {
            endpoints.add(buildEndpoint(path, "POST", pathItem.getPost()));
        }
        if (pathItem.getPut() != null) {
            endpoints.add(buildEndpoint(path, "PUT", pathItem.getPut()));
        }
        if (pathItem.getDelete() != null) {
            endpoints.add(buildEndpoint(path, "DELETE", pathItem.getDelete()));
        }
        if (pathItem.getPatch() != null) {
            endpoints.add(buildEndpoint(path, "PATCH", pathItem.getPatch()));
        }

        return endpoints;
    }

    private static OpenAPIEndpoint buildEndpoint(String path, String method, Operation operation) {
        List<OpenAPIParameter> parameters = new ArrayList<>();

        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                Schema schema = param.getSchema();
                parameters.add(OpenAPIParameter.builder()
                        .name(param.getName())
                        .in(param.getIn())
                        .description(param.getDescription())
                        .required(param.getRequired() != null ? param.getRequired() : false)
                        .type(schema != null ? schema.getType() : "string")
                        .format(schema != null ? schema.getFormat() : null)
                        .build());
            }
        }

        return OpenAPIEndpoint.builder()
                .path(path)
                .method(method)
                .operationId(operation.getOperationId())
                .summary(operation.getSummary())
                .description(operation.getDescription())
                .parameters(parameters)
                .build();
    }
}
