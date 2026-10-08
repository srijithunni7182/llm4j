package io.github.llm4j.getviral.app.security;

import io.github.llm4j.getviral.app.AppProperties;
import io.github.llm4j.getviral.app.account.CurrentUser;
import io.github.llm4j.getviral.app.account.OnboardingStep;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sign-in with Google (when GOOGLE_CLIENT_ID/SECRET are set) plus an opt-in dev login for local use.
 * Pages are public shells; everything personal lives behind {@code /api/**} and {@code /media/**}.
 * CSRF uses the SPA cookie pattern: the XSRF-TOKEN cookie is echoed back in the X-XSRF-TOKEN header.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AppProperties props, CurrentUser currentUser,
                                 SecurityContextRepository contextRepository) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // resolve the token eagerly so the cookie is always set
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**", "/auth/**", "/actuator/health/**").permitAll()
                .requestMatchers("/api/**", "/media/**", "/connect/**").authenticated()
                .anyRequest().permitAll())
            .securityContext(ctx -> ctx.securityContextRepository(contextRepository))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .logout(logout -> logout.logoutUrl("/auth/logout").logoutSuccessUrl("/"))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; img-src 'self' data: blob: https:; media-src 'self' blob:; "
                    + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                    + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

        if (props.auth().googleConfigured()) {
            http.oauth2Login(oauth -> oauth
                .loginPage("/")
                .successHandler((request, response, authentication) -> {
                    var user = currentUser.upsert(authentication);
                    response.sendRedirect(user.getOnboardingStep() == OnboardingStep.DONE ? "/studio" : "/welcome");
                }));
        }
        return http.build();
    }

    @Bean
    ClientRegistrationRepository clientRegistrations(AppProperties props) {
        if (!props.auth().googleConfigured()) {
            return registrationId -> null; // Google sign-in disabled — dev login only
        }
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(props.auth().googleClientId())
                .clientSecret(props.auth().googleClientSecret())
                .scope("openid", "email", "profile")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    /** Touches the deferred CSRF token so the XSRF-TOKEN cookie is written on every response. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }
}
