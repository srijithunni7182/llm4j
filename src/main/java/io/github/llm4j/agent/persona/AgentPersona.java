package io.github.llm4j.agent.persona;

import java.util.*;

/**
 * Represents an agent persona that defines behavioral characteristics,
 * expertise, and constraints for a ReAct agent.
 * <p>
 * A persona makes agent behavior more deterministic and role-specific by
 * influencing the system prompt and decision-making process.
 */
public class AgentPersona {

    private final String name;
    private final String role;
    private final String expertise;
    private final String tone;
    private final List<String> constraints;
    private final Map<String, String> customAttributes;

    private AgentPersona(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.role = builder.role;
        this.expertise = builder.expertise;
        this.tone = builder.tone;
        this.constraints = Collections.unmodifiableList(new ArrayList<>(builder.constraints));
        this.customAttributes = Collections.unmodifiableMap(new HashMap<>(builder.customAttributes));
    }

    /**
     * Converts this persona into a system prompt addition that can be prepended
     * to the agent's system prompt.
     *
     * @return formatted persona description for system prompt
     */
    public String toSystemPromptAddition() {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are ").append(name);

        if (role != null && !role.isEmpty()) {
            prompt.append(", a ").append(role);
        }

        prompt.append(".");

        if (expertise != null && !expertise.isEmpty()) {
            prompt.append("\n\nYour expertise: ").append(expertise);
        }

        if (tone != null && !tone.isEmpty()) {
            prompt.append("\n\nCommunication style: ").append(tone);
        }

        if (!constraints.isEmpty()) {
            prompt.append("\n\nYou must adhere to the following constraints:");
            for (String constraint : constraints) {
                prompt.append("\n- ").append(constraint);
            }
        }

        if (!customAttributes.isEmpty()) {
            prompt.append("\n\nAdditional characteristics:");
            for (Map.Entry<String, String> entry : customAttributes.entrySet()) {
                prompt.append("\n- ").append(entry.getKey()).append(": ").append(entry.getValue());
            }
        }

        return prompt.toString();
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getExpertise() {
        return expertise;
    }

    public String getTone() {
        return tone;
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public Map<String, String> getCustomAttributes() {
        return customAttributes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String role;
        private String expertise;
        private String tone;
        private List<String> constraints = new ArrayList<>();
        private Map<String, String> customAttributes = new HashMap<>();

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder expertise(String expertise) {
            this.expertise = expertise;
            return this;
        }

        public Builder tone(String tone) {
            this.tone = tone;
            return this;
        }

        public Builder addConstraint(String constraint) {
            this.constraints.add(constraint);
            return this;
        }

        public Builder constraints(List<String> constraints) {
            this.constraints.addAll(constraints);
            return this;
        }

        public Builder addCustomAttribute(String key, String value) {
            this.customAttributes.put(key, value);
            return this;
        }

        public Builder customAttributes(Map<String, String> customAttributes) {
            this.customAttributes.putAll(customAttributes);
            return this;
        }

        public AgentPersona build() {
            return new AgentPersona(this);
        }
    }

    @Override
    public String toString() {
        return "AgentPersona{" +
                "name='" + name + '\'' +
                ", role='" + role + '\'' +
                ", tone='" + tone + '\'' +
                '}';
    }
}
