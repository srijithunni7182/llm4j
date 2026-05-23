package io.github.llm4j.tantrik.console.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Catches all non-API, non-asset GET requests and forwards them to index.html
 * so that the React SPA can handle client-side routing.
 *
 * <p>Spring MVC resolves more specific mappings first:
 * <ul>
 *   <li>REST controllers under {@code /api/**} take priority.</li>
 *   <li>The default static resource handler serves {@code .js}, {@code .css},
 *       {@code .png}, etc. before this controller is reached.</li>
 * </ul>
 *
 * <p>This controller only fires for paths that match none of the above —
 * i.e. React router paths like {@code /}, {@code /dashboard}, etc.
 */
@Controller
public class SpaController {

    private static final Logger log = LoggerFactory.getLogger(SpaController.class);

    /**
     * Forward any unmatched GET to {@code /index.html}.
     *
     * <p>The regex {@code [^\\.]*} matches path segments with no dot, which
     * excludes static assets. The explicit {@code /api} prefix is handled by
     * REST controllers and never reaches here.
     */
    @GetMapping(value = {
        "/",
        "/{path:[^\\.]*}",
        "/{path:[^\\.]*}/**"
    })
    public String forward(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Safety guard: never forward /api/** — should never reach here but log if it does
        if (uri.startsWith("/api/")) {
            log.warn("SpaController unexpectedly received API request: {} — this is a routing bug", uri);
            // Return 404 rather than forwarding to index.html
            throw new java.util.NoSuchElementException("API route not found: " + uri);
        }
        log.debug("SpaController forwarding {} → /index.html", uri);
        return "forward:/index.html";
    }
}
