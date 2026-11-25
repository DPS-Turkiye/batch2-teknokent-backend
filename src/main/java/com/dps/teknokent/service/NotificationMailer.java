package com.dps.teknokent.service;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** The e-mail next to an in-app notification, for a reader who has not switched it off. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationMailer {

	private final MailService mailService;
	private final NotificationPreferenceService notificationPreferenceService;

	/**
	 * Sends {@code key}'s subject and body in the reader's language once the action commits. The texts get the
	 * reader's name, the link and then {@code args}. A failure is logged: a mail never fails the action it tells about.
	 */
	public void send(AppUser reader, NotificationCategory category, String key, String path, Object... args) {
		// Only an approved account can open the link, and a deleted one's address is a placeholder that bounces.
		if (reader.getStatus() != UserAccountStatus.APPROVED) {
			return;
		}
		try {
			UUID readerId = reader.getId();
			Object[] shown = Stream.concat(
					Stream.of(reader.getFullName(), mailService.link(path)), Arrays.stream(args)).toArray();
			mailService.sendAfterCommit(
					reader.getEmail(),
					Texts.get(reader, key + ".subject", shown),
					Texts.get(reader, key + ".body", shown),
					() -> notificationPreferenceService.allowsEmail(readerId, category));
		} catch (RuntimeException failure) {
			log.warn("Mail {} for user {} could not be prepared", key, reader.getId(), failure);
		}
	}
}
