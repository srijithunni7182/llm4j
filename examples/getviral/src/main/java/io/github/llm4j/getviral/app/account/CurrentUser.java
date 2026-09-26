package io.github.llm4j.getviral.app.account;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolves the signed-in creator from the security context, creating the account on first sign-in. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public UserAccount require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        }
        return upsert(auth);
    }

    @Transactional
    public UserAccount upsert(Authentication auth) {
        String email;
        String name = null;
        String avatar = null;
        String provider;
        if (auth instanceof OAuth2AuthenticationToken oauth) {
            Map<String, Object> attrs = oauth.getPrincipal().getAttributes();
            email = String.valueOf(attrs.get("email"));
            name = (String) attrs.get("name");
            avatar = (String) attrs.get("picture");
            provider = oauth.getAuthorizedClientRegistrationId();
        } else {
            email = auth.getName();
            provider = "dev";
        }
        if (email == null || email.isBlank() || "null".equals(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Your account has no email address");
        }
        String finalName = name;
        String finalAvatar = avatar;
        UserAccount user = users.findByEmail(email.toLowerCase())
                .orElseGet(() -> UserAccount.create(email, finalName != null ? finalName : email.split("@")[0], finalAvatar, provider));
        user.seen(finalName, finalAvatar);
        return users.save(user);
    }
}
