package io.github.llm4j.multiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WebSocketConfigurationTest {

    @Test
    void testConfigureMessageBroker() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void testRegisterStompEndpoints() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint(anyString())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(anyString())).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
        verify(registration).setAllowedOriginPatterns("*");
        verify(registration).withSockJS();
    }

    @Test
    void testWebSocketEndpointPath() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint("/ws")).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(anyString())).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
    }

    @Test
    void testCorsConfiguration() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint(anyString())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns("*")).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registration).setAllowedOriginPatterns("*");
    }

    @Test
    void testSockJSEnabled() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint(anyString())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(anyString())).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registration).withSockJS();
    }

    @Test
    void testMessageBrokerPrefix() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
    }

    @Test
    void testApplicationDestinationPrefix() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).setApplicationDestinationPrefixes("/app");
    }
}
