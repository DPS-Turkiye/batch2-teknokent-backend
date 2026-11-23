package com.dps.teknokent.service;

import java.util.Arrays;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;

/** The e-mail next to a match's or a meeting proposal's in-app notification. Off unless the deployment turns it on. */
@Slf4j
@Service
public class MatchProposalMailService {

	private final MailService mailService;
	private final NotificationPreferenceService notificationPreferenceService;
	private final boolean enabled;

	public MatchProposalMailService(
			MailService mailService,
			NotificationPreferenceService notificationPreferenceService,
			@Value("${app.mail.match-proposal.enabled:false}") boolean enabled) {
		this.mailService = mailService;
		this.notificationPreferenceService = notificationPreferenceService;
		this.enabled = enabled;
	}

	/**
	 * Sends {@code key}'s subject and body in the reader's language once the action commits. The texts get the
	 * reader's name, the link and then {@code args}. A failure is logged: a mail never fails the action it tells about.
	 */
	public void send(AppUser reader, NotificationCategory category, String key, String path, Object... args) {
		// Only an approved account can open the link, and a deleted one's address is a placeholder that bounces.
		if (!enabled || reader.getStatus() != UserAccountStatus.APPROVED) {
			return;
		}
		try {
			if (!notificationPreferenceService.allows(reader.getId(), category)) {
				return;
			}
			Object[] shown = Stream.concat(
					Stream.of(reader.getFullName(), mailService.link(path)), Arrays.stream(args)).toArray();
			mailService.sendAfterCommit(
					reader.getEmail(),
					Texts.get(reader, key + ".subject", shown),
					Texts.get(reader, key + ".body", shown));
		} catch (RuntimeException failure) {
			log.warn("Mail {} for user {} could not be prepared", key, reader.getId(), failure);
		}
	}
}
