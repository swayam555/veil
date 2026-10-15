package dev.veil.control.auth;

import dev.veil.control.config.AppProperties;
import dev.veil.control.config.Caller;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Console sign-in. Passwords are BCrypt hashes; five failures lock an email for 15 minutes. The JWT carries
 * user, organisation and role, and is verified by the gateway, which passes identity on as X-User-* headers.
 */
@RestController
public class AuthController {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final SecretKey key;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    public AuthController(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Login req) {
        String email = req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        Attempts a = failures.get(email);
        if (a != null && a.count() >= MAX_ATTEMPTS && a.until().isAfter(Instant.now())) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many failed attempts. Try again in 15 minutes."));
        }
        var user = jdbc.sql("""
                        SELECT u.id, u.org_id, u.name, u.password_hash, u.role, o.name AS org_name
                        FROM users u JOIN orgs o ON o.id = u.org_id WHERE u.email = ?""")
                .param(email).query(UserRow.class).optional();
        if (user.isEmpty() || req.password() == null || !bcrypt.matches(req.password(), user.get().passwordHash())) {
            failures.merge(email, new Attempts(1, Instant.now().plus(LOCKOUT)),
                    (old, x) -> new Attempts(old.count() + 1, Instant.now().plus(LOCKOUT)));
            return ResponseEntity.status(401).body(Map.of("error", "Wrong email or password"));
        }
        failures.remove(email);
        UserRow u = user.get();
        Instant now = Instant.now();
        String token = Jwts.builder().subject(u.id()).claim("org", u.orgId()).claim("name", u.name()).claim("role", u.role())
                .issuer("veil").issuedAt(Date.from(now)).expiration(Date.from(now.plus(Duration.ofHours(12)))).signWith(key).compact();
        return ResponseEntity.ok(Map.of("accessToken", token, "user", Map.of("id", u.id(), "name", u.name(), "email", email,
                "role", u.role(), "orgId", u.orgId(), "orgName", u.orgName())));
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Caller c = Caller.from(request);
        return jdbc.sql("""
                        SELECT u.id, u.name, u.email, u.role, u.org_id AS "orgId", o.name AS "orgName"
                        FROM users u JOIN orgs o ON o.id = u.org_id WHERE u.id = ?""")
                .param(c.userId()).query().singleRow();
    }

    public record Login(String email, String password) {
    }

    record UserRow(String id, String orgId, String name, String passwordHash, String role, String orgName) {
    }

    record Attempts(int count, Instant until) {
    }
}
