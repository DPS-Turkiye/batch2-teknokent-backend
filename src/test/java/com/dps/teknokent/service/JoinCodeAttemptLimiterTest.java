package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.InvalidTeamCodeException;

class JoinCodeAttemptLimiterTest {

	private final JoinCodeAttemptLimiter limiter = new JoinCodeAttemptLimiter(new RateLimitingConfig());
	private final AtomicInteger joins = new AtomicInteger();

	@Test
	void aLoginIsStoppedAfterFiveWrongCodesEvenWithTheRightOne() {
		for (int i = 0; i < JoinCodeAttemptLimiter.PER_LOGIN; i++) {
			assertWrongCode(() -> limiter.guard("firebase:a", null, this::wrongCode));
		}

		assertTooMany(() -> limiter.guard("firebase:a", null, this::join));
		assertThat(joins).hasValue(0);
		assertThat(limiter.guard("firebase:b", null, this::join)).isEqualTo("joined");
	}

	@Test
	void joiningAndOtherRefusalsSpendNothing() {
		for (int i = 0; i < 20; i++) {
			limiter.guard("user:c", "203.0.113.5", this::join);
			assertThatThrownBy(() -> limiter.guard("user:c", "203.0.113.5", () -> {
				throw new InvalidRequestException("Bu e-posta programın listesinde yok.");
			})).isNotInstanceOf(ResponseStatusException.class);
		}

		assertThat(limiter.guard("user:c", "203.0.113.5", this::join)).isEqualTo("joined");
	}

	@Test
	void oneAddressIsStoppedAcrossLogins() {
		for (int i = 0; i < JoinCodeAttemptLimiter.PER_ADDRESS; i++) {
			String login = "firebase:rotating-" + i;
			assertWrongCode(() -> limiter.guard(login, "198.51.100.7", this::wrongCode));
		}

		assertTooMany(() -> limiter.guard("firebase:fresh", "198.51.100.7", this::join));
		assertThat(limiter.guard("firebase:fresh", "198.51.100.8", this::join)).isEqualTo("joined");
		// Without a named visitor there is no address budget, so a fresh login still gets in.
		assertThat(limiter.guard("firebase:fresh-2", null, this::join)).isEqualTo("joined");
	}

	@Test
	void theSharedBudgetCapsRotatingLoginsAndAddresses() {
		for (int i = 0; i < JoinCodeAttemptLimiter.SHARED; i++) {
			String login = "firebase:many-" + i;
			assertWrongCode(() -> limiter.guard(login, null, this::wrongCode));
		}

		assertTooMany(() -> limiter.guard("firebase:anyone", "192.0.2.1", this::join));
	}

	@Test
	void parallelWrongCodesCannotSlipPastALoginsLastTokens() throws Exception {
		int attempts = 20;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger reachedTheCode = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
			List<Future<?>> runs = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				runs.add(pool.submit(() -> {
					start.await();
					try {
						limiter.guard("firebase:burst", null, () -> {
							reachedTheCode.incrementAndGet();
							sleepBriefly();
							return wrongCode();
						});
					} catch (ResponseStatusException tooMany) {
						refused.incrementAndGet();
					} catch (InvalidTeamCodeException expected) {
						// counted
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> run : runs) {
				run.get(10, TimeUnit.SECONDS);
			}
		}

		assertThat(reachedTheCode).hasValue((int) JoinCodeAttemptLimiter.PER_LOGIN);
		assertThat(refused).hasValue(attempts - (int) JoinCodeAttemptLimiter.PER_LOGIN);
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private String join() {
		joins.incrementAndGet();
		return "joined";
	}

	private String wrongCode() {
		throw new InvalidTeamCodeException("Bu kodla bir startup bulunamadı.");
	}

	private static void assertWrongCode(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOf(InvalidTeamCodeException.class);
	}

	private static void assertTooMany(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
			assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
			assertThat(error.getReason())
					.isEqualTo("Çok fazla hatalı takım kodu denendi. 10 dakika bekleyip tekrar dene.");
		});
	}
}
