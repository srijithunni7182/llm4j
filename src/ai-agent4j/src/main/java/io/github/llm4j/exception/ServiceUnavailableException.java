package io.github.llm4j.exception;

/**
 * The provider is temporarily unable to serve (HTTP 500, 502, 503, 504, or Anthropic's 529
 * "overloaded"), even after retries. Worth trying again later, or on another model.
 */
public class ServiceUnavailableException extends ProviderException {

    public ServiceUnavailableException(String providerName, String message, Integer statusCode, String responseBody) {
        super(providerName, message, statusCode);
        setResponseBody(responseBody);
    }

    public ServiceUnavailableException(String providerName, String message) {
        super(providerName, message);
    }
}
