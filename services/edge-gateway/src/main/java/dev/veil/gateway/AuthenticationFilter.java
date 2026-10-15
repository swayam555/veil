package dev.veil.gateway;

import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Identity at the edge. Console APIs need a valid JWT; changing configuration needs the ADMIN role. Client-sent
 * X-User-* headers are always removed, so nobody can claim to be someone else or to belong to another organisation.
 * The proxy's own {@code /v1} API is public here because it authenticates with Veil virtual keys itself.
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ATTRIBUTE = "veil.user";
    private static final List<String> IDENTITY_HEADERS = List.of("X-User-Id", "X-User-Org", "X-User-Role", "X-User-Name");
    /** Viewers (auditors) may read everything and use these POST endpoints, which change nothing. */
    private static final List<String> VIEWER_POSTS = List.of("/api/playground/", "/api/policy/copilot", "/api/insight/incidents/");

    private final Jwt jwt;

    public AuthenticationFilter(Jwt jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        ServerHttpRequest stripped = request.mutate().headers(h -> IDENTITY_HEADERS.forEach(h::remove)).build();
        if (!path.startsWith("/api/") || path.startsWith("/api/auth/")) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }
        Optional<Claims> claims = token(exchange).flatMap(jwt::verify);
        if (claims.isEmpty()) return reject(exchange, HttpStatus.UNAUTHORIZED, "Sign in again: your session is missing or expired");
        Claims c = claims.get();
        String role = String.valueOf(c.get("role"));
        if (!"ADMIN".equals(role) && writes(request.getMethod(), path)) {
            return reject(exchange, HttpStatus.FORBIDDEN, "Your role can view but not change settings");
        }
        ServerHttpRequest withIdentity = stripped.mutate()
                .header("X-User-Id", c.getSubject())
                .header("X-User-Org", String.valueOf(c.get("org")))
                .header("X-User-Role", role)
                .header("X-User-Name", String.valueOf(c.get("name")))
                .build();
        exchange.getAttributes().put(USER_ATTRIBUTE, c.getSubject());
        return chain.filter(exchange.mutate().request(withIdentity).build());
    }

    static boolean writes(HttpMethod method, String path) {
        if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) return false;
        if (method == HttpMethod.POST && VIEWER_POSTS.stream().anyMatch(path::startsWith)) {
            // Explaining an incident is allowed for viewers; changing its status (PATCH) is not.
            return false;
        }
        return true;
    }

    private static Optional<String> token(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) return Optional.of(header.substring(7));
        // EventSource (the live feed) cannot send headers.
        return Optional.ofNullable(exchange.getRequest().getQueryParams().getFirst("access_token"));
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
