package io.github.llm4j.getviral.app.security;

import io.github.llm4j.getviral.app.AppProperties;
import io.github.llm4j.getviral.app.account.CurrentUser;
import io.github.llm4j.getviral.app.account.OnboardingStep;
import io.github.llm4j.getviral.config.GetViralConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** What the landing page needs to know, and the opt-in dev login used for local runs and tests. */
@RestController
public class AuthController {

    private final AppProperties props;
    private final GetViralConfig engineConfig;
    private final CurrentUser currentUser;
    private final SecurityContextRepository contextRepository;

    public AuthController(AppProperties props, GetViralConfig engineConfig, CurrentUser currentUser,
                          SecurityContextRepository contextRepository) {
        this.props = props;
        this.engineConfig = engineConfig;
        this.currentUser = currentUser;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/api/public/info")
    Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", engineConfig.mode().name());
        info.put("model", engineConfig.model());
        info.put("googleSignIn", props.auth().googleConfigured());
        info.put("devLogin", props.auth().devLogin());
        info.put("packsPerMonth", props.quota().packsPerMonth());
        info.put("publicApis", engineConfig.offlineApis() ? "offline samples" : "live (sample fallback)");
        return info;
    }

    public record DevLogin(@NotBlank @Email String email, String name) { }

    @PostMapping("/auth/dev-login")
    Map<String, Object> devLogin(@Valid @RequestBody DevLogin body, HttpServletRequest request, HttpServletResponse response) {
        if (!props.auth().devLogin()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dev login is disabled");
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(body.email().toLowerCase(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.getSession(true); // new session id after login is handled by the container/Spring Session
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
        var user = currentUser.upsert(auth);
        return Map.of("redirect", user.getOnboardingStep() == OnboardingStep.DONE ? "/studio" : "/welcome");
    }
}
