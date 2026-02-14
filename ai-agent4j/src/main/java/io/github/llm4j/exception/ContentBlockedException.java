package io.github.llm4j.exception;

public class ContentBlockedException extends ProviderException {
    public ContentBlockedException(String provider, String message) {
        super(provider, message);
    }
}
