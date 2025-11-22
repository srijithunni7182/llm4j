package io.github.llm4j.agent.tools.openapi;

/**
 * Represents a parameter for an API endpoint.
 */
public class OpenAPIParameter {
    private final String name;
    private final String in; // query, path, header, cookie
    private final String description;
    private final boolean required;
    private final String type;
    private final String format;
    private final Object defaultValue;

    private OpenAPIParameter(Builder builder) {
        this.name = builder.name;
        this.in = builder.in;
        this.description = builder.description;
        this.required = builder.required;
        this.type = builder.type;
        this.format = builder.format;
        this.defaultValue = builder.defaultValue;
    }

    public String getName() {
        return name;
    }

    public String getIn() {
        return in;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }

    public String getType() {
        return type;
    }

    public String getFormat() {
        return format;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String in;
        private String description;
        private boolean required;
        private String type;
        private String format;
        private Object defaultValue;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder in(String in) {
            this.in = in;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public OpenAPIParameter build() {
            return new OpenAPIParameter(this);
        }
    }
}
