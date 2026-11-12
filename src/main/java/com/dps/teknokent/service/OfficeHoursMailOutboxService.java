package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.dps.teknokent.entity.OfficeHoursMailOutbox;
import com.dps.teknokent.repository.OfficeHoursMailOutboxRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Office-hours e-mails go through a table instead of straight to SES, so a send that fails is tried again rather
 * than lost (FR-OH-17). A mail is queued in the same transaction as the change it reports, so a change that rolls
 * back sends nothing; the scheduler then sends what is due, at most a minute later.
 */
@Slf4j
@Service
public class OfficeHoursMailOutboxService {

	/** One send and five retries, about four and a half hours in all, then the mail is dropped. */
	static final int MAX_ATTEMPTS = 6;
	/** The wait after the first, second, … failed attempt. */
	static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(3));
	/** Long enough for any SES call; a server that dies mid-send leaves the mail to be tried again after it. */
	static final Duration LEASE = Duration.ofMinutes(5);

	private final OfficeHoursMailOutboxRepository outboxRepository;
	private final MailService mailService;
	private final Clock clock;

	@Autowired
	public OfficeHoursMailOutboxService(OfficeHoursMailOutboxRepository outboxRepository, MailService mailService) {
		this(outboxRepository, mailService, Clock.systemUTC());
	}

	OfficeHoursMailOutboxService(OfficeHoursMailOutboxRepository outboxRepository, MailService mailService,
			Clock clock) {
		this.outboxRepository = outboxRepository;
		this.mailService = mailService;
		this.clock = clock;
	}

	/** Queues a mail in the caller's transaction; nothing when mail is switched off, as nothing could send it. */
	public void enqueue(String to, String subject, String body) {
		if (!mailService.isConfigured()) {
			return;
		}
		Instant now = clock.instant();
		OfficeHoursMailOutbox mail = new OfficeHoursMailOutbox();
		mail.setId(UUID.randomUUID());
		mail.setToAddress(to);
		mail.setSubject(subject);
		mail.setBody(body);
		mail.setNextAttemptAt(now);
		mail.setCreatedAt(now);
		outboxRepository.save(mail);
	}

	/**
	 * Sends the mails that are due. Each is claimed first, so two servers never both send it; a sent one is deleted, a
	 * failed one waits its back-off, and one that failed {@link #MAX_ATTEMPTS} times is dropped. Returns how many went.
	 */
	public int deliverDue(Instant now) {
		int sent = 0;
		for (OfficeHoursMailOutbox mail : outboxRepository
				.findTop50ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(now)) {
			if (outboxRepository.claim(mail.getId(), mail.getNextAttemptAt(), now.plus(LEASE)) != 1) {
				continue;
			}
			int attempt = mail.getAttempts() + 1;
			if (mailService.sendNow(mail.getToAddress(), mail.getSubject(), mail.getBody())) {
				outboxRepository.deleteSent(mail.getId());
				sent++;
				continue;
			}
			if (attempt >= MAX_ATTEMPTS) {
				// The address stays out of the log; the id is enough to match it with SES's own records.
				log.warn("Office hours mail {} dropped after {} failed attempts", mail.getId(), attempt);
				outboxRepository.deleteSent(mail.getId());
				continue;
			}
			outboxRepository.retryAt(mail.getId(), now.plus(BACKOFF.get(attempt - 1)));
		}
		return sent;
	}
}
