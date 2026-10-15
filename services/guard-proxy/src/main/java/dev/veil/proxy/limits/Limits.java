package dev.veil.proxy.limits;

import dev.veil.contracts.KeyInfo;
import dev.veil.contracts.OrgConfig;
import dev.veil.proxy.auth.ProxyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Per-key request rate (a token bucket) and daily spend budgets per key and per team, enforced atomically in
 * Redis so every proxy instance shares the same counters. If Redis is down, limits fail open: availability of
 * the AI tools matters more than a perfectly enforced rate for a few seconds, and data protection still applies.
 */
@Component
public class Limits {

    private static final Logger log = LoggerFactory.getLogger(Limits.class);

    /**
     * KEYS[1] bucket; ARGV: capacity, refill per ms, now ms. Returns {allowed (1/0), ms until a token is available}.
     * Tokens refill continuously, so a 60 rpm key gets one request per second sustained and bursts up to 60.
     */
    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1]) or capacity
            local ts = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + (now - ts) * rate)
            local allowed = 0
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait = math.ceil((1 - tokens) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
            return {allowed, wait}
            """, List.class);

    private final ReactiveStringRedisTemplate redis;

    public Limits(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Void> checkRate(KeyInfo key) {
        if (key.rpmLimit() <= 0) return Mono.empty();
        double ratePerMs = key.rpmLimit() / 60_000.0;
        return redis.execute(TOKEN_BUCKET, List.of("veil:rl:" + key.keyId()),
                        List.of(String.valueOf(key.rpmLimit()), String.valueOf(ratePerMs), String.valueOf(System.currentTimeMillis())))
                .next()
                .flatMap(result -> {
                    long allowed = ((Number) result.get(0)).longValue();
                    long waitMs = ((Number) result.get(1)).longValue();
                    if (allowed == 1) return Mono.<Void>empty();
                    return Mono.<Void>error(new ProxyException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "rate_limit_exceeded",
                            "Rate limit of " + key.rpmLimit() + " requests per minute reached for this key", Map.of(),
                            Math.max(1, (waitMs + 999) / 1000)));
                })
                .onErrorResume(e -> !(e instanceof ProxyException), e -> {
                    log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
                    return Mono.empty();
                });
    }

    /** Fails if today's spend for the key or its team is already at the budget. */
    public Mono<Void> checkBudget(KeyInfo key, OrgConfig config) {
        long teamBudget = config.teamDailyBudgets().getOrDefault(key.teamId(), 0L);
        if (key.dailyBudgetMicros() <= 0 && teamBudget <= 0) return Mono.empty();
        String day = today();
        return redis.opsForValue().multiGet(List.of(keySpend(day, key.keyId()), teamSpend(day, key.orgId(), key.teamId())))
                .flatMap(values -> {
                    long keySpent = parse(values.get(0));
                    long teamSpent = parse(values.get(1));
                    if (key.dailyBudgetMicros() > 0 && keySpent >= key.dailyBudgetMicros()) {
                        return Mono.<Void>error(budget("This key's daily budget of " + usd(key.dailyBudgetMicros()) + " is used up"));
                    }
                    if (teamBudget > 0 && teamSpent >= teamBudget) {
                        return Mono.<Void>error(budget("Team " + key.teamName() + "'s daily AI budget of " + usd(teamBudget) + " is used up"));
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(e -> !(e instanceof ProxyException), e -> {
                    log.warn("Budget check unavailable, allowing request: {}", e.toString());
                    return Mono.empty();
                });
    }

    public Mono<Void> recordSpend(KeyInfo key, long micros) {
        if (micros <= 0) return Mono.empty();
        String day = today();
        String k = keySpend(day, key.keyId());
        String t = teamSpend(day, key.orgId(), key.teamId());
        return redis.opsForValue().increment(k, micros).then(redis.expire(k, java.time.Duration.ofHours(48)))
                .then(redis.opsForValue().increment(t, micros)).then(redis.expire(t, java.time.Duration.ofHours(48)))
                .then().onErrorResume(e -> Mono.empty());
    }

    public Mono<Map<String, Long>> spendToday(KeyInfo key) {
        String day = today();
        return redis.opsForValue().multiGet(List.of(keySpend(day, key.keyId()), teamSpend(day, key.orgId(), key.teamId())))
                .map(v -> Map.of("key", parse(v.get(0)), "team", parse(v.get(1))))
                .onErrorReturn(Map.of("key", 0L, "team", 0L));
    }

    private static ProxyException budget(String msg) {
        return new ProxyException(HttpStatus.TOO_MANY_REQUESTS, "insufficient_quota", "budget_exceeded", msg, Map.of(), 3600);
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String keySpend(String day, String keyId) {
        return "veil:spend:" + day + ":key:" + keyId;
    }

    private static String teamSpend(String day, String orgId, String teamId) {
        return "veil:spend:" + day + ":team:" + orgId + ":" + teamId;
    }

    private static long parse(String v) {
        return v == null ? 0 : Long.parseLong(v);
    }

    private static String usd(long micros) {
        return String.format("$%.2f", micros / 1_000_000.0);
    }
}
