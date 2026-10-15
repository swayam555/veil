package dev.veil.proxy.console;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;

/** Console user identity, set by the gateway after it verifies the JWT. */
public record ConsoleCaller(String userId, String orgId, String role, String name) {

    public static ConsoleCaller from(ServerHttpRequest req) {
        String user = req.getHeaders().getFirst("X-User-Id");
        String org = req.getHeaders().getFirst("X-User-Org");
        if (user == null || org == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        return new ConsoleCaller(user, org, req.getHeaders().getFirst("X-User-Role"), req.getHeaders().getFirst("X-User-Name"));
    }

    public ConsoleCaller requireAdmin() {
        if (!"ADMIN".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        return this;
    }
}
