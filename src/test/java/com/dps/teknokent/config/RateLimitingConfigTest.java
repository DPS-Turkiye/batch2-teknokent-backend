package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.dps.teknokent.config.RateLimitingConfig.MemoryBuckets;
import com.dps.teknokent.config.RateLimitingConfig.TokenBucket;

@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class RateLimitingConfigTest {

	private static final long TEN_MINUTES_MS = 600_000;
	private static final String KEY = "203.0.113.7";
	private static final List<String> REDIS_KEY = List.of("rate_window:203.0.113.7");

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T09:00:00Z"));

	@Mock
	private ObjectProvider<StringRedisTemplate> templates;
	@Mock
	private StringRedisTemplate redis;

	@Test
	void aBucketLetsItsCapacityThroughThenRefusesUntilTheWindowEnds() {
		TokenBucket bucket = inMemory().resolveBucket(KEY, 2, TEN_MINUTES_MS);

		assertThat(bucket.getSecondsToWait()).isZero();
		assertThat(bucket.tryConsume(1)).isTrue();
		assertThat(bucket.tryConsume(1)).isTrue();
		assertThat(bucket.tryConsume(1)).isFalse();
		assertThat(bucket.getRemainingTokens()).isZero();
		assertThat(bucket.getSecondsToWait()).isEqualTo(600);

		clock.advance(Duration.ofMinutes(4));
		assertThat(bucket.getSecondsToWait()).isEqualTo(360);
	}

	@Test
	void aRequestForMoreThanIsLeftTakesNothing() {
		TokenBucket bucket = inMemory().resolveBucket(KEY, 3, TEN_MINUTES_MS);

		assertThat(bucket.tryConsume(4)).isFalse();
		assertThat(bucket.getRemainingTokens()).isEqualTo(3);
	}

	@Test
	void aSpentBucketIsFullAgainOnceItsWindowHasPassed() {
		TokenBucket bucket = inMemory().resolveBucket(KEY, 3, TEN_MINUTES_MS);
		bucket.tryConsume(3);

		clock.advance(Duration.ofMillis(TEN_MINUTES_MS));

		assertThat(bucket.tryConsume(1)).isTrue();
		assertThat(bucket.getRemainingTokens()).isEqualTo(2);
	}

	// The header is whole seconds: a caller told "0" would retry at once and be refused again.
	@Test
	void anEmptyBucketNeverAsksForLessThanASecond() {
		TokenBucket bucket = inMemory().resolveBucket(KEY, 1, TEN_MINUTES_MS);
		bucket.tryConsume(1);

		clock.advance(Duration.ofMillis(TEN_MINUTES_MS - 5));

		assertThat(bucket.getSecondsToWait()).isEqualTo(1);
	}

	@Test
	void withoutRedisEachKeyKeepsItsOwnBucketOfAHundredInMemory() {
		RateLimitingConfig config = new RateLimitingConfig();

		TokenBucket first = config.resolveBucket(KEY);

		assertThat(first.getRemainingTokens()).isEqualTo(100);
		assertThat(config.resolveBucket(KEY)).isSameAs(first);
		assertThat(config.resolveBucket("203.0.113.8")).isNotSameAs(first);
		assertThat(config.keysInMemory()).isEqualTo(2);
	}

	@Test
	void anApplicationWithoutARedisTemplateUsesMemoryAlone() {
		when(templates.getIfAvailable()).thenReturn(null);
		RateLimitingConfig config = new RateLimitingConfig(templates);

		assertThat(config.resolveBucket(KEY, 1, TEN_MINUTES_MS).tryConsume(1)).isTrue();
		assertThat(config.resolveBucket(KEY, 1, TEN_MINUTES_MS).tryConsume(1)).isFalse();
	}

	@Test
	void aBucketWhoseWindowHasEndedIsDroppedFromMemory() {
		RateLimitingConfig config = inMemory();
		config.resolveBucket(KEY, 1, TEN_MINUTES_MS).tryConsume(1);

		clock.advance(Duration.ofMillis(TEN_MINUTES_MS));
		config.resolveBucket("203.0.113.8", 1, TEN_MINUTES_MS);

		assertThat(config.keysInMemory()).isEqualTo(1);
	}

	// Without a bound, anyone able to invent keys (addresses, e-mail addresses) could fill the heap.
	@Test
	void memoryNeverHoldsMoreThanItsBoundEvenWhenEveryWindowIsStillRunning(CapturedOutput output) {
		MemoryBuckets buckets = new MemoryBuckets(clock, 100);

		for (int i = 0; i < 1_000; i++) {
			buckets.resolve("key-" + i, 1, TEN_MINUTES_MS).tryConsume(1);
		}

		assertThat(buckets.size()).isLessThanOrEqualTo(100);
		assertThat(output).containsOnlyOnce("In-memory rate limits reached 100 keys");
	}

	@Test
	void withRedisTheDecisionIsTheAtomicScriptsAndItsAnswerIsObeyed() {
		RateLimitingConfig config = withRedis();
		when(redis.execute(eq(RateLimitingConfig.WINDOW_SCRIPT), eq(REDIS_KEY), eq("10"), eq("1"), eq("600000")))
				.thenReturn(9L);

		TokenBucket bucket = config.resolveBucket(KEY, 10, TEN_MINUTES_MS);

		assertThat(bucket.tryConsume(1)).isTrue();
		assertThat(bucket.getRemainingTokens()).isEqualTo(9);
		assertThat(bucket.getSecondsToWait()).isZero();
		assertThat(config.keysInMemory()).isZero();
	}

	@Test
	void aRefusalFromRedisCarriesTheTimeLeftInItsWindowRoundedUp() {
		RateLimitingConfig config = withRedis();
		when(redis.execute(eq(RateLimitingConfig.WINDOW_SCRIPT), eq(REDIS_KEY), eq("10"), eq("1"), eq("600000")))
				.thenReturn(-299_001L);

		TokenBucket bucket = config.resolveBucket(KEY, 10, TEN_MINUTES_MS);

		assertThat(bucket.tryConsume(1)).isFalse();
		assertThat(bucket.getRemainingTokens()).isZero();
		assertThat(bucket.getSecondsToWait()).isEqualTo(300);
	}

	// Every instance spends from the same counter, so the budget is one per caller and not one per instance.
	@Test
	void eachRequestAsksRedisAgainRatherThanTrustingWhatItSawBefore() {
		RateLimitingConfig config = withRedis();
		when(redis.execute(eq(RateLimitingConfig.WINDOW_SCRIPT), eq(REDIS_KEY), eq("10"), eq("1"), eq("600000")))
				.thenReturn(0L, -1L);

		assertThat(config.resolveBucket(KEY, 10, TEN_MINUTES_MS).tryConsume(1)).isTrue();
		TokenBucket next = config.resolveBucket(KEY, 10, TEN_MINUTES_MS);

		assertThat(next.tryConsume(1)).isFalse();
		assertThat(next.getSecondsToWait()).isEqualTo(1);
		verify(redis, times(2)).execute(eq(RateLimitingConfig.WINDOW_SCRIPT), anyList(), anyString(), anyString(),
				anyString());
	}

	@Test
	void whileRedisIsDownTheLimitIsKeptInMemoryAndTheOutageIsLoggedOncePerMinute(CapturedOutput output) {
		RateLimitingConfig config = withRedis();
		when(redis.execute(eq(RateLimitingConfig.WINDOW_SCRIPT), anyList(), anyString(), anyString(), anyString()))
				.thenThrow(new RedisConnectionFailureException("connection refused"));

		assertThat(config.resolveBucket(KEY, 2, TEN_MINUTES_MS).tryConsume(1)).isTrue();
		assertThat(config.resolveBucket(KEY, 2, TEN_MINUTES_MS).tryConsume(1)).isTrue();
		TokenBucket third = config.resolveBucket(KEY, 2, TEN_MINUTES_MS);

		assertThat(third.tryConsume(1)).isFalse();
		assertThat(third.getSecondsToWait()).isEqualTo(600);
		assertThat(config.keysInMemory()).isEqualTo(1);
		assertThat(output).containsOnlyOnce("Rate limiting cannot reach Redis").contains("connection refused");

		clock.advance(Duration.ofMinutes(1));
		config.resolveBucket(KEY, 2, TEN_MINUTES_MS).tryConsume(1);

		assertThat(output.getOut().split("Rate limiting cannot reach Redis", -1)).hasSize(3);
	}

	// Inside a pipeline or transaction the template answers null instead of a count.
	@Test
	void anAnswerlessRedisIsTreatedAsDown(CapturedOutput output) {
		RateLimitingConfig config = withRedis();

		TokenBucket bucket = config.resolveBucket(KEY, 1, TEN_MINUTES_MS);

		assertThat(bucket.tryConsume(1)).isTrue();
		assertThat(bucket.getRemainingTokens()).isZero();
		assertThat(config.resolveBucket(KEY, 1, TEN_MINUTES_MS).tryConsume(1)).isFalse();
		assertThat(output).contains("Rate limiting cannot reach Redis (no answer)");
	}

	private RateLimitingConfig inMemory() {
		return new RateLimitingConfig(null, clock);
	}

	private RateLimitingConfig withRedis() {
		return new RateLimitingConfig(redis, clock);
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
