package com.dps.teknokent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dps.teknokent.filter.RateLimitingFilter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class RateLimitingConfig {

	private final Map<String, TokenBucket> cache = new ConcurrentHashMap<>();

	@Bean
	public Map<String, TokenBucket> rateLimitBuckets() {
		return cache;
	}

	@Bean
	public RateLimitingFilter rateLimitingFilter() {
		return new RateLimitingFilter(this);
	}

	public TokenBucket resolveBucket(String key) {
		return cache.computeIfAbsent(key, k -> new TokenBucket(100, 60_000)); // 100 requests per minute
	}

	public static class TokenBucket {
		private final long capacity;
		private final long refillIntervalMs;
		private long tokens;
		private long lastRefillTime;

		public TokenBucket(long capacity, long refillIntervalMs) {
			this.capacity = capacity;
			this.refillIntervalMs = refillIntervalMs;
			this.tokens = capacity;
			this.lastRefillTime = System.currentTimeMillis();
		}

		public synchronized boolean tryConsume(long tokens) {
			refill();
			if (this.tokens >= tokens) {
				this.tokens -= tokens;
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

		private void refill() {
			long now = System.currentTimeMillis();
			long timeSinceLastRefill = now - lastRefillTime;

			if (timeSinceLastRefill >= refillIntervalMs) {
				tokens = capacity;
				lastRefillTime = now;
			}
		}
	}
}

