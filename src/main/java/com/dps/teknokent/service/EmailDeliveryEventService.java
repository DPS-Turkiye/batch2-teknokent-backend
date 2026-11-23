package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.EmailDeliveryEvent;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.EmailSuppressionReason;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.repository.EmailCampaignRecipientRepository;

/** Writes what SES reports into the send log, wherever the report came from. Every call may be repeated. */
@Service
public class EmailDeliveryEventService {

	/** How far back an address on SES's suppression list is matched to a mail. */
	static final Duration MATCH_WINDOW = Duration.ofDays(14);

	private final EmailCampaignRecipientRepository recipientRepository;
	private final EmailSuppressionService suppressionService;
	private final Clock clock;

	@Autowired
	public EmailDeliveryEventService(
			EmailCampaignRecipientRepository recipientRepository,
			EmailSuppressionService suppressionService) {
		this(recipientRepository, suppressionService, Clock.systemUTC());
	}

	EmailDeliveryEventService(
			EmailCampaignRecipientRepository recipientRepository,
			EmailSuppressionService suppressionService,
			Clock clock) {
		this.recipientRepository = recipientRepository;
		this.suppressionService = suppressionService;
		this.clock = clock;
	}

	/** False for a mail that is not the email center's, or whose sending is not written down yet. */
	@Transactional
	public boolean apply(String messageId, EmailDeliveryEvent event) {
		Optional<EmailCampaignRecipient> found = recipientRepository.findBySesMessageId(messageId);
		found.ifPresent(row -> apply(row, event));
		return found.isPresent();
	}

	/**
	 * An address SES no longer delivers to: not written to again, and the mail that caused it (or, when SES does not
	 * name one, the address's last mail) is marked.
	 */
	@Transactional
	public void undeliverable(String address, EmailDeliveryEvent event, @Nullable String messageId) {
		if (messageId != null && apply(messageId, event)) {
			return;
		}
		// With a message id that is not ours, another mail caused it: the address is dead, but no mail of ours is
		// blamed.
		if (messageId == null) {
			recipientRepository.findFirstByAddressAndStatusAndSentAtAfterOrderBySentAtDesc(address,
					EmailRecipientStatus.SENT, clock.instant().minus(MATCH_WINDOW))
					.ifPresent(row -> apply(row, event));
		}
		suppressionService.suppress(address, reasonOf(event));
	}

	private void apply(EmailCampaignRecipient row, EmailDeliveryEvent event) {
		EmailRecipientStatus next = switch (event) {
		case DELIVERED -> EmailRecipientStatus.DELIVERED;
		case HARD_BOUNCE, SOFT_BOUNCE -> EmailRecipientStatus.BOUNCED;
		case COMPLAINT -> EmailRecipientStatus.COMPLAINED;
		case REJECTED -> EmailRecipientStatus.FAILED;
		};
		// Events arrive in any order: a late delivery report does not hide a bounce or a complaint.
		boolean problemStands = EmailRecipientStatus.PROBLEMS.contains(row.getStatus());
		if (!problemStands || event == EmailDeliveryEvent.COMPLAINT) {
			row.setStatus(next);
			if (event != EmailDeliveryEvent.DELIVERED) {
				row.setErrorCode(event.name());
			}
			recipientRepository.save(row);
		}
		if (row.getAddress() != null
				&& (event == EmailDeliveryEvent.HARD_BOUNCE || event == EmailDeliveryEvent.COMPLAINT)) {
			suppressionService.suppress(row.getAddress(), reasonOf(event));
		}
	}

	private static EmailSuppressionReason reasonOf(EmailDeliveryEvent event) {
		return event == EmailDeliveryEvent.COMPLAINT
				? EmailSuppressionReason.COMPLAINT
				: EmailSuppressionReason.HARD_BOUNCE;
	}
}
