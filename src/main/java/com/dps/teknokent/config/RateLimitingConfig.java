package com.dps.teknokent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class RateLimitingConfig {

	// Fallback in-memory cache if Redis is unavailable
	private final Map<String, TokenBucket> memoryCache = new ConcurrentHashMap<>();
	private RedisRateLimitingStore redisStore;

	@Autowired(required = false)
	private StringRedisTemplate redisTemplate;

	@Bean
	public Map<String, TokenBucket> rateLimitBuckets() {
		return memoryCache;
	}

	@Bean
	public RedisRateLimitingStore redisRateLimitingStore() {
		if (redisTemplate != null) {
			this.redisStore = new RedisRateLimitingStore(redisTemplate, memoryCache);
		}
		return redisStore;
	}

	public TokenBucket resolveBucket(String key) {
		return resolveBucket(key, 100, 60_000);
	}

	public TokenBucket resolveBucket(String key, long capacity, long refillIntervalMs) {
		if (redisStore != null) {
			return redisStore.resolveBucket(key, capacity, refillIntervalMs);
		}
		// Fallback to memory cache
		return memoryCache.computeIfAbsent(key, k -> new TokenBucket(capacity, refillIntervalMs));
	}

	/**
	 * Redis-backed rate limiting store
	 * Stores tokens and last refill time in Redis with automatic expiration
	 */
	@RequiredArgsConstructor
	public static class RedisRateLimitingStore {
		private final StringRedisTemplate redisTemplate;
		private final Map<String, TokenBucket> fallbackCache;
		private static final String KEY_PREFIX = "rate_limit:";
		private static final String TOKENS_SUFFIX = ":tokens";
		private static final String LAST_REFILL_SUFFIX = ":last_refill";
		public TokenBucket resolveBucket(String ipAddress, long capacity, long refillIntervalMs) {
			// TTL longer than the refill interval, so an idle bucket expires only once it would be full again
			Duration ttl = Duration.ofMillis(refillIntervalMs * 2);
			String tokensKey = KEY_PREFIX + ipAddress + TOKENS_SUFFIX;
			String lastRefillKey = KEY_PREFIX + ipAddress + LAST_REFILL_SUFFIX;

			try {
				// Try to get from Redis
				String tokensStr = redisTemplate.opsForValue().get(tokensKey);
				String lastRefillStr = redisTemplate.opsForValue().get(lastRefillKey);

				long tokens = tokensStr != null ? Long.parseLong(tokensStr) : capacity;
				long lastRefill = lastRefillStr != null ? Long.parseLong(lastRefillStr) : System.currentTimeMillis();

				return new TokenBucket(capacity, refillIntervalMs, tokens, lastRefill,
						(bucket) -> saveToRedis(tokensKey, lastRefillKey, bucket, ttl));
			} catch (Exception e) {
				// Fallback to memory cache if Redis fails
				return fallbackCache.computeIfAbsent(ipAddress,
						k -> new TokenBucket(capacity, refillIntervalMs));
			}
		}

		private void saveToRedis(String tokensKey, String lastRefillKey, TokenBucket bucket, Duration ttl) {
			try {
				redisTemplate.opsForValue().set(tokensKey, String.valueOf(bucket.getTokens()), ttl);
				redisTemplate.opsForValue().set(lastRefillKey, String.valueOf(bucket.getLastRefillTime()), ttl);
			} catch (Exception e) {
				// Log error but don't fail - in-memory fallback will handle it
			}
		}
	}

	public static class TokenBucket {
		private final long capacity;
		private final long refillIntervalMs;
		private long tokens;
		private long lastRefillTime;
		private final PersistenceCallback persistenceCallback;

		public TokenBucket(long capacity, long refillIntervalMs) {
			this(capacity, refillIntervalMs, capacity, System.currentTimeMillis(), null);
		}

		public TokenBucket(long capacity, long refillIntervalMs, long tokens, long lastRefillTime,
				PersistenceCallback persistenceCallback) {
			this.capacity = capacity;
			this.refillIntervalMs = refillIntervalMs;
			this.tokens = tokens;
			this.lastRefillTime = lastRefillTime;
			this.persistenceCallback = persistenceCallback;
		}

		public synchronized boolean tryConsume(long tokens) {
			refill();
			if (this.tokens >= tokens) {
				this.tokens -= tokens;
				persist();
				return true;
			}
			return false;
		}

		public synchronized long getRemainingTokens() {
			refill();
			return tokens;
		}

		public synchronized long getSecondsToWait() {
			if (tokens >= 1) {
				return 0;
			}
			long timeSinceLastRefill = System.currentTimeMillis() - lastRefillTime;
			long timeToWait = refillIntervalMs - timeSinceLastRefill;
			return Math.max(1, (timeToWait + 999) / 1000); // Round up to nearest second
		}

		public long getTokens() {
			return tokens;
		}

		public long getLastRefillTime() {
			return lastRefillTime;
		}

		private void refill() {
			long now = System.currentTimeMillis();
			long timeSinceLastRefill = now - lastRefillTime;

			if (timeSinceLastRefill >= refillIntervalMs) {
				tokens = capacity;
				lastRefillTime = now;
				persist();
			}
		}

		private void persist() {
			if (persistenceCallback != null) {
				persistenceCallback.onStateChange(this);
			}
		}
	}

	@FunctionalInterface
	public interface PersistenceCallback {
		void onStateChange(TokenBucket bucket);
	}
}
