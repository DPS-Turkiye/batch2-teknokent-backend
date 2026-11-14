package com.dps.teknokent.config;

import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import lombok.extern.slf4j.Slf4j;

/**
 * Fixed-window limits: a key may spend {@code capacity} tokens within {@code windowMs} of its window starting, then
 * waits for the window to end. With Redis the count is shared by every instance and decided in one atomic script;
 * without Redis, or while it fails, each instance counts in its own bounded memory.
 */
@Slf4j
@Configuration
public class RateLimitingConfig {

	static final String KEY_PREFIX = "rate_window:";

	/**
	 * Checks and spends in one step, so parallel requests cannot all read the same count and all pass. Answers the
	 * tokens left (zero or more) when the spend was allowed, or minus the milliseconds until the window ends (-1 or
	 * less) when it was refused. A refused spend changes nothing.
	 */
	static final RedisScript<Long> WINDOW_SCRIPT = RedisScript.of("""
			local used = tonumber(redis.call('GET', KEYS[1]) or '0')
			local capacity = tonumber(ARGV[1])
			local allowed = used + tonumber(ARGV[2]) <= capacity
			if allowed then
			  used = redis.call('INCRBY', KEYS[1], ARGV[2])
			end
			local ttl = redis.call('PTTL', KEYS[1])
			if ttl < 0 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[3])
			  ttl = tonumber(ARGV[3])
			end
			if allowed then
			  return capacity - used
			end
			return -math.max(ttl, 1)
			""", Long.class);

	/** Far more callers than one instance sees in a window; a flood of new keys must not grow the heap without end. */
	static final int MAX_MEMORY_KEYS = 100_000;
	private static final long LOG_INTERVAL_MS = 60_000;

	private final @Nullable StringRedisTemplate redisTemplate;
	private final MemoryBuckets memoryBuckets;
	private final Clock clock;
	private final AtomicLong nextRedisWarningAt = new AtomicLong();

	/** Without Redis: every bucket lives in this instance's memory. */
	public RateLimitingConfig() {
		this(null, Clock.systemUTC());
	}

	/** Redis is optional: when the application has no template, the in-memory buckets are used alone. */
	@Autowired
	public RateLimitingConfig(ObjectProvider<StringRedisTemplate> redisTemplate) {
		this(redisTemplate.getIfAvailable(), Clock.systemUTC());
	}

	RateLimitingConfig(@Nullable StringRedisTemplate redisTemplate, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.clock = clock;
		this.memoryBuckets = new MemoryBuckets(clock, MAX_MEMORY_KEYS);
	}

	public TokenBucket resolveBucket(String key) {
		return resolveBucket(key, 100, 60_000);
	}

	public TokenBucket resolveBucket(String key, long capacity, long windowMs) {
		StringRedisTemplate redis = redisTemplate;
		if (redis == null) {
			return memoryBuckets.resolve(key, capacity, windowMs);
		}
		return new RedisBucket(redis, key, capacity, windowMs);
	}

	/** How many keys this instance counts in memory; for tests. */
	int keysInMemory() {
		return memoryBuckets.size();
	}

	/** Once per interval, not per request: while Redis is down every limited request would log otherwise. */
	private void warnRedisUnavailable(String reason) {
		long now = clock.millis();
		long due = nextRedisWarningAt.get();
		if (now >= due && nextRedisWarningAt.compareAndSet(due, now + LOG_INTERVAL_MS)) {
			log.warn("Rate limiting cannot reach Redis ({}); limits are counted per instance until it is back.",
					reason);
		}
	}

	/** A limit some code spends from; a bucket is good for one request, so resolve a new one per request. */
	public interface TokenBucket {

		/** Spends the tokens if that many are left; a refused spend takes nothing. */
		boolean tryConsume(long tokens);

		long getRemainingTokens();

		/** Whole seconds until a refused caller may try again: 0 while tokens are left, otherwise at least 1. */
		long getSecondsToWait();
	}

	/** One request's view of a window kept in Redis; falls back to memory for this key when Redis fails. */
	private final class RedisBucket implements TokenBucket {
		private final StringRedisTemplate redis;
		private final String key;
		private final long capacity;
		private final long windowMs;
		private long remaining;
		private long secondsToWait;
		private @Nullable TokenBucket fallback;

		private RedisBucket(StringRedisTemplate redis, String key, long capacity, long windowMs) {
			this.redis = redis;
			this.key = key;
			this.capacity = capacity;
			this.windowMs = windowMs;
			this.remaining = capacity;
		}

		@Override
		public boolean tryConsume(long tokens) {
			if (fallback != null) {
				return fallback.tryConsume(tokens);
			}
			Long answer = null;
			String failure = "no answer";
			try {
				answer = redis.execute(WINDOW_SCRIPT, List.of(KEY_PREFIX + key),
						String.valueOf(capacity), String.valueOf(tokens), String.valueOf(windowMs));
			} catch (RuntimeException error) {
				failure = error.toString();
			}
			if (answer == null) {
				warnRedisUnavailable(failure);
				fallback = memoryBuckets.resolve(key, capacity, windowMs);
				return fallback.tryConsume(tokens);
			}
			if (answer >= 0) {
				remaining = answer;
				secondsToWait = 0;
				return true;
			}
			remaining = 0;
			secondsToWait = Math.max(1, (-answer + 999) / 1000);
			return false;
		}

		@Override
		public long getRemainingTokens() {
			return fallback != null ? fallback.getRemainingTokens() : remaining;
		}

		@Override
		public long getSecondsToWait() {
			return fallback != null ? fallback.getSecondsToWait() : secondsToWait;
		}
	}

	/**
	 * Buckets in this instance's memory, at most {@code maxKeys} of them. Once a minute, or whenever the map is
	 * full, buckets whose window has ended are dropped (they would be full again, so nothing is lost); if it is
	 * still full, arbitrary buckets go until a tenth is free, which forgets those keys' spending.
	 */
	public static final class MemoryBuckets {
		private static final long SWEEP_INTERVAL_MS = 60_000;

		private final Map<String, MemoryBucket> buckets = new ConcurrentHashMap<>();
		private final Clock clock;
		private final int maxKeys;
		private final AtomicLong nextSweepAt = new AtomicLong();
		private final AtomicLong nextOverflowWarningAt = new AtomicLong();

		public MemoryBuckets(Clock clock, int maxKeys) {
			this.clock = clock;
			this.maxKeys = maxKeys;
		}

		public TokenBucket resolve(String key, long capacity, long windowMs) {
			sweepIfDue();
			return buckets.computeIfAbsent(key, ignored -> new MemoryBucket(capacity, windowMs, clock));
		}

		public int size() {
			return buckets.size();
		}

		private void sweepIfDue() {
			long now = clock.millis();
			long due = nextSweepAt.get();
			boolean full = buckets.size() >= maxKeys;
			if ((now < due && !full) || !nextSweepAt.compareAndSet(due, now + SWEEP_INTERVAL_MS)) {
				return;
			}
			buckets.values().removeIf(bucket -> bucket.windowEnded(now));
			if (buckets.size() < maxKeys) {
				return;
			}
			int target = maxKeys - Math.max(1, maxKeys / 10);
			Iterator<MemoryBucket> iterator = buckets.values().iterator();
			while (buckets.size() > target && iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			}
			long warningDue = nextOverflowWarningAt.get();
			if (now >= warningDue && nextOverflowWarningAt.compareAndSet(warningDue, now + LOG_INTERVAL_MS)) {
				log.warn("In-memory rate limits reached {} keys within one window; some limits were reset.", maxKeys);
			}
		}
	}

	/** Starts full; once its window has passed since it was last filled, it is full again. */
	static final class MemoryBucket implements TokenBucket {
		private final long capacity;
		private final long windowMs;
		private final Clock clock;
		private long tokens;
		private long windowStart;

		MemoryBucket(long capacity, long windowMs, Clock clock) {
			this.capacity = capacity;
			this.windowMs = windowMs;
			this.clock = clock;
			this.tokens = capacity;
			this.windowStart = clock.millis();
		}

		@Override
		public synchronized boolean tryConsume(long tokens) {
			refill();
			if (this.tokens >= tokens) {
				this.tokens -= tokens;
				return true;
			}
			return false;
		}

		@Override
		public synchronized long getRemainingTokens() {
			refill();
			return tokens;
		}

		@Override
		public synchronized long getSecondsToWait() {
			refill();
			if (tokens >= 1) {
				return 0;
			}
			long timeToWait = windowMs - (clock.millis() - windowStart);
			return Math.max(1, (timeToWait + 999) / 1000);
		}

		synchronized boolean windowEnded(long now) {
			return now - windowStart >= windowMs;
		}

		private void refill() {
			long now = clock.millis();
			if (now - windowStart >= windowMs) {
				tokens = capacity;
				windowStart = now;
			}
		}
	}
}
