package io.github.llm4j.tantrik.console.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every incoming HTTP request with method, URI, query string, status,
 * and elapsed time. Helps diagnose routing and CORS issues.
 */
@Configuration
public class RequestLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger("HTTP");

    @Bean
    public OncePerRequestFilter requestLoggingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {

                long start = System.currentTimeMillis();
                String query = request.getQueryString();
                String uri = request.getRequestURI() + (query != null ? "?" + query : "");

                try {
                    chain.doFilter(request, response);
                } finally {
                    long elapsed = System.currentTimeMillis() - start;
                    int status = response.getStatus();
                    String level = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";

                    if ("ERROR".equals(level)) {
                        log.error("{} {} → {} ({}ms)", request.getMethod(), uri, status, elapsed);
                    } else if ("WARN".equals(level)) {
                        log.warn("{} {} → {} ({}ms)", request.getMethod(), uri, status, elapsed);
                    } else {
                        log.info("{} {} → {} ({}ms)", request.getMethod(), uri, status, elapsed);
                    }
                }
            }

            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                // Skip logging for Vite HMR websocket upgrade requests
                String upgrade = request.getHeader("Upgrade");
                return "websocket".equalsIgnoreCase(upgrade);
            }
        };
    }
}
