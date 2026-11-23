package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.EmailCenterSettings;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Whether bounces are really being heard. A source that is configured but cannot be read (a missing permission, for
 * example) does not count: mails then wait until it can.
 */
@Slf4j
@Component
class EmailBounceMonitor {

	static final Duration FRESH = Duration.ofMinutes(15);

	private final EmailCenterSettings settings;
	private final List<EmailBounceReader> readers;
	private final Clock clock;
	private volatile @Nullable Instant lastRead;

	@Autowired
	EmailBounceMonitor(EmailCenterSettings settings, List<EmailBounceReader> readers) {
		this(settings, readers, Clock.systemUTC());
	}

	EmailBounceMonitor(EmailCenterSettings settings, List<EmailBounceReader> readers, Clock clock) {
		this.settings = settings;
		this.readers = readers;
		this.clock = clock;
	}

	void read() {
		try {
			readers.forEach(EmailBounceReader::read);
			lastRead = clock.instant();
		} catch (SdkException unreadable) {
			log.warn("Bounces could not be read, so e-mails wait: {}", unreadable.getClass().getSimpleName());
		}
	}

	boolean hearing() {
		Instant read = lastRead;
		return settings.hearsBounces() && !readers.isEmpty() && read != null
				&& read.isAfter(clock.instant().minus(FRESH));
	}

	boolean refuses(String address) {
		return readers.stream().anyMatch(reader -> reader.refuses(address));
	}
}
