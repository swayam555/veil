package dev.veil.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationFilterTest {

    static final String SECRET = "change-me-in-production-this-is-a-32+-byte-dev-secret";

    @Test
    void viewersMayReadAndUseSideEffectFreePostsOnly() {
        assertThat(AuthenticationFilter.writes(HttpMethod.GET, "/api/keys")).isFalse();
        assertThat(AuthenticationFilter.writes(HttpMethod.POST, "/api/playground/chat")).isFalse();
        assertThat(AuthenticationFilter.writes(HttpMethod.POST, "/api/policy/copilot")).isFalse();
        assertThat(AuthenticationFilter.writes(HttpMethod.POST, "/api/insight/incidents/4/explain")).isFalse();
        assertThat(AuthenticationFilter.writes(HttpMethod.PATCH, "/api/insight/incidents/4")).isTrue();
        assertThat(AuthenticationFilter.writes(HttpMethod.POST, "/api/keys")).isTrue();
        assertThat(AuthenticationFilter.writes(HttpMethod.PUT, "/api/policy")).isTrue();
        assertThat(AuthenticationFilter.writes(HttpMethod.POST, "/api/simulator/start")).isTrue();
    }

    @Test
    void tokensMustBeSignedByVeilAndUnexpired() {
        Jwt jwt = new Jwt(SECRET);
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String good = Jwts.builder().subject("u").issuer("veil").expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        String wrongIssuer = Jwts.builder().subject("u").issuer("other").signWith(key).compact();
        String expired = Jwts.builder().subject("u").issuer("veil").expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key).compact();
        var otherKey = Keys.hmacShaKeyFor("another-secret-that-is-at-least-32-bytes-long!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().subject("u").issuer("veil").signWith(otherKey).compact();
        assertThat(jwt.verify(good)).isPresent();
        assertThat(jwt.verify(wrongIssuer)).isEmpty();
        assertThat(jwt.verify(expired)).isEmpty();
        assertThat(jwt.verify(forged)).isEmpty();
        assertThat(jwt.verify("garbage")).isEmpty();
    }
}
