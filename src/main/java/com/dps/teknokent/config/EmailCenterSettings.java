package com.dps.teknokent.config;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.EmailBounceSource;

import lombok.Getter;

@Component
@Getter
public class EmailCenterSettings {

	private static final String EVERYBODY = "*";

	private final boolean enabled;
	private final String from;
	private final String replyTo;
	private final Set<String> allowlist;
	private final int ratePerSecond;
	private final int maxRecipients;
	private final String configurationSet;
	private final EmailBounceSource bounceSource;
	private final String eventsQueueUrl;

	public EmailCenterSettings(
			@Value("${app.mail.center.enabled:false}") boolean enabled,
			@Value("${app.mail.from:}") String from,
			@Value("${app.mail.center.reply-to:info@menton.com.tr}") String replyTo,
			@Value("${app.mail.center.recipient-allowlist:simulator.amazonses.com}") String allowlist,
			@Value("${app.mail.center.rate-per-second:10}") int ratePerSecond,
			@Value("${app.mail.center.max-recipients:1000}") int maxRecipients,
			@Value("${app.mail.center.configuration-set:}") String configurationSet,
			@Value("${app.mail.center.bounce-source:none}") String bounceSource,
			@Value("${app.mail.center.events-queue-url:}") String eventsQueueUrl) {
		this.enabled = enabled;
		this.from = from.trim();
		this.replyTo = replyTo.trim();
		this.allowlist = Stream.of(allowlist.split(","))
				.map(entry -> entry.trim().toLowerCase(Locale.ROOT))
				.filter(entry -> !entry.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
		this.ratePerSecond = Math.max(1, ratePerSecond);
		this.maxRecipients = Math.max(1, maxRecipients);
		this.configurationSet = configurationSet.trim();
		this.bounceSource = EmailBounceSource.parse(bounceSource);
		this.eventsQueueUrl = eventsQueueUrl.trim();
	}

	/** Whether the deployment may write to the (normalized) address; a listed domain does not cover subdomains. */
	public boolean mayWriteTo(String address) {
		return allowlist.contains(EVERYBODY) || allowlist.contains(address)
				|| allowlist.contains(address.substring(address.lastIndexOf('@') + 1));
	}

	/** Whether bounces are read from somewhere; a source that is not fully configured does not count. */
	public boolean hearsBounces() {
		return switch (bounceSource) {
		case NONE -> false;
		case SUPPRESSION_LIST -> true;
		case SES_EVENTS -> !eventsQueueUrl.isEmpty() && !configurationSet.isEmpty();
		};
	}

	/** Whether a mail may leave at all: switched on, and with a sender. */
	public boolean canSend() {
		return enabled && !from.isEmpty();
	}
}
