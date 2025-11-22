package io.github.llm4j.agent.tools.openapi;

import java.util.List;
import java.util.Map;

/**
 * Represents a single API endpoint from an OpenAPI specification.
 */
public class OpenAPIEndpoint {
    private final String path;
    private final String method;
    private final String operationId;
    private final String summary;
    private final String description;
    private final List<OpenAPIParameter> parameters;
    private final Map<String, Object> requestBodySchema;
    private final Map<String, Object> responseSchema;

    private OpenAPIEndpoint(Builder builder) {
        this.path = builder.path;
        this.method = builder.method;
        this.operationId = builder.operationId;
        this.summary = builder.summary;
        this.description = builder.description;
        this.parameters = builder.parameters;
        this.requestBodySchema = builder.requestBodySchema;
        this.responseSchema = builder.responseSchema;
    }

    public String getPath() {
        return path;
    }

    public String getMethod() {
        return method;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public List<OpenAPIParameter> getParameters() {
        return parameters;
    }

    public Map<String, Object> getRequestBodySchema() {
        return requestBodySchema;
    }

    public Map<String, Object> getResponseSchema() {
        return responseSchema;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String path;
        private String method;
        private String operationId;
        private String summary;
        private String description;
        private List<OpenAPIParameter> parameters;
        private Map<String, Object> requestBodySchema;
        private Map<String, Object> responseSchema;

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder operationId(String operationId) {
            this.operationId = operationId;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameters(List<OpenAPIParameter> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder requestBodySchema(Map<String, Object> requestBodySchema) {
            this.requestBodySchema = requestBodySchema;
            return this;
        }

        public Builder responseSchema(Map<String, Object> responseSchema) {
            this.responseSchema = responseSchema;
            return this;
        }

        public OpenAPIEndpoint build() {
            return new OpenAPIEndpoint(this);
        }
    }
}
