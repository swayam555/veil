package dev.veil.control.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in console user, from headers the gateway sets after verifying the JWT (it strips any
 * client-sent copies). Services never trust identity from anywhere else.
 */
public record Caller(String userId, String orgId, String role, String name) {

    public static Caller from(HttpServletRequest req) {
        String user = req.getHeader("X-User-Id");
        String org = req.getHeader("X-User-Org");
        if (user == null || org == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        return new Caller(user, org, req.getHeader("X-User-Role"), req.getHeader("X-User-Name"));
    }

    public Caller requireAdmin() {
        if (!"ADMIN".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        return this;
    }
}
