package com.lastulas.ica.config;

import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting simple en memoria para endpoints sensibles.
 *
 * Implementación básica de token bucket sin dependencias externas.
 * Login: 5 peticiones por minuto por IP.
 */
@Configuration
public class RateLimitConfig {

    public static final class TokenBucket {
        private final int capacity;
        private final long refillMillis;
        private int tokens;
        private Instant lastRefill;

        TokenBucket(int capacity, Duration refillDuration) {
            this.capacity = capacity;
            this.refillMillis = refillDuration.toMillis();
            this.tokens = capacity;
            this.lastRefill = Instant.now();
        }

public synchronized boolean tryConsume(int tokensToConsume) {
        refill();
        if (tokens >= tokensToConsume) {
            tokens -= tokensToConsume;
            return true;
        }
        return false;
    }

    public synchronized long getNanosToWaitForRefill() {
        refill();
        if (tokens > 0) return 0;
        long millisUntilRefill = refillMillis - (Instant.now().toEpochMilli() - lastRefill.toEpochMilli());
        return Math.max(0, millisUntilRefill) * 1_000_000;
    }

        private void refill() {
            Instant now = Instant.now();
            long elapsedMillis = now.toEpochMilli() - lastRefill.toEpochMilli();
            if (elapsedMillis >= refillMillis) {
                tokens = capacity;
                lastRefill = now;
            }
        }
    }

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Obtiene o crea un bucket para la clave dada (ej. IP).
     * capacity = max peticiones, refillDuration = ventana de tiempo.
     */
    public TokenBucket resolveBucket(String key, int capacity, Duration refillDuration) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillDuration));
    }

}