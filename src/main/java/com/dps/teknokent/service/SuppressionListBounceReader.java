package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.EmailDeliveryEvent;
import com.dps.teknokent.util.EmailDomains;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsRequest;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SuppressedDestination;
import software.amazon.awssdk.services.sesv2.model.SuppressedDestinationSummary;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;

/**
 * Asks SES which addresses it stopped delivering to. Needs nothing set up beyond the account's suppression list, but
 * hears only hard bounces and complaints, and nothing new about an address that is on the list already.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mail.center", name = "bounce-source", havingValue = "suppression-list")
class SuppressionListBounceReader implements EmailBounceReader {

	/** Longer than a restart or a short outage; what was read before is skipped. */
	private static final Duration LOOK_BACK = Duration.ofDays(2);
	private static final int MAX_REMEMBERED = 10_000;

	private final SesV2Client sesClient;
	private final EmailDeliveryEventService eventService;
	private final Clock clock;
	private final Set<String> seen = new HashSet<>();

	@Autowired
	SuppressionListBounceReader(
			@Qualifier("emailCenterSesClient") SesV2Client sesClient,
			EmailDeliveryEventService eventService) {
		this(sesClient, eventService, Clock.systemUTC());
	}

	SuppressionListBounceReader(SesV2Client sesClient, EmailDeliveryEventService eventService, Clock clock) {
		this.sesClient = sesClient;
		this.eventService = eventService;
		this.clock = clock;
	}

	@Override
	public void read() {
		Instant since = clock.instant().minus(LOOK_BACK);
		String nextToken = null;
		do {
			var page = sesClient.listSuppressedDestinations(ListSuppressedDestinationsRequest.builder()
					.startDate(since)
					.nextToken(nextToken)
					.build());
			page.suppressedDestinationSummaries().forEach(this::readOne);
			nextToken = page.nextToken();
		} while (nextToken != null);
	}

	@Override
	public boolean asksPerAddress() {
		return true;
	}

	@Override
	public boolean refuses(String address) {
		try {
			sesClient.getSuppressedDestination(
					GetSuppressedDestinationRequest.builder().emailAddress(address).build());
			return true;
		} catch (NotFoundException deliverable) {
			return false;
		}
	}

	private void readOne(SuppressedDestinationSummary summary) {
		String key = summary.emailAddress() + summary.reasonAsString() + summary.lastUpdateTime();
		if (seen.contains(key)) {
			return;
		}
		EmailDeliveryEvent event = summary.reason() == SuppressionListReason.COMPLAINT
				? EmailDeliveryEvent.COMPLAINT
				: EmailDeliveryEvent.HARD_BOUNCE;
		try {
			SuppressedDestination details = sesClient.getSuppressedDestination(GetSuppressedDestinationRequest.builder()
					.emailAddress(summary.emailAddress())
					.build()).suppressedDestination();
			eventService.undeliverable(EmailDomains.normalizeEmail(summary.emailAddress()), event,
					messageId(details));
		} catch (SdkException later) {
			log.warn("A suppressed address could not be read from SES: {}", later.getClass().getSimpleName());
			return;
		}
		if (seen.size() >= MAX_REMEMBERED) {
			seen.clear();
		}
		seen.add(key);
	}

	/** SES names the mail with or without its "@region.amazonses.com". */
	private static @Nullable String messageId(SuppressedDestination details) {
		String id = details.attributes() == null ? null : details.attributes().messageId();
		if (id == null || id.isBlank()) {
			return null;
		}
		int at = id.indexOf('@');
		return at < 0 ? id : id.substring(0, at);
	}
}
