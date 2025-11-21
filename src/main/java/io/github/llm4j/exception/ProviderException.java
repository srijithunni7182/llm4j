package io.github.llm4j.exception;

/**
 * Exception thrown for provider-specific errors.
 */
public class ProviderException extends LLMException {
    
    private final String providerName;
    
    public ProviderException(String providerName, String message) {
        super(String.format("[%s] %s", providerName, message));
        this.providerName = providerName;
    }
    
    public ProviderException(String providerName, String message, Throwable cause) {
        super(String.format("[%s] %s", providerName, message), cause);
        this.providerName = providerName;
    }
    
    public ProviderException(String providerName, String message, Integer statusCode) {
        super(String.format("[%s] %s", providerName, message), statusCode);
        this.providerName = providerName;
    }
    
    public String getProviderName() {
        return providerName;
    }
}
