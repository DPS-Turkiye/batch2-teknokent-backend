package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.EmailCampaignRecipientRepository;
import com.dps.teknokent.repository.EmailCampaignRepository;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells a mail's sender and the admins, in the app, that some of it failed, bounced or was reported as spam. One
 * notification per mail at most every ten minutes, with the total so far; it names no recipient.
 */
@Slf4j
@Service
public class EmailAlertService {

	static final Duration QUIET = Duration.ofMinutes(10);
	/** How long after its sending a mail's problems are still alerted. */
	static final Duration WATCHED = Duration.ofDays(30);
	private static final int MAX_SUBJECT = 120;

	private final EmailCampaignRepository campaignRepository;
	private final EmailCampaignRecipientRepository recipientRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;
	private final TransactionTemplate transaction;
	private final Clock clock;

	@Autowired
	public EmailAlertService(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			AppUserRepository appUserRepository,
			NotificationService notificationService,
			PlatformTransactionManager transactionManager) {
		this(campaignRepository, recipientRepository, appUserRepository, notificationService, transactionManager,
				Clock.systemUTC());
	}

	EmailAlertService(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			AppUserRepository appUserRepository,
			NotificationService notificationService,
			PlatformTransactionManager transactionManager,
			Clock clock) {
		this.campaignRepository = campaignRepository;
		this.recipientRepository = recipientRepository;
		this.appUserRepository = appUserRepository;
		this.notificationService = notificationService;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** One transaction per mail, so one that cannot be alerted does not silence the others. */
	public void alertProblems() {
		Instant now = clock.instant();
		for (UUID id : recipientRepository.findCampaignsToAlert(now.minus(WATCHED), now.minus(QUIET),
				EmailRecipientStatus.PROBLEMS)) {
			try {
				transaction.executeWithoutResult(status -> campaignRepository.findByIdForUpdate(id)
						.ifPresent(campaign -> alert(campaign, now)));
			} catch (RuntimeException failure) {
				log.error("The alert for e-mail {} failed", id, failure);
			}
		}
	}

	private void alert(EmailCampaign campaign, Instant now) {
		int problems = Math.toIntExact(recipientRepository.countByCampaignIdAndStatusIn(
				campaign.getId(), EmailRecipientStatus.PROBLEMS));
		Map<UUID, AppUser> readers = new LinkedHashMap<>();
		appUserRepository.findById(campaign.getCreatedBy())
				.filter(sender -> sender.getStatus() == UserAccountStatus.APPROVED)
				.ifPresent(sender -> readers.put(sender.getId(), sender));
		appUserRepository.findByPermissionAndStatusOrderByFullNameAsc(UserRole.ADMIN, UserAccountStatus.APPROVED)
				.forEach(admin -> readers.put(admin.getId(), admin));
		String subject = campaign.getSubject().length() <= MAX_SUBJECT
				? campaign.getSubject()
				: campaign.getSubject().substring(0, MAX_SUBJECT) + "…";
		for (AppUser reader : readers.values()) {
			String area = reader.has(UserRole.ADMIN) ? "/admin" : "/coordinator";
			notificationService.create(reader,
					Texts.get(reader, "emailCenter.alert.title"),
					Texts.get(reader, "emailCenter.alert.body", subject, problems),
					area + "/emails/" + campaign.getId());
		}
		campaign.setAlertedProblems(problems);
		campaign.setLastAlertAt(now);
		campaignRepository.save(campaign);
	}
}
