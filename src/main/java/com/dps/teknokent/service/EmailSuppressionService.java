package com.dps.teknokent.service;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.EmailSuppressionReason;
import com.dps.teknokent.entity.EmailSuppression;
import com.dps.teknokent.repository.EmailCampaignRecipientRepository;
import com.dps.teknokent.repository.EmailSuppressionRepository;
import com.dps.teknokent.util.Sha256;

/** The addresses the email center no longer writes to. Account mails never ask this service. */
@Service
public class EmailSuppressionService {

	private final EmailSuppressionRepository suppressionRepository;
	private final EmailCampaignRecipientRepository recipientRepository;
	private final Clock clock;

	@Autowired
	public EmailSuppressionService(
			EmailSuppressionRepository suppressionRepository,
			EmailCampaignRecipientRepository recipientRepository) {
		this(suppressionRepository, recipientRepository, Clock.systemUTC());
	}

	EmailSuppressionService(
			EmailSuppressionRepository suppressionRepository,
			EmailCampaignRecipientRepository recipientRepository,
			Clock clock) {
		this.suppressionRepository = suppressionRepository;
		this.recipientRepository = recipientRepository;
		this.clock = clock;
	}

	/** Which of the (normalized) addresses are suppressed, and why. */
	@Transactional(readOnly = true)
	public Map<String, EmailSuppressionReason> reasonsFor(Collection<String> addresses) {
		if (addresses.isEmpty()) {
			return Map.of();
		}
		Map<String, String> addressByHash = addresses.stream()
				.distinct()
				.collect(Collectors.toMap(Sha256::hex, Function.identity()));
		Map<String, EmailSuppressionReason> reasons = new HashMap<>();
		for (EmailSuppression row : suppressionRepository.findByAddressHashIn(addressByHash.keySet())) {
			String address = addressByHash.get(row.getAddressHash());
			if (address != null) {
				reasons.put(address, row.getReason());
			}
		}
		return reasons;
	}

	@Transactional(readOnly = true)
	public boolean isSuppressed(String address) {
		return suppressionRepository.existsByAddressHash(Sha256.hex(address));
	}

	/** Keeps the first reason, except that an unsubscribe is the person's own word and always wins. */
	@Transactional
	public void suppress(String address, EmailSuppressionReason reason) {
		String hash = Sha256.hex(address);
		EmailSuppression row = suppressionRepository.findByAddressHash(hash).orElse(null);
		if (row == null) {
			row = new EmailSuppression();
			row.setId(UUID.randomUUID());
			row.setAddressHash(hash);
			row.setCreatedAt(clock.instant());
		} else if (reason != EmailSuppressionReason.UNSUBSCRIBED) {
			return;
		}
		row.setReason(reason);
		suppressionRepository.save(row);
	}

	/** Says nothing about whether the token is known, so the link cannot be used to probe. */
	@Transactional
	public void unsubscribe(String token) {
		recipientRepository.findByUnsubscribeTokenHash(Sha256.hex(token))
				.ifPresent(recipient -> {
					if (recipient.getAddress() != null) {
						suppress(recipient.getAddress(), EmailSuppressionReason.UNSUBSCRIBED);
					}
				});
	}
}
