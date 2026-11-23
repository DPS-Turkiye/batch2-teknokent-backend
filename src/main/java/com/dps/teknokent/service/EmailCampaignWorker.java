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
import com.dps.teknokent.domain.EmailBounceSource;
import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.EmailCampaignRecipientRepository;
import com.dps.teknokent.repository.EmailCampaignRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.RandomTokens;
import com.dps.teknokent.util.Sha256;

/**
 * Sends confirmed mails, one per person, at most the deployment's rate per run. Each row is claimed in its own
 * transaction before SES is called, so a crash leaves a row that is known to be in doubt and is never sent twice.
 * Switching the email center off stops it between two mails; what waits stays waiting.
 */
@Service
public class EmailCampaignWorker {

	static final int MAX_ATTEMPTS = 3;
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
	private final EmailSuppressionService suppressionService;
	private final EmailComposer composer;
	private final EmailCenterMailer mailer;
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
			EmailSuppressionService suppressionService,
			EmailComposer composer,
			EmailCenterMailer mailer,
			EmailCenterSettings settings,
			PlatformTransactionManager transactionManager) {
		this(campaignRepository, recipientRepository, appUserRepository, programRepository,
				programCoordinatorRepository, suppressionService, composer, mailer, settings, transactionManager,
				Clock.systemUTC());
	}

	EmailCampaignWorker(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			AppUserRepository appUserRepository,
			ProgramRepository programRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			EmailSuppressionService suppressionService,
			EmailComposer composer,
			EmailCenterMailer mailer,
			EmailCenterSettings settings,
			PlatformTransactionManager transactionManager,
			Clock clock) {
		this.campaignRepository = campaignRepository;
		this.recipientRepository = recipientRepository;
		this.appUserRepository = appUserRepository;
		this.programRepository = programRepository;
		this.programCoordinatorRepository = programCoordinatorRepository;
		this.suppressionService = suppressionService;
		this.composer = composer;
		this.mailer = mailer;
		this.settings = settings;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** One run; the scheduler's pause between runs makes {@code ratePerSecond} the ceiling. */
	public void run() {
		if (!settings.canSend() || settings.getBounceSource() == EmailBounceSource.NONE) {
			return;
		}
		Instant now = clock.instant();
		transaction.executeWithoutResult(
				status -> recipientRepository.failStale(now.minus(IN_DOUBT_AFTER), "NO_ANSWER"));
		List<UUID> due = recipientRepository.findDueIds(now, PageRequest.of(0, settings.getRatePerSecond()));
		for (UUID id : due) {
			sendOne(id);
		}
		finishCampaigns();
	}

	private void sendOne(UUID id) {
		Instant now = clock.instant();
		transaction.execute(status -> Optional.ofNullable(claim(id, now))).ifPresent(mail -> {
			EmailCenterMailer.Outcome outcome = mailer.send(mail);
			transaction.executeWithoutResult(status -> recipientRepository.findById(id)
					.ifPresent(row -> record(row, outcome)));
		});
	}

	/** Null when the row is no longer waiting or must not be written to any more. */
	private EmailCenterMailer.@Nullable Mail claim(UUID id, Instant now) {
		if (recipientRepository.claim(id, now) == 0) {
			return null;
		}
		EmailCampaignRecipient row = recipientRepository.findById(id).orElseThrow();
		EmailCampaign campaign = campaignRepository.findById(row.getCampaignId()).orElseThrow();
		Program program = programRepository.findById(campaign.getProgramId()).orElse(null);
		AppUser sender = appUserRepository.findById(campaign.getCreatedBy()).orElse(null);
		AppUser reader = row.getUserId() == null ? null : appUserRepository.findById(row.getUserId()).orElse(null);
		String reason = skipReason(row, program, sender, reader);
		if (program == null || sender == null || row.getAddress() == null || reason != null) {
			row.setStatus(EmailRecipientStatus.SKIPPED);
			row.setErrorCode(reason);
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
	private @Nullable String skipReason(EmailCampaignRecipient row, @Nullable Program program,
			@Nullable AppUser sender, @Nullable AppUser reader) {
		if (row.getAddress() == null) {
			return "NO_ADDRESS";
		}
		if (program == null || sender == null || !maySend(sender, program)) {
			return "SENDER_NOT_ALLOWED";
		}
		if (row.getUserId() != null && (reader == null || reader.getStatus() != UserAccountStatus.APPROVED)) {
			return "ACCOUNT_NOT_APPROVED";
		}
		if (suppressionService.isSuppressed(row.getAddress())) {
			return "SUPPRESSED";
		}
		return settings.mayWriteTo(row.getAddress()) ? null : "NOT_ON_ALLOWLIST";
	}

	private boolean maySend(AppUser sender, Program program) {
		if (sender.getStatus() != UserAccountStatus.APPROVED) {
			return false;
		}
		return sender.has(UserRole.ADMIN) || (sender.has(UserRole.COORDINATOR)
				&& ProgramCoordination.coordinates(program, sender.getId(), programCoordinatorRepository));
	}

	private void record(EmailCampaignRecipient row, EmailCenterMailer.Outcome outcome) {
		row.setErrorCode(outcome.errorCode());
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
		transaction.executeWithoutResult(status -> {
			for (EmailCampaign campaign : campaignRepository.findByStatus(EmailCampaignStatus.SENDING)) {
				if (!recipientRepository.existsByCampaignIdAndStatusIn(campaign.getId(), WAITING)) {
					campaign.setStatus(EmailCampaignStatus.SENT);
					campaign.setFinishedAt(clock.instant());
					campaignRepository.save(campaign);
				}
			}
		});
	}
}
