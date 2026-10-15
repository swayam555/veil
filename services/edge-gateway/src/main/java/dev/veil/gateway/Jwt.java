package dev.veil.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Verifies HS256 tokens issued by the control plane. */
@Component
public class Jwt {

    private static final String ISSUER = "veil";
    private final SecretKey key;

    public Jwt(@Value("${veil.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Claims> verify(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
