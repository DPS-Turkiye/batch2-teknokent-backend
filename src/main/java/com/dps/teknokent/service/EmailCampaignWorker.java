package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.config.EmailCenterSettings;
import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.EmailSkipReason;
import com.dps.teknokent.domain.EmailSuppressionReason;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.EmailCampaignRecipientRepository;
import com.dps.teknokent.repository.EmailCampaignRepository;
import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.EmailDomains;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.RandomTokens;
import com.dps.teknokent.util.Sha256;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Sends confirmed mails, one per person, at most the deployment's rate per run. Each row is claimed in its own
 * transaction before SES is called, so a crash leaves a row that is known to be in doubt and is never sent twice.
 * Switching the email center off stops it within a run (one second); what waits stays waiting.
 */
@Slf4j
@Service
public class EmailCampaignWorker {

	static final int MAX_ATTEMPTS = 8;
	static final Duration IN_DOUBT_AFTER = Duration.ofMinutes(5);
	private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
	private static final int TOKEN_BYTES = 32;
	private static final Set<EmailRecipientStatus> WAITING = Set.copyOf(
			EnumSet.of(EmailRecipientStatus.PENDING, EmailRecipientStatus.SENDING));

	private final EmailCampaignRepository campaignRepository;
	private final EmailCampaignRecipientRepository recipientRepository;
	private final AppUserRepository appUserRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationPendingMemberRepository pendingMemberRepository;
	private final EmailSuppressionService suppressionService;
	private final EmailComposer composer;
	private final EmailCenterMailer mailer;
	private final EmailBounceMonitor bounceMonitor;
	private final EmailCenterSettings settings;
	private final TransactionTemplate transaction;
	private final Clock clock;

	@Autowired
	EmailCampaignWorker(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			AppUserRepository appUserRepository,
			ProgramRepository programRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			ProgramStartupRepository programStartupRepository,
			ProgramMentorRepository programMentorRepository,
			OrganizationPendingMemberRepository pendingMemberRepository,
			EmailSuppressionService suppressionService,
			EmailComposer composer,
			EmailCenterMailer mailer,
			EmailBounceMonitor bounceMonitor,
			EmailCenterSettings settings,
			PlatformTransactionManager transactionManager) {
		this(campaignRepository, recipientRepository, appUserRepository, programRepository,
				programCoordinatorRepository, programStartupRepository, programMentorRepository,
				pendingMemberRepository, suppressionService, composer, mailer, bounceMonitor, settings,
				transactionManager,
				Clock.systemUTC());
	}

	EmailCampaignWorker(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			AppUserRepository appUserRepository,
			ProgramRepository programRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			ProgramStartupRepository programStartupRepository,
			ProgramMentorRepository programMentorRepository,
			OrganizationPendingMemberRepository pendingMemberRepository,
			EmailSuppressionService suppressionService,
			EmailComposer composer,
			EmailCenterMailer mailer,
			EmailBounceMonitor bounceMonitor,
			EmailCenterSettings settings,
			PlatformTransactionManager transactionManager,
			Clock clock) {
		this.campaignRepository = campaignRepository;
		this.recipientRepository = recipientRepository;
		this.appUserRepository = appUserRepository;
		this.programRepository = programRepository;
		this.programCoordinatorRepository = programCoordinatorRepository;
		this.programStartupRepository = programStartupRepository;
		this.programMentorRepository = programMentorRepository;
		this.pendingMemberRepository = pendingMemberRepository;
		this.suppressionService = suppressionService;
		this.composer = composer;
		this.mailer = mailer;
		this.bounceMonitor = bounceMonitor;
		this.settings = settings;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** One run; the scheduler's pause between runs makes {@code ratePerSecond} the ceiling. */
	public void run() {
		Instant now = clock.instant();
		// Also while nothing may leave: what waited through an outage must not go out when it is over.
		cancelExpired(now);
		if (!settings.canSend() || !bounceMonitor.hearing()) {
			finishCampaigns();
			return;
		}
		transaction.executeWithoutResult(
				status -> recipientRepository.failStale(now.minus(IN_DOUBT_AFTER), "NO_ANSWER"));
		List<UUID> due = recipientRepository.findDueIds(now,
				PageRequest.of(0, bounceMonitor.limit(settings.getRatePerSecond())));
		for (UUID id : due) {
			try {
				sendOne(id);
			} catch (RuntimeException failure) {
				// One row that cannot be prepared must not hold up everybody behind it.
				log.error("E-mail {} could not be sent", id, failure);
				transaction.executeWithoutResult(status -> recipientRepository.failWaiting(id, "INTERNAL"));
			}
		}
		finishCampaigns();
	}

	/** Campaign first, then its rows: the order a stop and a claim lock in too. */
	private void cancelExpired(Instant now) {
		for (UUID id : campaignRepository.findSendingStartedBefore(now.minus(settings.getMaxWait()))) {
			try {
				transaction.executeWithoutResult(status -> {
					if (lock(id) != null) {
						recipientRepository.skipWaiting(id, EmailSkipReason.EXPIRED.name());
					}
				});
			} catch (RuntimeException failure) {
				log.error("What waited too long in e-mail {} could not be cancelled", id, failure);
			}
		}
	}

	private void sendOne(UUID id) {
		Instant now = clock.instant();
		transaction.execute(status -> Optional.ofNullable(claim(id, now))).ifPresent(mail -> {
			EmailCenterMailer.Outcome outcome = deliver(mail);
			transaction.executeWithoutResult(status -> recipientRepository.findById(id)
					.ifPresent(row -> record(row, outcome)));
		});
	}

	private EmailCenterMailer.Outcome deliver(EmailCenterMailer.Mail mail) {
		try {
			if (bounceMonitor.refuses(mail.to())) {
				suppressionService.suppress(mail.to(), EmailSuppressionReason.HARD_BOUNCE);
				return new EmailCenterMailer.Outcome(EmailCenterMailer.Result.FAILED, null, "SES_SUPPRESSED");
			}
		} catch (SdkException unknown) {
			// Nothing has left yet, so this one may be tried again.
			return new EmailCenterMailer.Outcome(EmailCenterMailer.Result.RETRY, null, "SES_UNREACHABLE");
		}
		return mailer.send(mail);
	}

	/** Null when the row is no longer waiting or must not be written to any more. */
	private EmailCenterMailer.@Nullable Mail claim(UUID id, Instant now) {
		UUID campaignId = recipientRepository.findById(id).map(EmailCampaignRecipient::getCampaignId).orElse(null);
		EmailCampaign campaign = campaignId == null ? null : lock(campaignId);
		// Under the lock a stop takes too: either the stop is seen here, or this row is in flight before the stop.
		if (campaign == null || campaign.getStatus() != EmailCampaignStatus.SENDING
				|| campaign.getStartsAt() == null || campaign.getStartsAt().isAfter(now)
				|| recipientRepository.claim(id, now) == 0) {
			return null;
		}
		EmailCampaignRecipient row = recipientRepository.findById(id).orElseThrow();
		Program program = programRepository.findById(campaign.getProgramId()).orElse(null);
		AppUser sender = appUserRepository.findById(campaign.getCreatedBy()).orElse(null);
		AppUser reader = row.getUserId() == null ? null : appUserRepository.findById(row.getUserId()).orElse(null);
		EmailSkipReason reason = skipReason(row, campaign, program, sender, reader, now);
		if (program == null || sender == null || row.getAddress() == null || reason != null) {
			row.setStatus(EmailRecipientStatus.SKIPPED);
			row.setErrorCode(reason == null ? null : reason.name());
			recipientRepository.save(row);
			return null;
		}
		String token = RandomTokens.urlSafe(TOKEN_BYTES);
		row.setUnsubscribeTokenHash(Sha256.hex(token));
		recipientRepository.save(row);
		return composer.mail(campaign, sender, program.getName(), row.getAddress(),
				reader == null ? null : reader.getPreferredLanguage(), token);
	}

	/** The reviewed list can only shrink: people who left it for good since, or a sender who may no longer write. */
	private @Nullable EmailSkipReason skipReason(EmailCampaignRecipient row, EmailCampaign campaign,
			@Nullable Program program, @Nullable AppUser sender, @Nullable AppUser reader, Instant now) {
		if (campaign.getStartsAt().plus(settings.getMaxWait()).isBefore(now)) {
			return EmailSkipReason.EXPIRED;
		}
		if (row.getAddress() == null) {
			return EmailSkipReason.NO_ADDRESS;
		}
		if (program == null || sender == null || !maySend(sender, program)) {
			return EmailSkipReason.SENDER_NOT_ALLOWED;
		}
		if (row.getUserId() != null && (reader == null || reader.getStatus() != UserAccountStatus.APPROVED)) {
			return EmailSkipReason.ACCOUNT_NOT_APPROVED;
		}
		if (reader != null && !row.getAddress().equals(EmailDomains.normalizeEmail(reader.getEmail()))) {
			return EmailSkipReason.ADDRESS_CHANGED;
		}
		if (!stillInAudience(row, program, reader)) {
			return EmailSkipReason.LEFT_THE_PROGRAM;
		}
		if (suppressionService.isSuppressed(row.getAddress())) {
			return EmailSkipReason.SUPPRESSED;
		}
		return settings.mayWriteTo(row.getAddress()) ? null : EmailSkipReason.NOT_ON_ALLOWLIST;
	}

	/** Whether the reason the person was on the reviewed list still holds. */
	private boolean stillInAudience(EmailCampaignRecipient row, Program program, @Nullable AppUser reader) {
		return switch (row.getKind()) {
		case PARTICIPANT -> row.getOrganizationId() != null
				&& programStartupRepository.findByProgramIdAndOrganizationId(program.getId(),
						row.getOrganizationId()).isPresent();
		case MENTOR -> reader != null && programMentorRepository
				.findByProgramIdAndMentorId(program.getId(), reader.getId())
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.isPresent();
		case UNJOINED -> row.getPendingMemberId() != null
				&& pendingMemberRepository.existsById(row.getPendingMemberId());
		};
	}

	private boolean maySend(AppUser sender, Program program) {
		if (sender.getStatus() != UserAccountStatus.APPROVED) {
			return false;
		}
		return sender.has(UserRole.ADMIN) || (sender.has(UserRole.COORDINATOR)
				&& ProgramCoordination.coordinates(program, sender.getId(), programCoordinatorRepository));
	}

	private @Nullable EmailCampaign lock(UUID campaignId) {
		return campaignRepository.findByIdForUpdate(campaignId).orElse(null);
	}

	private void record(EmailCampaignRecipient row, EmailCenterMailer.Outcome outcome) {
		EmailCampaign campaign = lock(row.getCampaignId());
		row.setErrorCode(outcome.errorCode());
		if (outcome.result() == EmailCenterMailer.Result.RETRY
				&& (campaign == null || campaign.getStatus() != EmailCampaignStatus.SENDING)) {
			// Stopped while SES was being asked, and SES did not take it: there is no second attempt.
			row.setStatus(EmailRecipientStatus.SKIPPED);
			row.setErrorCode(EmailSkipReason.STOPPED.name());
			recipientRepository.save(row);
			return;
		}
		switch (outcome.result()) {
		case ACCEPTED -> {
			row.setStatus(EmailRecipientStatus.SENT);
			row.setSesMessageId(outcome.messageId());
			row.setSentAt(clock.instant());
		}
		case RETRY -> {
			boolean again = row.getAttempts() < MAX_ATTEMPTS;
			row.setStatus(again ? EmailRecipientStatus.PENDING : EmailRecipientStatus.FAILED);
			row.setNextAttemptAt(again
					? clock.instant().plus(RETRY_AFTER.multipliedBy(row.getAttempts()))
					: null);
		}
		case FAILED -> row.setStatus(EmailRecipientStatus.FAILED);
		}
		recipientRepository.save(row);
	}

	private void finishCampaigns() {
		transaction.executeWithoutResult(status -> campaignRepository.finishDone(WAITING, clock.instant()));
	}
}
