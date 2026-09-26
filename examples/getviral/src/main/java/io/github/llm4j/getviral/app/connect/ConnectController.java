package io.github.llm4j.getviral.app.connect;

import io.github.llm4j.getviral.app.ApiErrors;
import io.github.llm4j.getviral.app.account.CurrentUser;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Browser-facing connect/callback redirects and the disconnect API. */
@RestController
public class ConnectController {

    private static final Logger log = LoggerFactory.getLogger(ConnectController.class);
    private static final String SESSION_KEY = "getviral.connect.";

    private final ConnectService service;
    private final CurrentUser currentUser;

    public ConnectController(ConnectService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/connect/{platform}")
    ResponseEntity<Void> start(@PathVariable String platform, @RequestParam(defaultValue = "/welcome") String returnTo,
                               HttpSession session) {
        currentUser.require();
        Platform p = Platform.of(platform).orElseThrow(ApiErrors::notFound);
        String back = safeReturn(returnTo);
        if (!service.configured(p)) {
            return redirect(back + "?connect=" + p.key() + "&error=" + enc(p.label + " connections aren't set up on this server yet."));
        }
        ConnectService.Pending pending = service.newPending(back);
        session.setAttribute(SESSION_KEY + p.key(), pending);
        return redirect(service.authorizeUrl(p, pending));
    }

    @GetMapping("/connect/{platform}/callback")
    ResponseEntity<Void> callback(@PathVariable String platform, @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error, HttpSession session) {
        var user = currentUser.require();
        Platform p = Platform.of(platform).orElseThrow(ApiErrors::notFound);
        ConnectService.Pending pending = (ConnectService.Pending) session.getAttribute(SESSION_KEY + p.key());
        session.removeAttribute(SESSION_KEY + p.key());
        String back = pending == null ? "/welcome" : pending.returnTo();
        if (error != null || code == null) {
            return redirect(back + "?connect=" + p.key() + "&error=" + enc("You cancelled or " + p.label + " declined the connection."));
        }
        if (pending == null || state == null || !state.equals(pending.state())) {
            return redirect(back + "?connect=" + p.key() + "&error=" + enc("That connection link expired — please try again."));
        }
        try {
            service.complete(user.getId(), p, code, pending);
            return redirect(back + "?connect=" + p.key() + "&ok=1");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Connecting {} failed for {}: {}", p.label, user.getId(), e.getMessage());
            return redirect(back + "?connect=" + p.key() + "&error=" + enc(p.label + " said: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/connections/{platform}")
    Map<String, Object> disconnect(@PathVariable String platform) {
        var user = currentUser.require();
        service.disconnect(user.getId(), Platform.of(platform).orElseThrow(ApiErrors::notFound));
        return Map.of("ok", true);
    }

    private static String safeReturn(String returnTo) {
        // Only same-site paths: never redirect to another origin.
        return returnTo != null && returnTo.matches("/[A-Za-z0-9/_-]*") ? returnTo : "/welcome";
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
