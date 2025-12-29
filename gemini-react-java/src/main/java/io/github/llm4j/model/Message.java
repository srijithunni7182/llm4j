package io.github.llm4j.model;

import java.util.Objects;

/**
 * Represents a message in a conversation with an LLM.
 * Messages can have different roles (system, user, assistant) and contain text content.
 */
public final class Message {
    
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");
        
        private final String value;
        
        Role(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static Role fromValue(String value) {
            for (Role role : values()) {
                if (role.value.equals(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }
    
    private final Role role;
    private final String content;
    private final String name;
    
    private Message(Builder builder) {
        this.role = Objects.requireNonNull(builder.role, "role cannot be null");
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        this.name = builder.name;
    }
    
    public Role getRole() {
        return role;
    }
    
    public String getContent() {
        return content;
    }
    
    public String getName() {
        return name;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static Message system(String content) {
        return builder().role(Role.SYSTEM).content(content).build();
    }
    
    public static Message user(String content) {
        return builder().role(Role.USER).content(content).build();
    }
    
    public static Message assistant(String content) {
        return builder().role(Role.ASSISTANT).content(content).build();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return role == message.role && 
               Objects.equals(content, message.content) && 
               Objects.equals(name, message.name);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(role, content, name);
    }
    
    @Override
    public String toString() {
        return "Message{" +
                "role=" + role +
                ", content='" + content + '\'' +
                (name != null ? ", name='" + name + '\'' : "") +
                '}';
    }
    
    public static final class Builder {
        private Role role;
        private String content;
        private String name;
        
        private Builder() {
        }
        
        public Builder role(Role role) {
            this.role = role;
            return this;
        }
        
        public Builder content(String content) {
            this.content = content;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Message build() {
            return new Message(this);
        }
    }
}
