package com.dps.teknokent.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.config.RateLimitingConfig.TokenBucket;
import com.dps.teknokent.exception.InvalidTeamCodeException;
import com.dps.teknokent.util.ClientAddressKey;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/**
 * Limits guessing team codes where a code actually joins a team: "Ekibe katıl" at sign-up and "Kodum var" for an
 * account without a startup. Only attempts that fail on the code count ({@link InvalidTeamCodeException}), so a
 * person who joins, or is refused for something else, spends nothing. Once a budget is spent, every attempt is
 * refused until its window ends, the right code included, so a guesser cannot tell a hit from a miss.
 * <p>
 * Each attempt takes its token before it runs and gives it back unless it failed on the code, so parallel
 * attempts cannot all slip past a budget that has one token left.
 * <p>
 * Three budgets: per login (a Firebase login or an account), per visitor address when the frontend's server names
 * one, and one shared by everyone. The address comes from a header any direct caller could write, so it only
 * separates honest visitors; the shared budget caps a caller who rotates logins and addresses. Each new login needs
 * a verified address first, but a determined caller can still spend the shared budget and hold every join off until
 * its window ends; that trip is logged.
 */
@Slf4j
@Service
public class JoinCodeAttemptLimiter {

	static final long WINDOW_MS = 10 * 60_000;
	static final long PER_LOGIN = 5;
	static final long PER_ADDRESS = 20;
	static final long SHARED = 500;

	private static final String LOGIN_KEY = "join_code_login:";
	private static final String ADDRESS_KEY = "join_code_ip:";
	private static final String SHARED_KEY = "join_code_all";
	private static final long LOG_INTERVAL_MS = 60_000;

	private final RateLimitingConfig rateLimitingConfig;
	private final Clock clock;
	private final AtomicLong nextSharedWarningAt = new AtomicLong();

	@Autowired
	public JoinCodeAttemptLimiter(RateLimitingConfig rateLimitingConfig) {
		this(rateLimitingConfig, Clock.systemUTC());
	}

	JoinCodeAttemptLimiter(RateLimitingConfig rateLimitingConfig, Clock clock) {
		this.rateLimitingConfig = rateLimitingConfig;
		this.clock = clock;
	}

	/** Runs {@code join} unless a budget is spent. {@code login} names who is trying ("firebase:uid", "user:id"). */
	public <T> T guard(String login, @Nullable String clientAddress, Supplier<T> join) {
		List<TokenBucket> taken = new ArrayList<>();
		for (Budget budget : budgets(login, clientAddress)) {
			if (!budget.bucket().tryConsume(1)) {
				taken.forEach(bucket -> bucket.refund(1));
				if (budget.shared()) {
					warnSharedSpent();
				}
				throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
						Texts.forRequest("error.tooManyJoinAttempts"));
			}
			taken.add(budget.bucket());
		}
		try {
			T joined = join.get();
			taken.forEach(bucket -> bucket.refund(1));
			return joined;
		} catch (InvalidTeamCodeException wrongCode) {
			throw wrongCode;
		} catch (RuntimeException otherFailure) {
			taken.forEach(bucket -> bucket.refund(1));
			throw otherFailure;
		}
	}

	private List<Budget> budgets(String login, @Nullable String clientAddress) {
		List<Budget> budgets = new ArrayList<>();
		budgets.add(new Budget(rateLimitingConfig.resolveBucket(LOGIN_KEY + login, PER_LOGIN, WINDOW_MS), false));
		String address = ClientAddressKey.of(clientAddress);
		// Without a named visitor there is no per-address budget: one shared "unknown" key would lock everyone out.
		if (!ClientAddressKey.UNKNOWN.equals(address)) {
			budgets.add(new Budget(
					rateLimitingConfig.resolveBucket(ADDRESS_KEY + address, PER_ADDRESS, WINDOW_MS), false));
		}
		budgets.add(new Budget(rateLimitingConfig.resolveBucket(SHARED_KEY, SHARED, WINDOW_MS), true));
		return budgets;
	}

	/** Once a minute at most: while the shared budget is spent every join attempt is refused. */
	private void warnSharedSpent() {
		long now = clock.millis();
		long due = nextSharedWarningAt.get();
		if (now >= due && nextSharedWarningAt.compareAndSet(due, now + LOG_INTERVAL_MS)) {
			log.warn("The shared budget for wrong team codes is spent; every code join is refused until it refills.");
		}
	}

	private record Budget(TokenBucket bucket, boolean shared) {
	}
}
