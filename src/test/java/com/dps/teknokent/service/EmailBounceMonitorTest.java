package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.config.EmailCenterSettings;

import software.amazon.awssdk.core.exception.SdkClientException;

class EmailBounceMonitorTest {

	private final Instant now = Instant.parse("2026-11-23T10:00:00Z");
	private final AtomicReference<Instant> time = new AtomicReference<>(now);
	private final Clock clock = new Clock() {

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return time.get();
		}
	};

	@Test
	void hearsOnlyAfterASuccessfulReadAndOnlyForAWhile() {
		EmailBounceMonitor monitor = new EmailBounceMonitor(settings("suppression-list"), List.of(() -> {
		}), clock);
		assertThat(monitor.hearing()).isFalse();
		monitor.read();
		assertThat(monitor.hearing()).isTrue();
		time.set(now.plus(EmailBounceMonitor.FRESH));
		assertThat(monitor.hearing()).isFalse();
	}

	@Test
	void aSourceThatCannotBeReadDoesNotCount() {
		EmailBounceMonitor monitor = new EmailBounceMonitor(settings("suppression-list"), List.of(() -> {
			throw SdkClientException.create("no permission");
		}), clock);
		monitor.read();
		assertThat(monitor.hearing()).isFalse();
	}

	@Test
	void noSourceNeverCounts() {
		EmailBounceMonitor monitor = new EmailBounceMonitor(settings("none"), List.of(), clock);
		monitor.read();
		assertThat(monitor.hearing()).isFalse();
	}

	private static EmailCenterSettings settings(String source) {
		return new EmailCenterSettings(true, "noreply@menton.test", "", "", 10, 1000, "", source, "", 300, 120);
	}
}
